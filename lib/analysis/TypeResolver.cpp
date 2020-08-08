//
// Created by dhruv on 08/08/20.
//
#include "hades/analysis/TypeResolver.h"
#include "TypeResolverImpl.h"

namespace hades {

TypeResolver::TypeResolver() noexcept
    : m_impl{new TypeResolverImpl()}
{}
auto TypeResolver::self() -> TypeResolverImpl & { return *m_impl; }


auto TypeResolver::resolve_type_var(const type::Var *) -> TypeResolutionResult {
  unimplemented();
}

TypeResolver::~TypeResolver() noexcept = default;

} // namespace hades
