use super::{Block, Expr, ExprKind, Fn, Item, Stmt};

pub trait Visitor: Sized {
    fn visit_item(&mut self, item: &Item) {
        walk_item(self, item)
    }

    fn visit_block(&mut self, block: &Block) {
        walk_block(self, block)
    }

    fn visit_stmt(&mut self, stmt: &Stmt) {
        walk_stmt(self, stmt)
    }

    fn visit_expr(&mut self, expr: &Expr) {
        walk_expr(self, expr)
    }

    fn visit_fn(&mut self, f: &super::Fn) {
        walk_fn(self, f)
    }
}

#[macro_export]
macro_rules! walk_list {
    ($visitor: expr, $method: ident, $list: expr $(, $($extra_args: expr),* )?) => {
        {
            #[allow(for_loops_over_fallibles)]
            for elem in $list {
                $visitor.$method(elem $(, $($extra_args,)* )?)
            }
        }
    }
}

pub fn walk_expr(visitor: &mut impl Visitor, expr: &Expr) {
    use ExprKind as E;
    match &expr.kind {
        E::Block(block) => visitor.visit_block(block),
        E::Unit => (),
        E::Lit(_) => (),
        E::Var(_) => (),
        E::Call(callee, args) => {
            visitor.visit_expr(callee);
            walk_list!(visitor, visit_expr, args);
        }
    }
}

pub fn walk_block(visitor: &mut impl Visitor, block: &Block) {
    walk_list!(visitor, visit_stmt, &block.stmts);
}

pub fn walk_item(visitor: &mut impl Visitor, item: &Item) {
    use super::ItemKind as I;
    match &item.kind {
        I::Fn(f) => visitor.visit_fn(&f),
        I::ForeignMod(_) => todo!(),
    }
}

pub fn walk_stmt(visitor: &mut impl Visitor, stmt: &Stmt) {
    use super::StmtKind as S;
    match &stmt.kind {
        S::Expr(expr) => todo!(),
        S::Semi => {}
    }
}

pub fn walk_fn(visitor: &mut impl Visitor, f: &Fn) {
    if let Some(body) = &f.body {
        visitor.visit_expr(body);
    }
}
