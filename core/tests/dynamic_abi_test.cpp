#include "reviewfault/reviewfault_c.h"

#include <dlfcn.h>

#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>
#include <type_traits>

namespace {

template <typename Function>
Function load(void* library, const char* name) {
  static_assert(std::is_pointer_v<Function>);
  void* symbol = dlsym(library, name);
  if (symbol == nullptr) {
    std::cerr << "missing dynamic symbol " << name << ": " << dlerror() << '\n';
    std::exit(EXIT_FAILURE);
  }
  Function function{};
  static_assert(sizeof(function) == sizeof(symbol));
  std::memcpy(&function, &symbol, sizeof(function));
  return function;
}

}  // namespace

int main() {
  void* library = dlopen("./build/libreviewfault_core.so", RTLD_NOW | RTLD_LOCAL);
  if (library == nullptr) {
    std::cerr << "cannot load shared core: " << dlerror() << '\n';
    return EXIT_FAILURE;
  }

  const auto abi_version = load<uint32_t (*)()>(library, "rf_scheduler_abi_version");
  const auto config_size = load<size_t (*)()>(library, "rf_scheduler_config_size");
  const auto card_size = load<size_t (*)()>(library, "rf_card_size");
  const auto result_size = load<size_t (*)()>(library, "rf_review_result_size");
  const auto default_config =
      load<rf_scheduler_config (*)()>(library, "rf_default_scheduler_config");
  const auto new_card = load<rf_card (*)()>(library, "rf_new_card");
  const auto review = load<int32_t (*)(const rf_scheduler_config*, const rf_card*,
                                       int32_t, int64_t, rf_review_result*, char*, size_t)>(
      library, "rf_review");

  if (abi_version() != RF_SCHEDULER_ABI_VERSION ||
      config_size() != sizeof(rf_scheduler_config) || card_size() != sizeof(rf_card) ||
      result_size() != sizeof(rf_review_result)) {
    std::cerr << "dynamic ABI layout mismatch\n";
    dlclose(library);
    return EXIT_FAILURE;
  }

  auto config = default_config();
  auto card = new_card();
  rf_review_result result{};
  result.struct_size = sizeof(result);
  char error[128]{};
  const auto status = review(&config, &card, RF_RATING_GOOD, 1'800'000'000,
                             &result, error, sizeof(error));
  if (status != 0 || result.card.state != RF_CARD_REVIEW ||
      result.card.due_at != 1'800'172'800) {
    std::cerr << "dynamic ABI review failed: " << error << '\n';
    dlclose(library);
    return EXIT_FAILURE;
  }
  dlclose(library);
  std::cout << "Dynamic C ABI tests passed\n";
  return EXIT_SUCCESS;
}

