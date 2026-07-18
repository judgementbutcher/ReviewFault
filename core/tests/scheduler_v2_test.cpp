#include "reviewfault/reviewfault_c.h"
#include "reviewfault/scheduler_v2.hpp"

#include <cmath>
#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

using namespace reviewfault;
constexpr std::int64_t kNow = 1'800'000'000;
constexpr std::int64_t kDay = 86'400;
int failures = 0;

void expect(bool condition, const std::string& message) {
  if (!condition) {
    ++failures;
    std::cerr << "FAIL: " << message << '\n';
  }
}

template <typename Callable>
void expect_invalid(Callable callable, const std::string& message) {
  try {
    callable();
    expect(false, message);
  } catch (const std::invalid_argument&) {
  }
}

void test_memory_fsrs6_paths() {
  const auto again = review_memory_v2({}, {Rating::Again, kNow, MemoryPreset::Balanced});
  expect(again.state.state == CardState::Learning, "memory Again enters learning");
  expect(again.state.due_at == kNow + 600, "memory Again uses ten minute step");
  expect(again.event.algorithm_version == 2 && again.event.parameter_version == 1,
         "memory event freezes algorithm and parameter versions");

  const auto hard = review_memory_v2({}, {Rating::Hard, kNow, MemoryPreset::Balanced});
  expect(hard.state.due_at == kNow + 8 * 3600, "memory Hard uses eight hour step");

  const auto good = review_memory_v2({}, {Rating::Good, kNow, MemoryPreset::Balanced});
  expect(good.state.state == CardState::Review, "memory Good graduates");
  expect(std::abs(good.state.stability_days - 2.3065) < 1e-12,
         "memory uses frozen FSRS-6 initial stability");
  expect(good.state.due_at == 1'800'199'282,
         "memory v2 golden due timestamp is exact across bindings");
  expect(std::abs(good.event.target_retention - 0.90) < 1e-12,
         "balanced target retention is recorded");

  const auto saving = review_memory_v2({}, {Rating::Good, kNow, MemoryPreset::TimeSaving});
  const auto reinforced =
      review_memory_v2({}, {Rating::Good, kNow, MemoryPreset::Reinforced});
  expect(saving.state.due_at > good.state.due_at &&
             good.state.due_at > reinforced.state.due_at,
         "memory presets order intervals by retention");

  const auto failed = review_memory_v2(good.state,
                                       {Rating::Again, good.state.due_at,
                                        MemoryPreset::Balanced});
  expect(failed.state.state == CardState::Relearning && failed.state.lapses == 1,
         "mature memory failure enters relearning and counts a lapse");
}

void test_math_mastery_ladder() {
  MathScheduleState level_four{4, 3, kNow, kNow - kDay, 7};
  const auto cannot = review_math_v2(
      level_four, {MathFeedback::CannotStart, MathErrorReason::None, false, kNow,
                   MathIntensity::Balanced});
  expect(cannot.state.mastery_level == 2 && cannot.state.fluent_streak == 0,
         "cannot start drops two levels and clears streak");
  expect(cannot.state.due_at == kNow + kDay, "cannot start retries in one day");

  const auto calculation = review_math_v2(
      level_four, {MathFeedback::Incorrect, MathErrorReason::Calculation, false, kNow,
                   MathIntensity::Balanced});
  expect(calculation.state.mastery_level == 3 &&
             calculation.state.due_at == kNow + 2 * kDay,
         "calculation error drops one level and retries in two days");
  const auto concept_result = review_math_v2(
      level_four, {MathFeedback::Incorrect, MathErrorReason::Concept, false, kNow,
                   MathIntensity::Balanced});
  expect(concept_result.state.due_at == kNow + kDay,
         "concept error retries in one day");

  MathScheduleState new_problem{};
  const auto effortful = review_math_v2(
      new_problem, {MathFeedback::EffortfulCorrect, MathErrorReason::None, false, kNow,
                    MathIntensity::Balanced});
  expect(effortful.state.mastery_level == 1 &&
             effortful.state.due_at == kNow + 3 * kDay,
         "first effortful success reaches level one");
  const auto still_level_one = review_math_v2(
      effortful.state,
      {MathFeedback::EffortfulCorrect, MathErrorReason::None, false,
       effortful.state.due_at, MathIntensity::Relaxed});
  expect(still_level_one.state.mastery_level == 1 &&
             std::abs(still_level_one.event.scheduled_days - 3.75) < 1e-12,
         "effortful success keeps mastery and applies intensity");

  const auto fluent = review_math_v2(
      level_four, {MathFeedback::FluentCorrect, MathErrorReason::None, false, kNow,
                   MathIntensity::Intensive});
  expect(fluent.state.mastery_level == 5 && fluent.state.fluent_streak == 4 &&
             fluent.event.scheduled_days == 45.0,
         "fluent success raises mastery and streak");
  const auto hinted = review_math_v2(
      level_four, {MathFeedback::FluentCorrect, MathErrorReason::None, true, kNow,
                   MathIntensity::Balanced});
  expect(hinted.event.applied_feedback == MathFeedback::EffortfulCorrect &&
             hinted.state.mastery_level == 4 && hinted.state.fluent_streak == 0,
         "viewing a hint caps math feedback at effortful correct");
}

void test_replay_and_validation() {
  const std::vector<MemoryReviewInput> memory_history{
      {Rating::Good, kNow, MemoryPreset::Balanced},
      {Rating::Hard, kNow + 3 * kDay, MemoryPreset::Balanced}};
  auto manual_memory = MemoryScheduleState{};
  for (const auto& input : memory_history) {
    manual_memory = review_memory_v2(manual_memory, input).state;
  }
  const auto replayed_memory = replay_memory_v2(memory_history);
  expect(replayed_memory.due_at == manual_memory.due_at &&
             replayed_memory.repetitions == 2,
         "memory history replay is deterministic");

  const std::vector<MathAttemptInput> math_history{
      {MathFeedback::EffortfulCorrect, MathErrorReason::None, false, kNow,
       MathIntensity::Balanced},
      {MathFeedback::FluentCorrect, MathErrorReason::None, false, kNow + 3 * kDay,
       MathIntensity::Balanced}};
  const auto replayed_math = replay_math_v2(math_history);
  expect(replayed_math.mastery_level == 2 && replayed_math.fluent_streak == 1,
         "math history replay is deterministic");

  expect_invalid([] {
    (void)review_math_v2({}, {MathFeedback::FluentCorrect, MathErrorReason::Concept,
                              false, kNow, MathIntensity::Balanced});
  }, "error reasons cannot be attached to successful math feedback");

  MemoryScheduleState huge{CardState::Review, 5.0, 1e300, kNow,
                           kNow - kDay, 12, 0};
  const auto capped = review_memory_v2(
      huge, {Rating::Easy, kNow, MemoryPreset::Balanced});
  expect(capped.event.scheduled_days == 3650.0 &&
             std::isfinite(capped.state.stability_days),
         "FSRS stability and due interval remain bounded");
}

void test_c_abi_v2() {
  expect(rf_scheduler_abi_version() == 4,
         "current C ABI retains the version two entry points");
  auto memory = rf_new_memory_state_v2();
  rf_memory_review_input_v2 memory_input{sizeof(rf_memory_review_input_v2),
                                         RF_RATING_GOOD,
                                         RF_MEMORY_BALANCED,
                                         kNow};
  rf_memory_review_result_v2 memory_result{};
  memory_result.struct_size = sizeof(memory_result);
  char error[128]{};
  expect(review_memory_v2(&memory, &memory_input, &memory_result, error,
                          sizeof(error)) == 0,
         std::string("memory v2 C ABI succeeds: ") + error);
  expect(memory_result.state.state == RF_CARD_REVIEW &&
             memory_result.event.parameter_version == 1,
         "memory v2 C ABI returns typed state and event");

  auto math = rf_new_math_state_v2();
  rf_math_attempt_input_v2 math_input{sizeof(rf_math_attempt_input_v2),
                                      RF_MATH_FLUENT_CORRECT,
                                      RF_MATH_ERROR_NONE,
                                      0,
                                      RF_MATH_BALANCED,
                                      kNow};
  rf_math_review_result_v2 math_result{};
  math_result.struct_size = sizeof(math_result);
  expect(review_math_v2(&math, &math_input, &math_result, error, sizeof(error)) == 0,
         std::string("math v2 C ABI succeeds: ") + error);
  expect(math_result.state.mastery_level == 1 &&
             math_result.state.due_at == kNow + 3 * kDay,
         "math v2 C ABI follows the mastery ladder");
}

}  // namespace

int main() {
  test_memory_fsrs6_paths();
  test_math_mastery_ladder();
  test_replay_and_validation();
  test_c_abi_v2();
  if (failures != 0) {
    std::cerr << failures << " v2 scheduler test(s) failed\n";
    return EXIT_FAILURE;
  }
  std::cout << "All v2 scheduler tests passed\n";
  return EXIT_SUCCESS;
}
