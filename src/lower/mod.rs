use crate::ast::{self, visit::Visitor, SourceFile};

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
}
impl Visitor for LowerInterfaceCtx {
    fn visit_fn(&mut self, _f: &ast::Fn) {
        todo!()
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
