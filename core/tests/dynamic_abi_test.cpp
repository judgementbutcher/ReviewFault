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
  const auto memory_v2 = load<int32_t (*)(const rf_memory_schedule_state_v2*,
                                           const rf_memory_review_input_v2*,
                                           rf_memory_review_result_v2*, char*, size_t)>(
      library, "review_memory_v2");
  const auto math_v2 = load<int32_t (*)(const rf_math_schedule_state_v2*,
                                         const rf_math_attempt_input_v2*,
                                         rf_math_review_result_v2*, char*, size_t)>(
      library, "review_math_v2");
  const auto memory_v3 = load<int32_t (*)(const rf_memory_schedule_state_v2*,
                                           const rf_memory_review_input_v3*,
                                           rf_memory_review_result_v3*, char*, size_t)>(
      library, "review_memory_v3");
  const auto canonical_v4 = load<int32_t (*)(const rf_review_action_v4*, size_t,
                                               size_t*, size_t, char*, size_t)>(
      library, "canonical_review_order_v4");
  const auto memory_task_v5 = load<int32_t (*)(const rf_memory_task_review_input_v5*,
                                                rf_memory_task_review_result_v5*, char*, size_t)>(
      library, "review_memory_task_v5");

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

  auto memory_state = load<rf_memory_schedule_state_v2 (*)()>(
      library, "rf_new_memory_state_v2")();
  rf_memory_review_input_v2 memory_input{sizeof(memory_input), RF_RATING_GOOD,
                                         RF_MEMORY_BALANCED, 1'800'000'000};
  rf_memory_review_result_v2 memory_result{};
  memory_result.struct_size = sizeof(memory_result);
  if (memory_v2(&memory_state, &memory_input, &memory_result, error, sizeof(error)) != 0 ||
      memory_result.state.state != RF_CARD_REVIEW) {
    std::cerr << "dynamic memory v2 ABI review failed: " << error << '\n';
    dlclose(library);
    return EXIT_FAILURE;
  }

  auto math_state = load<rf_math_schedule_state_v2 (*)()>(library,
                                                           "rf_new_math_state_v2")();
  rf_math_attempt_input_v2 math_input{sizeof(math_input), RF_MATH_FLUENT_CORRECT,
                                      RF_MATH_ERROR_NONE, 0, RF_MATH_BALANCED,
                                      1'800'000'000};
  rf_math_review_result_v2 math_result{};
  math_result.struct_size = sizeof(math_result);
  if (math_v2(&math_state, &math_input, &math_result, error, sizeof(error)) != 0 ||
      math_result.state.mastery_level != 1) {
    std::cerr << "dynamic math v2 ABI review failed: " << error << '\n';
    dlclose(library);
    return EXIT_FAILURE;
  }

  rf_memory_review_input_v3 memory_input_v3{sizeof(memory_input_v3),
                                             RF_RATING_GOOD,
                                             RF_MEMORY_BALANCED,
                                             1'800'000'000,
                                             200,
                                             0.02,
                                             0};
  rf_memory_review_result_v3 memory_result_v3{};
  memory_result_v3.struct_size = sizeof(memory_result_v3);
  memory_state = load<rf_memory_schedule_state_v2 (*)()>(
      library, "rf_new_memory_state_v2")();
  if (memory_v3(&memory_state, &memory_input_v3, &memory_result_v3, error,
                sizeof(error)) != 0 ||
      memory_result_v3.event.algorithm_version != 3) {
    std::cerr << "dynamic memory v3 ABI review failed: " << error << '\n';
    dlclose(library);
    return EXIT_FAILURE;
  }
  const rf_review_action_v4 actions[] = {
      {sizeof(rf_review_action_v4), "later", "device", 2, 0,
       RF_RATING_GOOD, 1'800'000'100, 0, RF_MATH_ERROR_NONE, 0},
      {sizeof(rf_review_action_v4), "first", "device", 1, 0,
       RF_RATING_AGAIN, 1'800'000'200, 0, RF_MATH_ERROR_NONE, 0},
  };
  size_t order[2]{};
  if (canonical_v4(actions, 2, order, 2, error, sizeof(error)) != 0 ||
      order[0] != 1 || order[1] != 0) {
    std::cerr << "dynamic v4 canonical replay failed: " << error << '\n';
    dlclose(library);
    return EXIT_FAILURE;
  }
  rf_memory_task_review_input_v5 task_input{};
  task_input.struct_size = sizeof(task_input);
  task_input.state = load<rf_memory_schedule_state_v2 (*)()>(
      library, "rf_new_memory_state_v2")();
  task_input.preset = RF_MEMORY_BALANCED;
  task_input.reviewed_at = 1'800'000'000;
  task_input.point_hits = 3;
  task_input.point_count = 3;
  task_input.duration_reliable = 1;
  task_input.confidence = 5;
  rf_memory_task_review_result_v5 task_result{};
  task_result.struct_size = sizeof(task_result);
  if (memory_task_v5(&task_input, &task_result, error, sizeof(error)) != 0 ||
      task_result.effective_rating != RF_RATING_EASY || task_result.point_coverage != 1.0) {
    std::cerr << "dynamic v5 memory task review failed: " << error << '\n';
    dlclose(library);
    return EXIT_FAILURE;
  }
  dlclose(library);
  std::cout << "Dynamic C ABI tests passed\n";
  return EXIT_SUCCESS;
}
