//
// Created by dhruv on 03/08/20.
//

#ifndef HADES_DECLARATION_H
#define HADES_DECLARATION_H

#include "hades/ast/FunctionSignature.h"
#include "hades/ast/Identifier.h"
#include "hades/core/location.h"
#include "hades/ast/Block.h"

namespace hades {
class Declaration {
public:
  enum class Kind;

protected:
  SourceLocation m_location;
  Kind m_kind;

  Declaration(SourceLocation location, Kind kind) noexcept;

public:
  auto location() const noexcept -> const SourceLocation &;

  auto kind() const noexcept -> Kind;

  enum class Kind {
    ERROR,
    EXTERN_DEF,
    STRUCT_DEF,
  };
};

class Error : Declaration {};

class ExternDef : public Declaration {
  const FunctionSignature *m_signature;
  Identifier m_extern_name;

public:
  ExternDef(SourceLocation location, const FunctionSignature *signature,
            Identifier extern_name) noexcept;

  auto signature() -> const FunctionSignature &;

  auto extern_name() -> const Identifier &;
};

class FunctionDef : public Declaration {
  const FunctionSignature *m_signature;
  const Block* m_body;
};

class StructMember;
class StructDef : public Declaration {
public:
  using Members = SmallVec<const StructMember *, 8>;

private:
  Identifier m_name;
  Members m_members;

public:
  StructDef(SourceLocation location, Identifier name,
            SmallVec<const StructMember *, 8> members) noexcept;

  auto identifier() const noexcept -> const Identifier &;

  auto members() const noexcept -> ArrayRef<const StructMember *>;
};
class StructMember {
  SourceLocation m_location;

public:
  StructMember(SourceLocation location) noexcept;
  auto location() const -> SourceLocation;
};
class StructField : public StructMember {
  Identifier m_name;
  Optional<const Type *> m_type;

public:
  StructField(SourceLocation location, Identifier name,
              Optional<const Type *> type) noexcept;
};

} // namespace hades

#endif // HADES_DECLARATION_H
