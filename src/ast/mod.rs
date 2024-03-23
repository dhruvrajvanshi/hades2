pub mod visit;
use std::path::PathBuf;

use libsyntax::Meta;
use libsyntax_derive::HasMeta;
use serde::Serialize;

pub type Ident = String;

#[derive(Debug, Serialize)]
pub struct SourceFile {
    pub path: PathBuf,
    pub items: Vec<Item>,
}
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug, Serialize)]
pub struct NodeId(pub usize);

#[derive(Debug, Serialize, HasMeta)]
pub struct Item {
    pub meta: Meta,
    pub kind: ItemKind,
    pub vis: Visibility,
    pub name: Ident,
}

#[derive(Debug, Serialize)]
pub enum Visibility {
    Public,
    Inherited,
}

#[derive(Debug, Serialize, HasMeta)]
pub struct Fn {
    pub meta: Meta,
    pub params: Vec<Param>,
    pub body: Option<Box<Expr>>,
    pub return_ty: Option<Ty>,
}

#[derive(Debug, Serialize, HasMeta)]
pub struct Param {
    pub meta: Meta,
    pub name: Ident,
    pub ty: Ty,
}

#[derive(Debug, Serialize, HasMeta)]
pub struct Expr {
    pub meta: Meta,
    pub kind: ExprKind,
}
#[derive(Debug, Serialize)]
pub enum ExprKind {
    Block(Block),
    Call(Box<Expr>, Vec<Expr>),
    Lit(Lit),
    Var(Var),
    Unit,
}

#[derive(Debug, Serialize)]
pub struct Var {
    pub name: Ident,
}

#[derive(Debug, Serialize)]
pub struct Lit {
    pub kind: LitKind,
    pub text: String,
}

#[derive(Debug, Serialize)]
pub enum LitKind {
    Integer,
}

#[derive(Debug, Serialize, HasMeta)]
pub struct Block {
    pub meta: Meta,
    pub stmts: Vec<Stmt>,
}

#[derive(Debug, Serialize)]
pub enum ItemKind {
    Fn(Box<Fn>),
    ForeignMod(ForeignMod),
}

#[derive(Debug, Serialize)]
pub struct ForeignMod {
    pub items: Vec<ForeignItem>,
}

#[derive(Debug, Serialize, HasMeta)]
pub struct ForeignItem {
    pub meta: Meta,
    pub name: Ident,
    pub vis: Visibility,
    pub kind: ForeignItemKind,
}

#[derive(Debug, Serialize)]
pub enum ForeignItemKind {
    Fn(Fn),
}

#[derive(Debug, Serialize, HasMeta)]
pub struct Stmt {
    pub meta: Meta,
    pub kind: StmtKind,
}

#[derive(Debug, Serialize)]
pub enum StmtKind {
    Item(Item),
    Semi,
    Expr(Box<Expr>),
}

#[derive(Debug, Serialize, HasMeta)]
pub struct Ty {
    pub meta: Meta,
    pub kind: TyKind,
}

#[derive(Debug, Serialize)]
pub enum TyKind {
    Tup(Vec<Ty>),
    Var(Ident),
}
