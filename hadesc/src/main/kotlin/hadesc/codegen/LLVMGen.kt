package hadesc.codegen

import dev.supergrecko.kllvm.core.types.IntType
import dev.supergrecko.kllvm.core.types.PointerType
import dev.supergrecko.kllvm.core.types.StructType
import dev.supergrecko.kllvm.core.values.FunctionValue
import dev.supergrecko.kllvm.core.values.InstructionValue
import dev.supergrecko.kllvm.core.values.IntValue
import dev.supergrecko.kllvm.core.values.PointerValue
import hadesc.ast.*
import hadesc.context.Context
import hadesc.location.SourceLocation
import hadesc.location.SourcePath
import hadesc.logging.logger
import hadesc.qualifiedname.QualifiedName
import hadesc.resolver.ValueBinding
import hadesc.types.Type
import llvm.*
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.llvm.LLVM.LLVMTargetMachineRef
import org.bytedeco.llvm.global.LLVM

@OptIn(ExperimentalStdlibApi::class)
class LLVMGen(private val ctx: Context) : AutoCloseable {
    private val log = logger()
    private val llvmCtx = llvm.Context()
    private val llvmModule = llvm.Module(ctx.options.main.toString(), llvmCtx)
    private val builder = llvm.Builder(llvmCtx)
    private val qualifiedNameToFunctionValue = mutableMapOf<QualifiedName, FunctionValue>()
    private val specializationStacks = mutableMapOf<QualifiedName, MutableList<FunctionValue>>()

    fun generate() {
        log.info("Generating LLVM IR")
        lowerSourceFile(ctx.sourceFile(QualifiedName(), ctx.mainPath()))
        log.debug(LLVM.LLVMPrintModuleToString(llvmModule.getUnderlyingReference()).string)
        verifyModule()
        writeModuleToFile()
        linkWithRuntime()
    }

    private fun verifyModule() {
        // TODO: Handle this in a better way
        val buffer = ByteArray(100)
        val len = LLVM.LLVMVerifyModule(llvmModule.getUnderlyingReference(), 100, buffer)
        println(buffer.decodeToString().slice(0 until len))
    }

    private val completedSourceFiles = mutableSetOf<SourcePath>()
    private fun lowerSourceFile(sourceFile: SourceFile) {
        if (completedSourceFiles.contains(sourceFile.location.file)) {
            return
        }
        log.debug("START: LLVMGen::lowerSourceFile(${sourceFile.location.file})")
        for (declaration in sourceFile.declarations) {
            ctx.checker.checkDeclaration(declaration)
            lowerDeclaration(declaration)
        }
        completedSourceFiles.add(sourceFile.location.file)
        log.debug("DONE: LLVMGen::lowerSourceFile(${sourceFile.location.file})")
    }

    private fun lowerDeclaration(declaration: Declaration) = when (declaration.kind) {
        is Declaration.Kind.ImportAs -> {
            // Imports don't generate anything
        }
        is Declaration.Kind.FunctionDef -> lowerFunctionDefDeclaration(declaration, declaration.kind)
        Declaration.Kind.Error -> {
        }
        is Declaration.Kind.ExternFunctionDef -> lowerExternDefDeclaration(declaration, declaration.kind)
        is Declaration.Kind.Struct -> {
            lowerStructDeclaration(declaration, declaration.kind)
        }
    }

    private fun lowerStructDeclaration(declaration: Declaration, kind: Declaration.Kind.Struct) {
        val constructorType = ctx.checker.typeOfStructConstructor(kind)
        val instanceType = ctx.checker.typeOfStructInstance(kind)
        val constructorFunction = llvmModule.addFunction(
            lowerBinder(kind.binder),
            lowerType(constructorType) as FunctionType
        )
        val basicBlock = constructorFunction.appendBasicBlock("entry")

        generateInBlock(basicBlock) {

            val thisPtr = LLVM.LLVMBuildAlloca(
                getUnderlyingRef(),
                lowerType(instanceType).getUnderlyingReference(),
                "instance"
            )

            val instanceStructType = instanceType as Type.Struct
            for (i in instanceStructType.memberTypes.entries.indices) {
                val paramRef = LLVM.LLVMGetParam(constructorFunction.getUnderlyingReference(), i)
                val elementPtr = LLVM.LLVMBuildStructGEP(
                    getUnderlyingRef(),
                    thisPtr,
                    i,
                    "field_${i}"
                )
                LLVM.LLVMBuildStore(getUnderlyingRef(), paramRef, elementPtr)

            }

            val result = LLVM.LLVMBuildLoad(getUnderlyingRef(), thisPtr, "result")
            buildRet(PointerValue(result))

        }
        constructorFunction.verify()
    }

    private fun FunctionValue.verify() {
        val validate = LLVM.LLVMVerifyFunction(getUnderlyingReference(), LLVM.LLVMPrintMessageAction)
        if (validate > 0) {
            log.debug("Bad function: ${dumpToString()}")
            TODO()
        }
    }

    private fun generateInBlock(basicBlock: BasicBlock, function: Builder.() -> Unit) {
        val oldPosition = builder.getInsertBlock()
        builder.positionAtEnd(basicBlock)

        builder.function()

        if (oldPosition != null) {
            builder.positionAtEnd(oldPosition)
        }
    }

    private fun lowerExternDefDeclaration(declaration: Declaration, kind: Declaration.Kind.ExternFunctionDef) {
        llvmModule.addFunction(
            kind.externName.name.text, FunctionType(
                returns = lowerTypeAnnotation(kind.returnType),
                types = kind.paramTypes.map { lowerTypeAnnotation(it) },
                variadic = false
            )
        )
    }

    private fun lowerFunctionDefDeclaration(declaration: Declaration, def: Declaration.Kind.FunctionDef) {
        if (def.typeParams.isNotEmpty()) {
            // we can't lower generic functions here.
            // we have to generate seperate versions
            // of this function for each call site with
            // type parameters substituted with actual
            // types
            return
        }
        val binding = ctx.resolver.getBinding(def.name.identifier)
        val qualifiedName = when (binding) {
            is ValueBinding.GlobalFunction -> binding.qualifiedName
            else -> {
                throw AssertionError(
                    "Expected function def declaration to bind to ValueBinding.GlobalFunction"
                )
            }
        }

        val returnType = ctx.checker.annotationToType(def.returnType)
        val type = FunctionType(
            lowerTypeAnnotation(def.returnType),
            def.params.map { lowerParamToType(it) },
            false
        )
        val func = llvmModule.addFunction(lowerBinder(def.name), type)

        qualifiedNameToFunctionValue[qualifiedName] = func

        LLVM.LLVMSetLinkage(func.getUnderlyingReference(), LLVM.LLVMExternalLinkage)
        val basicBlock = func.appendBasicBlock("entry")
        val previousPosition = builder.getInsertBlock()
        builder.positionAtEnd(basicBlock)
        for (member in def.body.members) {
            lowerBlockMember(member)
        }
        if (returnType == Type.Void) {
            builder.buildRetVoid()
        }
        log.debug("function: $qualifiedName; params: ${def.params.size}")
        log.debug(func.dumpToString())
        if (previousPosition != null) {
            builder.positionAtEnd(previousPosition)
        }
        func.verify()
    }

    private fun lowerTypeAnnotation(annotation: TypeAnnotation): llvm.Type {
        return lowerType(ctx.checker.annotationToType(annotation))
    }

    private fun lowerType(type: Type): llvm.Type = when (type) {
        Type.Error -> TODO()
        Type.Byte -> byteTy
        Type.Void -> voidTy
        is Type.ModuleAlias -> TODO("Bug: Module alias can't be lowered")
        is Type.Bool -> boolTy
        is Type.RawPtr -> ptrTy(lowerType(type.to))
        is Type.Function -> FunctionType(
            returns = lowerType(type.to),
            types = type.from.map { lowerType(it) },
            variadic = false
        )
        is Type.Struct -> StructType(
            type.memberTypes.values.map { lowerType(it) },
            packed = false,
            ctx = llvmCtx
        )
        is Type.ParamRef, is Type.GenericFunction, is Type.Deferred ->
            TODO("Can't lower unspecialized type param")
    }

    private fun lowerParamToType(param: Param): llvm.Type {
        assert(param.annotation != null) { "Inferred param types not implemented yet" }
        return lowerTypeAnnotation(param.annotation as TypeAnnotation)
    }

    private fun lowerBlockMember(member: Block.Member): Unit = when (member) {
        is Block.Member.Expression -> {
            lowerExpression(member.expression)
            Unit
        }
        is Block.Member.Statement -> {
            lowerStatement(member.statement)
        }
    }

    private fun lowerStatement(statement: Statement): Unit = when (statement.kind) {
        is Statement.Kind.Return -> lowerReturnStatement(statement, statement.kind)
        is Statement.Kind.Val -> lowerValStatement(statement, statement.kind)
        Statement.Kind.Error -> TODO()
    }

    private val localVariables = mutableMapOf<SourceLocation, llvm.Value>()
    private fun lowerValStatement(statement: Statement, kind: Statement.Kind.Val) {
        val instr = InstructionValue(
            LLVM.LLVMBuildAlloca(
                builder.getUnderlyingRef(),
                lowerType(ctx.checker.typeOfExpression(kind.rhs)).getUnderlyingReference(),
                kind.binder.identifier.name.text + "_tmp"
            )
        )
        val rhs = lowerExpression(kind.rhs)
        InstructionValue(
            LLVM.LLVMBuildStore(
                builder.getUnderlyingRef(),
                rhs.getUnderlyingReference(),
                instr.getUnderlyingReference()
            )
        )
        localVariables[kind.binder.location] = instr
        log.debug(LLVM.LLVMPrintModuleToString(llvmModule.getUnderlyingReference()).string)
    }

    private fun lowerReturnStatement(statement: Statement, kind: Statement.Kind.Return) {
        builder.buildRet(lowerExpression(kind.value))
    }

    private fun lowerExpression(expr: Expression): llvm.Value {
        // for now, this is only to ensure that we check each expression
        // eventually, the pipeline should ensure that everything is
        // typechecked before we reach codegen phase
        ctx.checker.typeOfExpression(expr)
        return when (expr.kind) {
            Expression.Kind.Error -> TODO("Syntax error: ${expr.location}")
            is Expression.Kind.Var -> lowerVarExpression(expr, expr.kind)
            is Expression.Kind.Call -> {
                lowerCallExpression(expr, expr.kind)
            }
            is Expression.Kind.Property -> lowerPropertyExpression(expr, expr.kind)
            is Expression.Kind.ByteString -> lowerByteStringExpression(expr, expr.kind)
            is Expression.Kind.BoolLiteral -> {
                if (expr.kind.value) {
                    trueValue
                } else {
                    falseValue
                }
            }
        }
    }

    private fun lowerPropertyExpression(expr: Expression, kind: Expression.Kind.Property): Value {
        // the reason this isn't as simple as lowerLHS -> extractvalue on lowered lhs
        // is because lhs might not be an actual runtime value, for example when
        // it refers to a module alias (import x as y). Here, y isn't a real value
        // in that case, we have to resolve y.z to a qualified name and use that
        // value instead (x.y.z).
        // If it's not a module alias, then we do the extractvalue (lower lhs) thingy
        // (lowerValuePropertyExpression)
        return when (kind.lhs.kind) {
            is Expression.Kind.Var -> {
                when (val binding = ctx.resolver.getBinding(kind.lhs.kind.name)) {
                    is ValueBinding.ImportAs -> {
                        val sourceFile = ctx.resolveSourceFile(binding.kind.modulePath)
                        lowerSourceFile(sourceFile)
                        val qualifiedName = ctx.resolver.findInSourceFile(kind.property, sourceFile)?.qualifiedName
                            ?: throw AssertionError("${expr.location}: No such property")
                        lowerQualifiedValueName(qualifiedName)
                    }
                    else -> {
                        lowerValuePropertyExpression(expr, kind)
                    }
                }
            }
            else ->
                lowerValuePropertyExpression(expr, kind)
        }
    }

    private fun lowerValuePropertyExpression(expr: Expression, kind: Expression.Kind.Property): Value {
        val lhsPtr = lowerExpression(kind.lhs)
        return when (val type = ctx.checker.typeOfExpression(kind.lhs)) {
            Type.Byte,
            Type.Void,
            Type.Error,
            Type.Bool,
            is Type.RawPtr,
            is Type.Function -> TODO("Can't call dot operator")
            is Type.Struct -> {
                val rhsName = kind.property.name
                val index = type.memberTypes.keys.indexOf(rhsName)
                val extractValueRef = LLVM.LLVMBuildExtractValue(
                    builder.getUnderlyingRef(),
                    lhsPtr.getUnderlyingReference(),
                    index,
                    ""
                )
                InstructionValue(
                    extractValueRef
                )
            }
            is Type.ModuleAlias -> TODO("Should not be reached")
            is Type.ParamRef, is Type.GenericFunction -> TODO("Can't lower unspecialized type")
            is Type.Deferred -> TODO("Can't lower unspecialized type")
        }
    }

    private fun lowerByteStringExpression(expr: Expression, kind: Expression.Kind.ByteString): Value {
        val text = kind.bytes.decodeToString()
        val constStringRef = LLVM.LLVMConstString(text, text.length, 0)
        val globalRef = LLVM.LLVMAddGlobal(
            llvmModule.getUnderlyingReference(),
            LLVM.LLVMTypeOf(constStringRef),
            stringLiteralName()
        )
        LLVM.LLVMSetInitializer(globalRef, constStringRef)
        val ptrRef = LLVM.LLVMConstPointerCast(globalRef, bytePtrTy.getUnderlyingReference())
        return PointerValue(ptrRef)
    }

    private var nextLiteralIndex = 0
    private fun stringLiteralName(): String {
        nextLiteralIndex++
        return "\$string_literal_$nextLiteralIndex"
    }

    private var nextNameIndex = 0
    private fun generateUniqueName(): String {
        nextNameIndex++
        return "\$_$nextNameIndex"
    }

    private val byteTy = IntType(8, llvmCtx)
    private val bytePtrTy = PointerType(byteTy)
    private val voidTy = VoidType(llvmCtx)
    private val boolTy = IntType(1, llvmCtx)
    private val trueValue = IntValue(boolTy, 1, false)
    private val falseValue = IntValue(0, false)

    private fun ptrTy(to: llvm.Type): llvm.Type {
        return PointerType(to)
    }

    private fun lowerQualifiedValueName(qualifiedName: QualifiedName): Value {
        val binding = ctx.resolver.resolveQualifiedName(qualifiedName)
        return when (binding) {
            null -> TODO("Unbound name $qualifiedName")
            is ValueBinding.GlobalFunction -> {
                llvmModule.getFunction(mangleQualifiedName(binding.qualifiedName))
                    ?: throw AssertionError(
                        "Function ${binding.qualifiedName} hasn't been added to llvm module"
                    )
            }
            is ValueBinding.ExternFunction ->
                llvmModule.getFunction(binding.kind.externName.name.text)
                    ?: throw AssertionError(
                        "Function ${binding.qualifiedName} hasn't been added to llvm module"
                    )

            is ValueBinding.FunctionParam -> {
                assert(qualifiedName.size == 1)
                val name = qualifiedName.first
                val functionQualifiedName = ctx.resolver.getBinding(binding.kind.name.identifier).qualifiedName
                val index = binding.kind.params.indexOfFirst { it.binder.identifier.name == name }
                assert(index > -1)
                val specialization = specializationStacks[functionQualifiedName]?.lastOrNull()
                if (specialization != null) {
                    Value(LLVM.LLVMGetParam(specialization.getUnderlyingReference(), index))
                } else {
                    val functionValue: FunctionValue = qualifiedNameToFunctionValue[functionQualifiedName]
                        ?: throw AssertionError("no function value for param binding")
                    Value(LLVM.LLVMGetParam(functionValue.getUnderlyingReference(), index))
                }
            }
            is ValueBinding.ImportAs -> TODO("${binding.kind.asName.identifier.name.text} is not a valid expression")
            is ValueBinding.ValBinding ->
                InstructionValue(
                    LLVM.LLVMBuildLoad(
                        builder.getUnderlyingRef(),
                        localVariables[binding.kind.binder.location]?.getUnderlyingReference(),
                        generateUniqueName()
                    )
                )
            is ValueBinding.Struct ->
                llvmModule.getFunction(mangleQualifiedName(binding.qualifiedName))
                    ?: throw AssertionError(
                        "Function ${binding.qualifiedName} hasn't been added to llvm module"
                    )
        }
    }

    private fun lowerVarExpression(expr: Expression, kind: Expression.Kind.Var): Value {
        val binding = ctx.resolver.getBinding(kind.name)
        return lowerQualifiedValueName(binding.qualifiedName)
    }

    private fun mangleQualifiedName(qualifiedName: QualifiedName): String {
        return qualifiedName.mangle()
    }


    private fun lowerCallExpression(
        expr: Expression,
        kind: Expression.Kind.Call
    ): InstructionValue {
        if (ctx.checker.isGenericCallSite(kind)) {
            return generateGenericCall(expr, kind)
        }
        return builder.buildCall(
            lowerExpression(kind.callee),
            kind.args.map { lowerExpression(it.expression) }
        )
    }

    private fun generateGenericCall(expr: Expression, kind: Expression.Kind.Call): InstructionValue {
        val def = getFunctionDef(kind.callee)
        val specializedFunctionType = ctx.checker
            .getGenericSpecializedFunctionType(def, expr.location, kind.args)
        val originalFunctionName =
            ctx.resolver.getBinding(def.name.identifier).qualifiedName
        val specializedFunctionName = originalFunctionName.append(ctx.makeName(expr.location.toString()))
        val fn = llvmModule.addFunction(
            mangleQualifiedName(specializedFunctionName),
            lowerType(specializedFunctionType) as dev.supergrecko.kllvm.core.types.FunctionType
        )
        val stack = specializationStacks
            .computeIfAbsent(originalFunctionName) { mutableListOf() }
        stack.add(fn)
        generateSpecializedFunctionBody(fn, specializedFunctionType, def)
        stack.removeLast()
        return builder.buildCall(
            fn,
            kind.args.map { lowerExpression(it.expression) }
        )
    }

    private fun generateSpecializedFunctionBody(
        fn: FunctionValue,
        specializedFunctionType: Type.Function,
        def: Declaration.Kind.FunctionDef
    ) {
        val basicBlock = fn.appendBasicBlock("entry")
        generateInBlock(basicBlock) {
            def.body.members.forEach {
                lowerBlockMember(it)
            }
        }

    }

    private fun getFunctionDef(callee: Expression): Declaration.Kind.FunctionDef {
        return when (callee.kind) {
            is Expression.Kind.Var -> resolveGlobalFunctionDefFromVariable(callee.kind.name)
            is Expression.Kind.Property -> resolveGlobalFunctionDefForModule(callee.kind.lhs, callee.kind.property)
            else -> TODO("${callee.location}: Can't lower generic call for non global functions right now")
        }
    }

    private fun resolveGlobalFunctionDefFromVariable(name: Identifier): Declaration.Kind.FunctionDef {
        return when (val binding = ctx.resolver.getBinding(name)) {
            is ValueBinding.GlobalFunction -> binding.kind
            else -> TODO("${name.location}: Not a function definition")
        }
    }

    private fun resolveGlobalFunctionDefForModule(lhs: Expression, property: Identifier): Declaration.Kind.FunctionDef {
        TODO("Generic functions not implemented across modules")
    }


    private fun lowerBinder(binder: Binder): String {
        return if (binder.identifier.name.text == "main") {
            "hades_main"
        } else {
            mangleQualifiedName(ctx.resolver.getBinding(binder.identifier).qualifiedName)
        }
    }

    private fun linkWithRuntime() {
        log.info("Linking using gcc")
        val commandParts = listOf(
            "gcc",
            "-no-pie",
            "-o", ctx.options.output.toString(),
            ctx.options.runtime.toString(),
            objectFilePath
        )
        log.info(commandParts.joinToString(" "))
        val builder = ProcessBuilder(commandParts).inheritIO()
        log.debug(builder.command().joinToString(","))
        val process = builder.start()
        val exitCode = process.waitFor()
        assert(exitCode == 0) {
            "gcc exited with code $exitCode"
        }
    }

    private val objectFilePath get() = ctx.options.output.toString() + ".object"

    private fun writeModuleToFile() {
        log.info("Writing object file")
        LLVM.LLVMInitializeAllTargetInfos()
        LLVM.LLVMInitializeAllTargets()
        LLVM.LLVMInitializeAllTargetMCs()
        LLVM.LLVMInitializeAllAsmParsers()
        LLVM.LLVMInitializeAllAsmPrinters()
        val targetTriple = LLVM.LLVMGetDefaultTargetTriple()
        val cpu = BytePointer("generic")
        val features = BytePointer("")
        val target = LLVM.LLVMGetFirstTarget()
        val targetMachine: LLVMTargetMachineRef = LLVM.LLVMCreateTargetMachine(
            target,
            targetTriple,
            cpu,
            features,
            LLVM.LLVMCodeGenLevelLess,
            LLVM.LLVMRelocDefault,
            LLVM.LLVMCodeModelDefault
        )

        val pass = LLVM.LLVMCreatePassManager()
        LLVM.LLVMTargetMachineEmitToFile(
            targetMachine,
            llvmModule.getUnderlyingReference(),
            BytePointer(objectFilePath),
            LLVM.LLVMObjectFile,
            BytePointer("Message")
        )
        LLVM.LLVMRunPassManager(pass, llvmModule.getUnderlyingReference())

        LLVM.LLVMDisposePassManager(pass)
        LLVM.LLVMDisposeTargetMachine(targetMachine)
    }

    override fun close() {
        llvmCtx.dispose()
    }
}
