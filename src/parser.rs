use std::array;
use std::{path::PathBuf, rc::Rc};

use crate::ast::{
    Block, Expr, ExprKind, Fn, ForeignItem, ForeignItemKind, ForeignMod, Ident, Item, ItemKind,
    Lit, LitKind, NodeId, Param, SourceFile, Stmt, StmtKind, Ty, TyKind, Var, Visibility,
};
use crate::lexer::{Lexer, Token, TokenKind};

pub struct Parser<'text> {
    path: Rc<PathBuf>,
    tokens: TokenBuffer<'text>,
}

use libsyntax::{HasSpan, Meta, Span};
use t::*;
use TokenKind as t;
impl<'text> Parser<'text> {
    pub fn new(text: &'text str, path: PathBuf) -> Self {
        let lexer = Lexer::new(text, path.clone());
        let tokens = TokenBuffer::new(lexer);
        Parser {
            path: Rc::new(path),
            tokens,
        }
    }

    pub fn parse_source_file(mut self) -> SourceFile {
        let mut items = vec![];
        while self.current_kind() != TokenKind::EOF {
            items.push(self.parse_item());
        }
        SourceFile {
            path: PathBuf::clone(&self.path),
            items,
        }
    }

    fn parse_item(&mut self) -> Item {
        use t::*;
        let (vis, vis_token) = self.parse_visibility();
        match self.current_kind() {
            FN => {
                let (func, name) = self.parse_fn();
                let start = vis_token
                    .map(|it| *it.span())
                    .unwrap_or_else(|| *func.span());
                Item {
                    meta: Meta {
                        span: Span::between(&start, &func),
                    },
                    name,
                    vis,
                    kind: ItemKind::Fn(Box::new(func)),
                }
            }
            EXTERN => {
                let (start, foreign_mod, rbrace) = self.parse_foreign_mod();
                Item {
                    meta: Meta {
                        span: Span::between(&start, &rbrace),
                    },
                    name: "extern".to_string(),
                    vis,
                    kind: ItemKind::ForeignMod(foreign_mod),
                }
            }
            _ => todo!(
                "Unexpected token while parsing item: {:?}",
                self.current_kind()
            ),
        }
    }

    fn parse_foreign_mod(&mut self) -> (Token, ForeignMod, Token) {
        let start = self.expect(EXTERN, "Parsing foreign mod");
        self.expect(LBRACE, "Parsing foreign mod");
        let mut items = vec![];
        while !self.at(RBRACE) && !self.at(EOF) {
            items.push(self.parse_foreign_item());
        }
        let rbrace = self.expect(RBRACE, "Unexpected eof when parsing foreign mod");
        (start, ForeignMod { items }, rbrace)
    }

    fn parse_foreign_item(&mut self) -> ForeignItem {
        let (visibility, vis_token) = self.parse_visibility();
        let (f, name) = self.parse_fn();
        let start = vis_token.map(|it| *it.span()).unwrap_or_else(|| *f.span());
        ForeignItem {
            meta: Meta {
                span: Span::between(&start, &f),
            },
            name,
            vis: visibility,
            kind: ForeignItemKind::Fn(f),
        }
    }

    fn at(&self, kind: TokenKind) -> bool {
        self.current_kind() == kind
    }

    fn parse_visibility(&mut self) -> (Visibility, Option<Token>) {
        match self.current_kind() {
            t::PUB => {
                let token = self.advance();
                (Visibility::Public, Some(token))
            }
            _ => (Visibility::Inherited, None),
        }
    }

    fn parse_fn(&mut self) -> (Fn, Ident) {
        let start = self.expect(TokenKind::FN, "Trying to parse function");
        let name = self.expect(TokenKind::IDENT, "fn [name]").text;
        let (params, rparen) = self.parse_params();
        let return_ty = if self.current_kind() == TokenKind::ARROW {
            self.advance();
            Some(self.parse_ty())
        } else {
            None
        };
        let body = if self.at(LBRACE) {
            Some(self.parse_block_expr())
        } else {
            self.expect(SEMI, "Expected a semicolon after a function without a body");
            None
        };
        let end = body
            .as_ref()
            .map(|it| *it.span())
            .or(return_ty.as_ref().map(|it| *it.span()))
            .unwrap_or(*rparen.span());
        (
            Fn {
                meta: Meta {
                    span: Span::between(&start, &end),
                },
                body: body.map(|it| Box::new(it)),
                params,
                return_ty,
            },
            name,
        )
    }

    /// Returns the closing parenthesis token along with the parameters
    fn parse_params(&mut self) -> (Vec<Param>, Token) {
        self.expect(LPAREN, "Expected parameter list start");

        let mut params = vec![];

        while !self.at(RPAREN) && !self.eof() {
            let name = self.expect(IDENT, "Expected parameter name");
            self.expect(COLON, "Expected parameter type separator");
            let ty = self.parse_ty();
            params.push(Param {
                meta: Meta {
                    span: Span::between(&name, &ty),
                },
                name: name.text,
                ty,
            });
        }

        let rparen = self.expect(TokenKind::RPAREN, "Expected parameter list end");

        (params, rparen)
    }

    fn parse_block(&mut self) -> Block {
        let start = self.expect(TokenKind::LBRACE, "Trying to parse block");
        let mut stmts = vec![];
        while self.current_kind() != TokenKind::RBRACE && !self.eof() {
            stmts.push(self.parse_stmt());
        }

        let end = self.expect(
            TokenKind::RBRACE,
            "Unexpected end of file while looking for block terminator",
        );
        Block {
            meta: Meta {
                span: Span::between(&start, &end),
            },
            stmts,
        }
    }

    fn parse_block_expr(&mut self) -> Expr {
        let block = self.parse_block();
        Expr {
            meta: Meta {
                span: *block.span(),
            },
            kind: ExprKind::Block(block),
        }
    }

    fn parse_stmt(&mut self) -> Stmt {
        if self.current_kind() == TokenKind::SEMI {
            let tok = self.advance();
            return Stmt {
                meta: Meta { span: *tok.span() },
                kind: StmtKind::Semi,
            };
        }
        let expr = self.parse_expr();
        Stmt {
            meta: Meta { span: *expr.span() },
            kind: StmtKind::Expr(Box::new(expr)),
        }
    }

    fn parse_expr(&mut self) -> Expr {
        let head = match self.current_kind() {
            TokenKind::IDENT => {
                let token = self.advance();
                Expr {
                    meta: Meta {
                        span: *token.span(),
                    },
                    kind: ExprKind::Var(Var { name: token.text }),
                }
            }
            TokenKind::LPAREN => {
                let start = self.advance();
                let end = self.expect(TokenKind::RPAREN, "Trying to parse unit expression");
                Expr {
                    meta: Meta {
                        span: Span::between(&start, &end),
                    },
                    kind: ExprKind::Unit,
                }
            }
            TokenKind::UNSAFE => {
                let start = self.advance();
                let block = self.parse_block();
                Expr {
                    meta: Meta {
                        span: Span::between(&start, &block),
                    },
                    kind: ExprKind::Block(block),
                }
            }
            INT => {
                let token = self.advance();
                Expr {
                    meta: Meta {
                        span: *token.span(),
                    },
                    kind: ExprKind::Lit(Lit {
                        kind: LitKind::Integer,
                        text: token.text,
                    }),
                }
            }
            k => todo!("Unexpected token while parsing expression: {:?}", k),
        };
        self.parse_expr_tail(head)
    }

    fn parse_expr_tail(&mut self, head: Expr) -> Expr {
        match self.current_kind() {
            LPAREN => {
                self.advance();
                let mut exprs = vec![];
                while self.current_kind() != RPAREN && self.current_kind() != EOF {
                    exprs.push(self.parse_expr());
                }
                let end = self.expect(
                    RPAREN,
                    "Unexpected EOF while trying to parse call arguments",
                );
                Expr {
                    meta: Meta {
                        span: Span::between(&head, &end),
                    },
                    kind: ExprKind::Call(Box::new(head), exprs),
                }
            }
            _ => head,
        }
    }

    fn parse_ty(&mut self) -> Ty {
        match self.current_kind() {
            LPAREN => {
                let start = self.expect(TokenKind::LPAREN, "Trying to parse unit type");

                let end = self.expect(TokenKind::RPAREN, "Trying to parse end of unit type");
                Ty {
                    meta: Meta {
                        span: Span::between(&start, &end),
                    },
                    kind: TyKind::Tup(vec![]),
                }
            }
            IDENT => {
                let token = self.advance();
                Ty {
                    meta: Meta {
                        span: *token.span(),
                    },
                    kind: TyKind::Var(token.text),
                }
            }
            _ => todo!("Parsing type"),
        }
    }

    fn eof(&self) -> bool {
        self.current_kind() == TokenKind::EOF
    }

    fn node_id(&mut self) -> NodeId {
        NodeId(0)
    }

    fn expect(&mut self, kind: TokenKind, message: &str) -> Token {
        let token = self.advance();
        if token.kind != kind {
            panic!(
                "Parse error: {}\nExpected: {:?}; Found: '{}' ({:?});",
                message, kind, token.text, token.kind
            )
        }
        token
    }

    fn current_kind(&self) -> TokenKind {
        self.tokens.current_kind()
    }

    fn advance(&mut self) -> Token {
        self.tokens.advance()
    }
}

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn test_parse_empty_function() {
        let path = PathBuf::from("test.hds");
        let parser = Parser::new("fn main() -> () {}", path);
        parser.parse_source_file();
    }
}

const MAX_LOOKAHEAD: usize = 4;
/// A ring buffer of N tokens; Allows peeking and consuming tokens.
#[derive(Debug)]
struct TokenBuffer<'text> {
    start: usize,
    tokens: [Token; MAX_LOOKAHEAD],
    lexer: Lexer<'text>,
}
impl<'text> TokenBuffer<'text> {
    pub fn new(mut lexer: Lexer<'text>) -> Self {
        let tokens = array::from_fn::<Token, MAX_LOOKAHEAD, _>(|_| lexer.next_token());
        TokenBuffer {
            lexer,
            tokens,
            start: 0,
        }
    }

    pub fn peek(&self, offset: usize) -> &Token {
        assert!(offset < MAX_LOOKAHEAD);
        &self.tokens[(self.start + offset) % MAX_LOOKAHEAD]
    }

    pub fn current(&self) -> &Token {
        self.peek(0)
    }

    pub fn current_kind(&self) -> TokenKind {
        self.current().kind
    }

    pub fn advance(&mut self) -> Token {
        let t = self.lexer.next_token();
        // before
        // 4 1 2 3
        //   ^
        // after
        // 4 t 2 3
        //     ^
        let old = std::mem::replace(&mut self.tokens[self.start], t);
        self.start = (self.start + 1) % MAX_LOOKAHEAD;
        old
    }
}

#[cfg(test)]
mod token_buffer_test {
    use std::path::PathBuf;

    use crate::lexer::Lexer;

    use super::*;

    #[test]
    fn test_token_buffer() {
        let lexer = Lexer::new("fn main() {}", PathBuf::from("test"));
        let mut buffer = TokenBuffer::new(lexer);

        assert_eq!(buffer.peek(0).kind, TokenKind::FN);
        assert_eq!(buffer.peek(1).kind, TokenKind::IDENT);
        assert_eq!(buffer.peek(2).kind, TokenKind::LPAREN);
        assert_eq!(buffer.peek(3).kind, TokenKind::RPAREN);

        assert_eq!(buffer.advance().kind, TokenKind::FN);
        assert_eq!(buffer.peek(0).kind, TokenKind::IDENT);
    }

    #[test]
    fn test_overflow() {
        let lexer = Lexer::new("fn main() {}", PathBuf::from("test"));
        let mut buffer = TokenBuffer::new(lexer);

        use TokenKind::*;
        assert_eq!(buffer.advance().kind, FN);
        assert_eq!(buffer.advance().kind, IDENT);
        assert_eq!(buffer.advance().kind, LPAREN);
        assert_eq!(buffer.advance().kind, RPAREN);
        assert_eq!(buffer.advance().kind, LBRACE);
        assert_eq!(buffer.advance().kind, RBRACE);
    }
}
