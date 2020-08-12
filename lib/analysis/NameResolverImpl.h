//
// Created by dhruv on 08/08/20.
//

#ifndef HADES_NAMERESOLVERIMPL_H
#define HADES_NAMERESOLVERIMPL_H

#include "ScopeTree.h"
#include "hades/analysis/NameResolutionResult.h"
#include "hades/ast/TypeAnnotation.h"
#include "hades/base.h"
#include "hades/base/BumpPtrAllocator.h"
#include "hades/context/Context.h"
#include "hades/hir/Type.h"

namespace hades {

class NameResolverImpl {
  core::Context *m_ctx;
  BumpPtrAllocator m_allocator{};
  enum class ResolutionContext {
    TYPE,
    VALUE,
  };

#define BUILTIN_INT(name, width, is_signed)                                    \
  const Type *builtin_##name =                            \
      m_allocator.allocate<IntType>(width, is_signed);       \
  InternedString builtin_name_##name = m_ctx->interner().intern_string(#name);

  BUILTIN_INT(u32, 32, false)
  BUILTIN_INT(i32, 32, true)
  BUILTIN_INT(u64, 64, false)
  BUILTIN_INT(i64, 64, true)

#undef BUILTIN_INT
  const Type *builtin_void =
      m_allocator.allocate<VoidType>();
  InternedString builtin_name_void = m_ctx->interner().intern_string("Void");

public:
  NameResolverImpl(core::Context *) noexcept;
  ~NameResolverImpl() noexcept = default;
  HADES_DEFAULT_MOVE(NameResolverImpl)

  auto resolve_type_var(const type_annotation::Var &) -> NameResolutionResult;

  auto resolve_expr_var(const VarExpression &) -> NameResolutionResult;

private:
  auto ctx() -> core::Context &;

  auto allocator() -> BumpPtrAllocator &;

  auto resolve_in_scope(  //
      const Identifier &, //
      const ScopeTree &,  //
      ResolutionContext   //
  ) const -> NameResolutionResult;

  auto find_in_source_file( //
      const Identifier &,   //
      const SourceFile &,   //
      ResolutionContext     //
  ) const -> NameResolutionResult;

  auto find_in_block(const Identifier &, //
                     const Block &,      //
                     ResolutionContext   //
  ) const -> NameResolutionResult;

  auto resolve_name(const Identifier &, ResolutionContext)
      -> NameResolutionResult;
};

} // namespace hades

#endif // HADES_NAMERESOLVERIMPL_H
