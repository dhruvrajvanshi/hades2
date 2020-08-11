//
// Created by dhruv on 10/08/20.
//

#ifndef HADES_TYPE_H
#define HADES_TYPE_H
#include "hades/base.h"

namespace hades {

class Type {
public:
  enum class Kind;

private:
  Kind m_kind;

protected:
  Type(Kind kind) noexcept : m_kind{kind} {};

public:
  enum class Kind {
    INT,
    POINTER,
    FUNCTION,
  };
};

class FunctionType : public Type {
  ArrayRef<const Type *> m_param_types;
  const Type *m_return_type;

public:
  static constexpr Kind kind = Kind::FUNCTION;
  HADES_DELETE_MOVE(FunctionType);
  FunctionType(ArrayRef<const Type *> param_types,
               const Type *return_type) noexcept
      : Type(kind), m_param_types(param_types), m_return_type(return_type) {}

  auto param_types() const -> ArrayRef<const Type*> {
    return m_param_types;
  }

  auto return_type() const -> const Type* {
    return m_return_type;
  }
};

class PointerType : public Type {
  const Type *m_pointee;

public:
  static constexpr Kind kind = Kind::POINTER;
  PointerType(const Type *pointee) noexcept : Type(kind), m_pointee(pointee){};

  auto pointee() const -> const Type * { return m_pointee; }
};

class IntType : public Type {
  u8 m_size;
  bool m_is_signed;

public:
  static constexpr Kind kind = Kind::INT;
  IntType(u8 size, bool is_signed) noexcept
      : Type(kind), m_size(size), m_is_signed(is_signed){};

  auto size() const -> u8 { return m_size; }

  auto is_signed() const -> bool { return m_is_signed; }
};

} // namespace hades

#endif // HADES_TYPE_H
