use core::panic;
use std::{collections::HashMap, path::PathBuf, rc::Rc, str::Chars};

use lazy_static::lazy_static;
use libsyntax::Span;
use libsyntax_derive::HasSpan;

#[derive(PartialEq, Eq, Debug, Clone, Copy)]
pub enum TokenKind {
    IDENT,
    INT,

    // Keyworkds
    FN,
    PUB,
    EXTERN,
    UNSAFE,

    // Punctuation
    LPAREN,
    RPAREN,
    LBRACE,
    RBRACE,
    SEMI,

    // Non punctuation Operators
    ARROW,
    COLON,
    COLONCOLON,

    EOF,
}
#[derive(Debug, HasSpan)]
pub struct Token {
    pub kind: TokenKind,
    pub span: Span,
    pub text: String,
}

lazy_static! {
    static ref TOKEN_KINDS: HashMap<&'static str, TokenKind> = {
        let mut m = HashMap::new();
        use TokenKind::*;
        let mut i = |k, v| m.insert(k, v);
        i("fn", FN);
        i("pub", PUB);
        i("extern", EXTERN);
        i("unsafe", UNSAFE);
        m
    };
}

lazy_static! {
    static ref SINGLE_CHAR_TOKENS: HashMap<char, TokenKind> = {
        let mut m = HashMap::new();
        let mut i = |k, v| m.insert(k, v);
        use TokenKind::*;
        i('(', LPAREN);
        i(')', RPAREN);
        i('{', LBRACE);
        i('}', RBRACE);
        i(';', SEMI);
        m
    };
}

#[derive(Debug)]
pub(crate) struct Lexer<'chars> {
    current_char: char,
    text: Chars<'chars>,
    lexeme: String,
    _path: Rc<PathBuf>,
    position: usize,
    line: usize,
    column: usize,
}
impl<'chars> Lexer<'chars> {
    pub fn new(text: &'chars str, path: PathBuf) -> Self {
        let mut chars = text.chars();
        let current_char = chars.next().unwrap_or('\0');
        Lexer {
            current_char,
            text: chars,
            _path: Rc::new(path),
            lexeme: String::new(),
            position: 0,
            line: 1,
            column: 1,
        }
    }

    pub fn next_token(&mut self) -> Token {
        self.skip_whitespace();
        self.start_token();
        match self.current_char {
            '\0' => self.make_token(TokenKind::EOF),
            '-' => {
                self.advance();
                self.expect('>', "Expected `>` after `-`");
                self.make_token(TokenKind::ARROW)
            }
            ':' => {
                self.advance();
                if self.current_char == ':' {
                    self.advance();
                    self.make_token(TokenKind::COLONCOLON)
                } else {
                    self.make_token(TokenKind::COLON)
                }
            }
            c if c.is_digit(10) => self.integer(),
            c if is_ident_starter(c) => self.ident_or_keyword(),
            c if SINGLE_CHAR_TOKENS.contains_key(&c) => {
                self.advance();
                self.make_token(
                    *SINGLE_CHAR_TOKENS
                        .get(&c)
                        .expect("Should not panic because of `contains_key` check above"),
                )
            }
            c => todo!(
                "Unexpected character at line: {}:{}: '{}'",
                self.line,
                self.column,
                c
            ),
        }
    }

    fn integer(&mut self) -> Token {
        assert!(self.current_char.is_digit(10));
        while self.current_char.is_digit(10) {
            self.advance();
        }
        self.make_token(TokenKind::INT)
    }

    fn ident_or_keyword(&mut self) -> Token {
        assert!(is_ident_starter(self.current_char));
        while is_ident_char(self.current_char) {
            self.advance();
        }
        self.make_token(
            *TOKEN_KINDS
                .get(self.lexeme.as_str())
                .unwrap_or(&TokenKind::IDENT),
        )
    }

    fn start_token(&mut self) {
        self.lexeme = String::new();
    }

    fn make_token(&mut self, kind: TokenKind) -> Token {
        let text = std::mem::replace(&mut self.lexeme, String::new());
        Token {
            kind,
            span: Span {
                start: self.position - text.len(),
                end: self.position,
            },
            text,
        }
    }
    fn skip_whitespace(&mut self) {
        while self.current_char.is_whitespace() && !self.eof() {
            self.advance();
        }
    }

    fn eof(&self) -> bool {
        self.current_char == '\0'
    }
    fn advance(&mut self) -> char {
        if self.eof() {
            panic!("Tried to advance past EOF")
        }
        let current_char = self.current_char;
        self.current_char = self.text.next().unwrap_or('\0');
        self.lexeme.push(current_char);
        self.position += 1;
        if current_char == '\n' {
            self.line += 1;
            self.column = 1;
        } else {
            self.column += 1;
        }
        current_char
    }
    fn expect(&mut self, c: char, message: &str) -> char {
        if self.current_char != c {
            eprintln!("Current position: {}", self.position);
            panic!(
                "{}; Expected: '{}'; Found: '{}'",
                message, c, self.current_char
            );
        }
        self.advance()
    }
}

fn is_ident_starter(c: char) -> bool {
    c.is_alphabetic() || c == '_'
}
fn is_ident_char(c: char) -> bool {
    is_ident_starter(c) || c.is_numeric()
}

#[cfg(test)]
mod test {
    use libsyntax::HasSpan;

    use super::*;

    #[test]
    fn test_lexer() {
        let path = PathBuf::from("test.hds");
        let mut lexer = Lexer::new("", path);

        let token = lexer.next_token();

        assert_eq!(token.kind, TokenKind::EOF);
    }

    #[test]
    fn test_lexer_skips_whitespace() {
        let path = PathBuf::from("test.hds");
        let text = "  \t\n";
        let mut lexer = Lexer::new(text, path);

        let token = lexer.next_token();

        assert_eq!(token.kind, TokenKind::EOF);
        assert_eq!(token.start(), 4);
    }

    #[test]
    fn test_tokenizes_fn() {
        let path = PathBuf::from("test.hds");
        let text = "  fn main";
        let mut lexer = Lexer::new(text, path);
        let mut token = lexer.next_token();

        assert_eq!(token.text, "fn");
        assert_eq!(token.kind, TokenKind::FN);
        assert_eq!(token.start(), 2);

        token = lexer.next_token();

        assert_eq!(token.kind, TokenKind::IDENT);
        assert_eq!(token.text, "main");

        assert_eq!(lexer.next_token().kind, TokenKind::EOF);
    }

    #[test]
    fn tokenizes_parens() {
        let mut t = mk_tokenizer("(){};");
        use TokenKind as k;
        assert_eq!(t.next_token().kind, k::LPAREN);
        assert_eq!(t.next_token().kind, k::RPAREN);
        assert_eq!(t.next_token().kind, k::LBRACE);
        assert_eq!(t.next_token().kind, k::RBRACE);
    }

    #[test]
    fn tokenize_function_header_with_no_args() {
        let mut t = mk_tokenizer("fn main() {}");
        use TokenKind as k;
        assert_eq!(t.next_token().kind, k::FN);
        assert_eq!(t.next_token().kind, k::IDENT);
        assert_eq!(t.next_token().kind, k::LPAREN);
        assert_eq!(t.next_token().kind, k::RPAREN);
        assert_eq!(t.next_token().kind, k::LBRACE);
        assert_eq!(t.next_token().kind, k::RBRACE);
    }

    #[test]
    fn tokenizes_arrow() {
        let mut t = mk_tokenizer("->");
        assert_eq!(t.next_token().kind, TokenKind::ARROW);
    }

    fn mk_tokenizer(s: &str) -> Lexer<'_> {
        Lexer::new(s, PathBuf::from("test.hds"))
    }
}
