use crate::ast::{self, visit::Visitor, SourceFile};

pub struct ResolveResult {}
pub fn resolve(source_file: &SourceFile) -> ResolveResult {
    let mut resolve = Resolve {};
    resolve.resolve_source_file(source_file)
}

struct Resolve;

impl Resolve {
    fn resolve_source_file(&mut self, source_file: &SourceFile) -> ResolveResult {
        for item in source_file.items.iter() {
            self.visit_item(&item);
        }
        ResolveResult {}
    }
}

impl Visitor for Resolve {
    fn visit_item(&mut self, item: &ast::Item) {}
}
