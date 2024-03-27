use crate::ast::{self, visit::Visitor, ForeignItem, SourceFile};

pub fn lower_source_file(source_file: SourceFile) {
    let buffer = String::new();
    let lowerer = LowerInterfaceCtx::new(buffer);
    let buffer = lowerer.lower(&source_file);
    let lowerer = LowerImplCtx::new(buffer);
    let out = lowerer.lower(source_file);
    todo!("Output: {}", out)
}

struct LowerInterfaceCtx {
    buffer: String,
}
impl LowerInterfaceCtx {
    fn new(buffer: String) -> Self {
        LowerInterfaceCtx { buffer }
    }

    fn lower(mut self, source_file: &SourceFile) -> String {
        for item in source_file.items.iter() {
            self.visit_item(&item);
        }
        self.buffer
    }
    fn lower_foreign_fn(&mut self, item: &ForeignItem, f: &ast::Fn) {
        let return_ty = f
            .return_ty
            .as_ref()
            .map(lower_ty)
            .unwrap_or("void".to_string());
        assert!(f.body.is_none(), "Foreign functions cannot have bodies");
        let name = item.name.as_str();
        self.buffer.push_str(&return_ty);
        self.buffer.push_str(" ");
        self.buffer.push_str(name);
        self.buffer.push_str("();");
    }
}
impl Visitor for LowerInterfaceCtx {
    fn visit_foreign_item(&mut self, f: &ast::ForeignItem) {
        use ast::ForeignItemKind::*;
        match &f.kind {
            Fn(fun) => self.lower_foreign_fn(f, fun),
        }
    }
}

struct LowerImplCtx {
    buffer: String,
}
impl LowerImplCtx {
    fn new(buffer: String) -> Self {
        LowerImplCtx { buffer }
    }

    fn lower(mut self, source_file: SourceFile) -> String {
        for item in source_file.items {
            self.visit_item(&item);
        }
        self.buffer
    }
}
impl Visitor for LowerImplCtx {}

fn lower_ty(ty: &ast::Ty) -> String {
    use ast::TyKind::*;
    match &ty.kind {
        Tup(items) if items.is_empty() => String::from("void"),
        Var(ident) => todo!("Cant lower type {}", ident),
        _ => todo!(),
    }
}
