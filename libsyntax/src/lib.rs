use serde::Serialize;

/// Represents a range of offsets in a text file
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub struct Span {
    pub start: usize,
    /// Exclusive; e.g. in the string 'foo bar' the span of 'foo' is { start: 0, end: 3 }
    pub end: usize,
}

pub trait HasSpan {
    fn span(&self) -> &Span;

    fn start(&self) -> usize {
        self.span().start
    }
    fn end(&self) -> usize {
        self.span().end
    }
}
impl Span {
    pub fn between(start: &impl HasSpan, end: &impl HasSpan) -> Self {
        Span {
            start: start.span().start,
            end: end.span().end,
        }
    }
}

#[derive(Debug, Clone, Copy, Serialize)]
pub struct Meta {
    pub span: Span,
}
pub trait HasMeta {
    fn meta(&self) -> &Meta;
}
impl<T: HasMeta> HasSpan for T {
    fn span(&self) -> &Span {
        &self.meta().span
    }
}
