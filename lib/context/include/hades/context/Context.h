#ifndef HADES_CORE_CONTEXT_H
#define HADES_CORE_CONTEXT_H

#include "hades/analysis/NameResolver.h"
#include "hades/analysis/Typer.h"
#include "hades/ast/SourceFile.h"
#include "hades/base/BumpPtrAllocator.h"
#include "hades/base/data.h"
#include "hades/core/CommandLineFlags.h"
#include "hades/core/InternedString.h"
#include "hades/core/Interner.h"

namespace hades::core {
class ContextImpl;
class Context {
  UniquePtr<ContextImpl> m_self;

public:
  Context() = delete;
  ~Context() noexcept;
  Context(CommandLineFlags self);
  HADES_DELETE_COPY(Context)
  HADES_DELETE_MOVE(Context)

  auto run() -> int;
  auto allocator() -> BumpPtrAllocator &;

  auto name_resolver() -> NameResolver &;

  auto typer() -> Typer &;

  auto interner() -> Interner &;

  auto get_source_file(const fs::path&) -> const SourceFile&;

private:
  auto self_mut() -> ContextImpl &;
  auto self() -> const ContextImpl &;
};

} // namespace hades::core

#endif