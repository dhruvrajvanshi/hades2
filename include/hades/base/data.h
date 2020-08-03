#ifndef HADES_DATA_H
#define HADES_DATA_H

#include "memory"
#include "string"
#include "string_view"
#include "vector"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/PointerUnion.h"
#include "llvm/ADT/ArrayRef.h"
#include "llvm/Support/Allocator.h"
#include "hades/base/Result.h"

namespace hades {
using String = std::string;
using StringView = std::string_view;
using StringRef = llvm::StringRef;
using Twine = llvm::Twine;

template <typename T, typename Alloc = std::allocator<T>>
using Vec = std::vector<T, Alloc>;

template <typename T, unsigned Size>
using SmallVec = llvm::SmallVector<T, Size>;

template <typename ...Ts>
using PointerUnion = llvm::PointerUnion<Ts...>;

template <typename T>
using Optional = llvm::Optional<T>;

namespace optional {
    template <typename T>
    auto none() -> Optional<T> {
        return Optional<T>();
    }

    template <typename T>
    auto some(T value) -> Optional<T> {
        return Optional<T>(value);
    }
} // namespace optional

template <typename T>
using ArrayRef = llvm::ArrayRef<T>;

template <typename... Elements>
using Tuple = std::tuple<Elements...>;

using u32 = uint32_t;
using u64 = uint64_t;
using i32 = uint32_t;
using i64 = uint64_t;

} // namespace hades

#endif
