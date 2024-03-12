#![feature(proc_macro_quote)]

use proc_macro::TokenStream;
use quote::quote;
use syn::{parse_macro_input, DeriveInput};

#[proc_macro_derive(HasSpan)]
pub fn derive_has_span(item: TokenStream) -> TokenStream {
    let DeriveInput { ident, .. } = parse_macro_input!(item as DeriveInput);

    quote! {
        #[automatically_derived]
        impl libsyntax::HasSpan for #ident {
            fn span(&self) -> &libsyntax::Span {
                &self.span
            }
        }
    }
    .into()
}

#[proc_macro_derive(HasMeta)]
pub fn derive_has_meta(item: TokenStream) -> TokenStream {
    let DeriveInput { ident, .. } = parse_macro_input!(item as DeriveInput);

    quote! {
        #[automatically_derived]
        impl libsyntax::HasMeta for #ident {
            fn meta(&self) -> &libsyntax::Meta {
                &self.meta
            }
        }
    }
    .into()
}
