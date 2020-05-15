package hadesc.parser

import hadesc.ast.*
import hadesc.context.Context
import hadesc.diagnostics.Diagnostic
import hadesc.ir.BinaryOperator
import hadesc.location.HasLocation
import hadesc.location.Position
import hadesc.location.SourceLocation
import hadesc.location.SourcePath
import hadesc.qualifiedname.QualifiedName

internal typealias tt = Token.Kind

private val declarationRecoveryTokens = setOf(tt.EOF, tt.IMPORT, tt.DEF, tt.EXTERN, tt.STRUCT, tt.CONST)
private val statementPredictors = setOf(tt.RETURN, tt.VAL, tt.WHILE, tt.IF)
private val statementRecoveryTokens: Set<TokenKind> = setOf(tt.EOF, tt.WHILE) + statementPredictors
private val byteStringEscapes = mapOf(
        'n' to '\n',
        '0' to '\u0000'
)

private val OPERATORS = listOf(
        setOf(tt.AND, tt.OR),
        setOf(
                tt.LESS_THAN,
                tt.LESS_THAN_EQUAL,
                tt.GREATER_THAN_EQUAL,
                tt.GREATER_THAN),
        setOf(tt.EQEQ, tt.BANG_EQ),
        setOf(tt.PLUS, tt.MINUS),
        setOf(tt.STAR)
)

typealias op = BinaryOperator

private val BINARY_OPERATORS = mapOf(
        tt.LESS_THAN to op.LESS_THAN,
        tt.LESS_THAN_EQUAL to op.LESS_THAN_EQUAL,
        tt.GREATER_THAN to op.GREATER_THAN,
        tt.GREATER_THAN_EQUAL to op.GREATER_THAN_EQUAL,
        tt.PLUS to op.PLUS,
        tt.MINUS to op.MINUS,
        tt.STAR to op.TIMES,
        tt.AND to op.AND,
        tt.OR to op.OR,
        tt.EQEQ to op.EQUALS,
        tt.BANG_EQ to op.NOT_EQUALS
).apply {
    for (tokenType in keys) {
        require(OPERATORS.any { it.contains(tokenType) }) {
            "operator token type $tokenType missing in precedence table"
        }
    }
}

object SyntaxError : Error()

@OptIn(ExperimentalStdlibApi::class)
class Parser(
        private val ctx: Context,
        private val moduleName: QualifiedName,
        val file: SourcePath
) {
    private val tokenBuffer = TokenBuffer(maxLookahead = 3, lexer = Lexer(file))
    private val currentToken get() = tokenBuffer.currentToken

    fun parseSourceFile(): SourceFile {
        val declarations = parseDeclarations()
        val start = Position(1, 1)
        val location = SourceLocation(file, start, currentToken.location.stop)
        val sourceFile = SourceFile(location, moduleName, declarations)
        ctx.resolver.onParseSourceFile(sourceFile)
        return sourceFile
    }

    private fun parseDeclarations(): List<Declaration> = buildList<Declaration> {
        while (currentToken.kind != tt.EOF) {
            try {
                add(parseDeclaration())
            } catch (e: SyntaxError) {
                recoverFromError(stopBefore = declarationRecoveryTokens)
            }
        }
    }.toList()

    private fun parseDeclaration(): Declaration {
        val decl = when (currentToken.kind) {
            tt.IMPORT -> parseDeclarationImportAs()
            tt.DEF -> parseDeclarationFunctionDef()
            tt.STRUCT -> parseStructDeclaration()
            tt.EXTERN -> parseExternFunctionDef()
            tt.CONST -> parseConstDef()
            else -> {
                syntaxError(currentToken.location, Diagnostic.Kind.DeclarationExpected)
            }
        }
        ctx.resolver.onParseDeclaration(decl)
        return decl
    }

    private fun parseConstDef(): Declaration.ConstDefinition {
        val start = expect(tt.CONST)
        val name = parseBinder()
        expect(tt.EQ)
        val rhs = parseExpression()
        expect(tt.SEMICOLON)
        return Declaration.ConstDefinition(
                makeLocation(start, rhs),
                name,
                rhs
        )
    }

    private fun parseStructDeclaration(): Declaration {
        val start = expect(tt.STRUCT)
        val binder = parseBinder()
        val typeParams = parseOptionalTypeParams()

        expect(tt.LBRACE)
        val members = buildList {
            while (!isEOF() && !at(tt.RBRACE)) {
                add(parseStructMember())
            }
        }
        val stop = expect(tt.RBRACE)

        return Declaration.Struct(
                makeLocation(start, stop),
                binder,
                typeParams,
                members
        )
    }

    private fun parseStructMember(): Declaration.Struct.Member = when (currentToken.kind) {
        tt.VAL -> parseValStructMember()
        else -> {
            syntaxError(currentToken.location, Diagnostic.Kind.DeclarationExpected)
        }
    }

    private fun parseValStructMember(): Declaration.Struct.Member {
        expect(tt.VAL)
        val binder = parseBinder()
        expect(tt.COLON)
        val annotation = parseTypeAnnotation()
        expect(tt.SEMICOLON)
        return Declaration.Struct.Member.Field(
                binder,
                annotation
        )
    }

    private fun parseExternFunctionDef(): Declaration {
        val start = expect(tt.EXTERN)
        expect(tt.DEF)
        val name = parseBinder()
        expect(tt.LPAREN)
        val params = parseSeperatedList(seperator = tt.COMMA, terminator = tt.RPAREN) {
            parseTypeAnnotation()
        }
        expect(tt.RPAREN)
        expect(tt.COLON)
        val returnType = parseTypeAnnotation()
        expect(tt.EQ)

        val externName = parseIdentifier()

        expect(tt.SEMICOLON)
        return Declaration.ExternFunctionDef(
                makeLocation(start, returnType),
                binder = name,
                paramTypes = params,
                returnType = returnType,
                externName = externName
        )
    }

    private fun makeLocation(start: HasLocation, stop: HasLocation): SourceLocation {
        return SourceLocation.between(start, stop)
    }

    private fun parseDeclarationImportAs(): Declaration {
        expect(tt.IMPORT)
        val modulePath = parseQualifiedPath()
        expect(tt.AS)
        val asName = parseBinder()
        expect(Token.Kind.SEMICOLON)

        return Declaration.ImportAs(modulePath, asName)
    }

    private fun parseQualifiedPath(): QualifiedPath = QualifiedPath(buildList {
        add(parseIdentifier())
        while (at(tt.DOT)) {
            advance()
            add(parseIdentifier())
        }
    })

    private fun parseIdentifier(): Identifier {
        val tok = expect(tt.ID)
        return Identifier(tok.location, ctx.makeName(tok.text))
    }

    private fun parseDeclarationFunctionDef(): Declaration {
        val start = expect(tt.DEF)
        val name = parseBinder()
        val typeParams = parseOptionalTypeParams()
        val scopeStartToken = expect(tt.LPAREN)
        val (thisParam, params) = parseParams(scopeStartToken)
        expect(tt.COLON)
        val annotation = parseTypeAnnotation()
        val block = parseBlock()
        return Declaration.FunctionDef(
                location = makeLocation(start, block),
                name = name,
                scopeStartToken = scopeStartToken,
                typeParams = typeParams,
                thisParam = thisParam,
                params = params,
                returnType = annotation,
                body = block
        )
    }

    private fun parseOptionalTypeParams(): List<TypeParam>? = if (currentToken.kind == tt.LSQB) {
        advance()
        val list: List<TypeParam> = parseSeperatedList(tt.COMMA, tt.RSQB) {
            TypeParam(parseBinder())
        }
        expect(tt.RSQB)
        list
    } else {
        null
    }

    private fun parseBlock(): Block {
        val start = expect(tt.LBRACE)
        val members = parseBlockMembers()
        val stop = expect(tt.RBRACE)
        val result = Block(makeLocation(start, stop), members)
        ctx.resolver.onParseBlock(result)
        return result
    }

    private fun parseBlockMembers(): List<Block.Member> = buildList {
        while (!(at(tt.RBRACE) || at(tt.EOF))) {
            try {
                add(parseBlockMember())
            } catch (e: SyntaxError) {
                recoverFromError(statementRecoveryTokens)
            }
        }
    }

    private fun parseBlockMember(): Block.Member {
        return when {
            isStatementPredicted() -> Block.Member.Statement(parseStatement())
            else -> {
                val expr = parseExpression()
                expect(tt.SEMICOLON)
                Block.Member.Expression(expr)
            }
        }
    }

    private fun isStatementPredicted(): Boolean {
        return currentToken.kind in statementPredictors
                // LocalAssignment
                || (currentToken.kind == TokenKind.ID && tokenBuffer.peek(1).kind == TokenKind.EQ)
    }

    private fun parseStatement(): Statement {
        return when (currentToken.kind) {
            tt.RETURN -> parseReturnStatement()
            tt.VAL -> parseValStatement()
            tt.WHILE -> parseWhileStatement()
            tt.IF -> parseIfStatement()
            tt.ID -> parseLocalAssignment()
            else -> {
                syntaxError(currentToken.location, Diagnostic.Kind.StatementExpected)
            }
        }
    }

    private fun parseLocalAssignment(): Statement {
        val name = parseIdentifier()
        expect(tt.EQ)
        val value = parseExpression()
        expect(tt.SEMICOLON)
        return Statement.LocalAssignment(
                makeLocation(name, value),
                name,
                value
        )
    }

    private fun parseIfStatement(): Statement {
        val start = expect(tt.IF)
        val condition = parseExpression()
        val ifTrue = parseBlock()
        val ifFalse = if (at(tt.ELSE)) {
            advance()
            parseBlock()
        } else {
            null
        }
        return Statement.If(
                location = makeLocation(start, ifFalse ?: ifTrue),
                condition = condition,
                ifTrue = ifTrue,
                ifFalse = ifFalse
        )
    }

    private fun parseWhileStatement(): Statement {
        val start = expect(tt.WHILE)
        val condition = parseExpression()
        val block = parseBlock()
        return Statement.While(
                makeLocation(start, block),
                condition,
                block
        )
    }

    private fun parseReturnStatement(): Statement {
        val start = expect(tt.RETURN)
        val value = parseExpression()
        expect(tt.SEMICOLON)
        return Statement.Return(
                makeLocation(start, value),
                value
        )
    }

    private fun parseValStatement(): Statement {
        val start = expect(tt.VAL)
        val isMutable = if (at(tt.MUT)) {
            advance()
            true
        } else {
            false
        }
        val binder = parseBinder()
        val typeAnnotation = parseOptionalAnnotation()
        expect(tt.EQ)
        val rhs = parseExpression()
        expect(tt.SEMICOLON)
        return Statement.Val(
                makeLocation(start, rhs),
                isMutable,
                binder,
                typeAnnotation,
                rhs
        )
    }

    private fun parseExpression(): Expression {
        return parseExpressionMinPrecedence(0)
    }

    private fun parseExpressionMinPrecedence(minPrecedence: Int): Expression {
        return if (minPrecedence == OPERATORS.size) {
            parsePrimaryExpression()
        } else {
            var currentExpression = parseExpressionMinPrecedence(minPrecedence + 1)
            while (currentToken.kind in OPERATORS[minPrecedence]) {
                val opToken = advance()
                currentExpression = makeBinOp(
                        currentExpression,
                        opToken.kind,
                        parseExpressionMinPrecedence(minPrecedence + 1)
                )
            }

            currentExpression
        }
    }

    private fun makeBinOp(lhs: Expression, operatorToken: TokenKind, rhs: Expression): Expression {
        val operator = requireNotNull(BINARY_OPERATORS[operatorToken]) {
            "Bug: Token type not found in binary operators table $operatorToken"
        }
        return Expression.BinaryOperation(
                makeLocation(lhs, rhs),
                lhs,
                operator,
                rhs
        )
    }

    private fun parsePrimaryExpression(): Expression {
        val head = when (currentToken.kind) {
            tt.LPAREN -> {
                advance()
                val result = parseExpression()
                expect(tt.RPAREN)
                result
            }
            tt.ID -> parseExpressionVar()
            tt.BYTE_STRING -> parseExpressionByteString()
            tt.NULLPTR -> {
                Expression.NullPtr(advance().location)
            }
            tt.INT_LITERAL -> {
                val token = advance()
                Expression.IntLiteral(token.location, token.text.toInt())
            }
            tt.HEX_INT_LITERAL -> {
                val token = advance()
                require(token.text.startsWith("0x"))
                Expression.IntLiteral(token.location, token.text.drop(2).toInt(16))
            }
            tt.TRUE -> {
                Expression.BoolLiteral(advance().location, true)
            }
            tt.FALSE -> {
                Expression.BoolLiteral(advance().location, false)
            }
            tt.NOT -> {
                val start = advance()
                val expression = parsePrimaryExpression()
                Expression.Not(makeLocation(start, expression), expression)
            }
            tt.THIS -> {
                Expression.This(advance().location)
            }
            tt.SIZE_OF -> {
                val start = advance()
                expect(tt.LSQB)
                val type = parseTypeAnnotation()
                val stop = expect(tt.RSQB)
                Expression.SizeOf(makeLocation(start, stop), type)
            }
            tt.AMPERSAND -> {
                val start = advance()
                val expression = parsePrimaryExpression()
                Expression.AddressOf(makeLocation(start, expression), expression)
            }
            tt.STAR -> {
                val start = advance()
                val expression = parseExpression()
                Expression.Load(makeLocation(start, expression), expression)
            }
            tt.POINTER_CAST -> {
                val start = advance()
                expect(tt.LSQB)
                val toType = parseTypeAnnotation()
                expect(tt.RSQB)
                expect(tt.LPAREN)
                val arg = parseExpression()
                val stop = expect(tt.RPAREN)
                Expression.PointerCast(
                        makeLocation(start, stop),
                        toType,
                        arg
                )

            }
            else -> {
                val location = advance().location
                syntaxError(location, Diagnostic.Kind.ExpressionExpected)
            }
        }
        return parseExpressionTail(head)
    }

    private fun parseExpressionByteString(): Expression {
        val token = expect(tt.BYTE_STRING)
        val bytes = buildList {
            val firstCharIndexInQuote = 2
            var i = firstCharIndexInQuote
            while (i < token.text.length - 1) {
                val char = token.text[i]
                if (char == '\\') {
                    i++
                    assert(i < token.text.length - 1) { TODO("Byte string ended abruptly") }
                    val escapeChar = byteStringEscapes[token.text[i]]
                    if (escapeChar != null) {
                        add(escapeChar.toByte())
                    } else {
                        TODO("Invalid byte string escape $escapeChar in ${token.location}")
                    }

                } else {
                    addAll("$char".encodeToByteArray().toList())
                }

                i++
            }
        }
        return Expression.ByteString(token.location, bytes.toByteArray())
    }

    private fun parseExpressionTail(head: Expression): Expression {
        return when (currentToken.kind) {
            tt.LSQB -> {
                advance()
                val typeArgs = parseSeperatedList(tt.COMMA, tt.RSQB) {
                    parseTypeAnnotation()
                }
                expect(tt.RSQB)
                expect(tt.LPAREN)
                val args = parseSeperatedList(tt.COMMA, tt.RPAREN) {
                    parseArg()
                }
                val stop = expect(tt.RPAREN)
                parseExpressionTail(
                        Expression.Call(
                                makeLocation(head, stop),
                                typeArgs,
                                head,
                                args
                        )
                )
            }
            tt.LPAREN -> {
                advance()
                val args = parseSeperatedList(tt.COMMA, tt.RPAREN) {
                    parseArg()
                }
                val stop = expect(tt.RPAREN)
                parseExpressionTail(
                        Expression.Call(
                                makeLocation(head, stop),
                                null,
                                head,
                                args
                        )
                )
            }
            tt.DOT -> {
                advance()
                val ident = parseIdentifier()
                parseExpressionTail(
                        Expression.Property(
                                makeLocation(head, ident),
                                head,
                                ident
                        )
                )
            }
            else -> head
        }
    }

    private fun parseArg(): Arg = Arg(parseExpression())

    private fun parseExpressionVar(): Expression {
        val identifier = parseIdentifier()
        return Expression.Var(identifier)
    }

    private fun parseParams(lparen: Token? = null): Pair<ThisParam?, List<Param>> {
        var thisParam: ThisParam? = null
        val params = buildList {
            lparen ?: expect(tt.LPAREN)
            var first = true
            while (!(at(tt.RPAREN) || at(tt.EOF))) {
                if (!first) {
                    expect(tt.COMMA)
                } else {
                    first = false
                    if (at(tt.THIS)) {
                        val start = advance()
                        expect(tt.COLON)
                        val annotation = parseTypeAnnotation()
                        thisParam = ThisParam(makeLocation(start, annotation), annotation)
                        continue
                    }
                }
                add(parseParam())
            }
            expect(tt.RPAREN)
        }
        return thisParam to params
    }

    private fun parseParam(): Param {
        val binder = parseBinder()
        val annotation = parseOptionalAnnotation()
        return Param(
                binder = binder,
                annotation = annotation
        )
    }

    private fun parseBinder(): Binder {
        return Binder(parseIdentifier())
    }

    private fun parseOptionalAnnotation(): TypeAnnotation? = if (at(tt.COLON)) {
        expect(tt.COLON)
        parseTypeAnnotation()
    } else {
        null
    }

    private fun parseTypeAnnotation(): TypeAnnotation {
        val head = when (currentToken.kind) {
            tt.ID -> {
                val id = parseIdentifier()
                if (at(tt.DOT)) {
                    advance()
                    val second = parseIdentifier()
                    val path = QualifiedPath(listOf(id, second))
                    TypeAnnotation.Qualified(makeLocation(id, second), path)
                } else {
                    TypeAnnotation.Var(id)
                }
            }
            tt.STAR -> {
                val start = advance()
                val to = parseTypeAnnotation()
                TypeAnnotation.Ptr(
                        makeLocation(start, to),
                        to
                )
            }
            tt.LPAREN -> {
                val start = advance()
                val from = parseSeperatedList(tt.COMMA, terminator = tt.RPAREN) {
                    parseTypeAnnotation()
                }
                expect(tt.RPAREN)
                expect(tt.ARROW)
                val to = parseTypeAnnotation()
                TypeAnnotation.Function(
                        makeLocation(start, to),
                        from,
                        to
                )
            }
            else -> {
                val location = advance().location
                syntaxError(location, Diagnostic.Kind.TypeAnnotationExpected)
            }
        }
        return parseTypeAnnotationTail(head)
    }

    private fun <T> syntaxError(location: SourceLocation, kind: Diagnostic.Kind): T {
        ctx.diagnosticReporter.report(location, kind)
        throw SyntaxError
    }

    private fun parseTypeAnnotationTail(head: TypeAnnotation): TypeAnnotation {
        return when (currentToken.kind) {
            tt.LSQB -> {
                advance()
                val args = parseSeperatedList(seperator = tt.COMMA, terminator = tt.RSQB) {
                    parseTypeAnnotation()
                }
                val end = expect(tt.RSQB)
                TypeAnnotation.Application(
                        makeLocation(head, end),
                        head,
                        args
                )
            }
            else -> head
        }
    }

    private fun <T> parseSeperatedList(
            seperator: Token.Kind,
            terminator: Token.Kind,
            parseItem: () -> T
    ): List<T> = buildList {
        var isFirst = true
        while (currentToken.kind != terminator && currentToken.kind != tt.EOF) {
            if (!isFirst) {
                expect(seperator)
            }
            isFirst = false
            add(parseItem())
        }
    }

    private fun expect(kind: Token.Kind): Token {
        return if (currentToken.kind == kind) {
            advance()
        } else {
            syntaxError(currentToken.location, Diagnostic.Kind.UnexpectedToken(kind, currentToken))
        }
    }

    private fun at(kind: tt): Boolean = currentToken.kind == kind

    private fun recoverFromError(
            stopBefore: Set<tt> = declarationRecoveryTokens
    ) {
        while (true) {
            if (isEOF()) {
                break
            } else if (currentToken.kind == Token.Kind.SEMICOLON) {
                advance()
                break
            } else if (stopBefore.contains(currentToken.kind)) {
                break
            } else {
                advance()
            }
        }
    }

    private fun advance(): Token {
        return tokenBuffer.advance()
    }

    private fun isEOF() =
            currentToken.kind == tt.EOF
}

class TokenBuffer(private val maxLookahead: Int, private val lexer: Lexer) {
    private val buffer: Array<Token> = Array(maxLookahead) { lexer.nextToken() }

    private var current = 0

    val currentToken: Token get() {
        return buffer[current]
    }

    fun advance(): Token {
        val result = currentToken
        buffer[current] = lexer.nextToken()
        current = (current + 1) % maxLookahead
        return result
    }

    fun peek(offset: Int): Token {
        require(offset < maxLookahead) {"Tried to peek past max lookahead $maxLookahead"}
        return buffer[(current + offset) % maxLookahead]
    }

}

