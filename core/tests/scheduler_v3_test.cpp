#include "reviewfault/reviewfault_c.h"
#include "reviewfault/scheduler_v3.hpp"

#include <cmath>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <string>
#include <sstream>

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

void test_memory_guards_and_personalization() {
  const auto default_result = review_memory_v3(
      {}, {Rating::Good, kNow, MemoryPreset::Balanced, 199, 0.50, 0});
  expect(default_result.event.algorithm_version == 3 &&
             default_result.event.parameter_version == 2 &&
             !default_result.event.personalized,
         "small histories retain frozen v3 defaults");

  const auto personal = review_memory_v3(
      {}, {Rating::Good, kNow, MemoryPreset::Balanced, 200, 0.01, 0});
  expect(personal.event.parameter_version == 3 && personal.event.personalized &&
             personal.state.due_at < default_result.state.due_at,
         "qualified calibrated history enables conservative personal parameters");

  const auto initial = review_memory_v3(
      {}, {Rating::Good, kNow, MemoryPreset::Balanced, 0, 0, 0});
  auto overdue = initial.state;
  overdue.due_at = kNow + kDay;
  const auto guarded = review_memory_v3(
      overdue, {Rating::Hard, kNow + 100 * kDay, MemoryPreset::Balanced, 0, 0, 0});
  expect((guarded.event.decision_flags & DecisionOverdueGuardV3) != 0 &&
             guarded.event.scheduled_days <= guarded.event.elapsed_days * 2.0,
         "very overdue non-easy cards have bounded interval growth");

  const auto repeated = review_memory_v3(
      initial.state, {Rating::Again, initial.state.due_at, MemoryPreset::Balanced,
                      0, 0, 4});
  expect(repeated.state.due_at == initial.state.due_at + 8 * 3600 &&
             (repeated.event.decision_flags & DecisionRepeatedFailureGuardV3) != 0,
         "repeated lapses avoid an unbounded same-day loop");
}

void test_math_quality_and_failure_rules() {
  MathScheduleState state{3, 2, kNow, kNow - kDay, 4};
  const auto conceptual = review_math_v3(
      state, {MathFeedback::Incorrect, MathErrorReason::Concept, false, kNow,
              MathIntensity::Balanced, 30, DurationQualityV3::Reliable, 2});
  expect(conceptual.event.scheduled_days == 0.5 &&
             conceptual.event.duration_seconds == 30 &&
             conceptual.state.mastery_level == 2,
         "concept failure is prioritized while duration remains observational");

  const auto fluent = review_math_v3(
      state, {MathFeedback::FluentCorrect, MathErrorReason::None, false, kNow,
              MathIntensity::Balanced, 2, DurationQualityV3::TooShort, 0});
  expect(fluent.state.mastery_level == 4 && fluent.state.fluent_streak == 3 &&
             std::abs(fluent.event.scheduled_days - 37.5) < 1e-12 &&
             (fluent.event.decision_flags & DecisionLongTermExtensionV3) != 0,
         "independent fluent streaks lengthen without treating speed as mastery");
}

void test_c_abi_v3() {
  expect(rf_scheduler_abi_version() == 3, "C ABI reports version three");
  auto memory = rf_new_memory_state_v2();
  rf_memory_review_input_v3 input{sizeof(input), RF_RATING_GOOD,
                                  RF_MEMORY_BALANCED, kNow, 200, 0.02, 0};
  rf_memory_review_result_v3 result{};
  result.struct_size = sizeof(result);
  char error[128]{};
  expect(review_memory_v3(&memory, &input, &result, error, sizeof(error)) == 0 &&
             result.event.algorithm_version == 3 && result.event.parameter_version == 3,
         std::string("memory v3 ABI succeeds: ") + error);

  auto math = rf_new_math_state_v2();
  rf_math_attempt_input_v3 math_input{sizeof(math_input), RF_MATH_INCORRECT,
                                      RF_MATH_ERROR_CONCEPT, 0, RF_MATH_BALANCED,
                                      kNow, 60, RF_DURATION_RELIABLE, 2};
  rf_math_review_result_v3 math_result{};
  math_result.struct_size = sizeof(math_result);
  expect(review_math_v3(&math, &math_input, &math_result, error, sizeof(error)) == 0 &&
             math_result.event.algorithm_version == 3 &&
             math_result.event.scheduled_days == 0.5,
         std::string("math v3 ABI succeeds: ") + error);
}

void test_v3_golden_fixtures() {
  {
    std::ifstream input("fixtures/memory_scheduler_v3.tsv");
    std::string line;
    std::getline(input, line);
    while (std::getline(input, line)) {
      std::istringstream row(line);
      int rating = 0, preset = 0, expected_state = 0;
      std::int64_t reviewed_at = 0, expected_due_at = 0;
      std::uint32_t history = 0, lapses = 0, expected_algorithm = 0,
                    expected_parameter = 0, expected_flags = 0;
      double improvement = 0;
      row >> rating >> reviewed_at >> preset >> history >> improvement >> lapses >>
          expected_state >> expected_due_at >> expected_algorithm >> expected_parameter >>
          expected_flags;
      const auto result = review_memory_v3(
          {}, {static_cast<Rating>(rating), reviewed_at,
               static_cast<MemoryPreset>(preset), history, improvement, lapses});
      expect(static_cast<int>(result.state.state) == expected_state &&
                 result.state.due_at == expected_due_at &&
                 result.event.algorithm_version == expected_algorithm &&
                 result.event.parameter_version == expected_parameter &&
                 result.event.decision_flags == expected_flags,
             "memory v3 golden fixture remains exact");
    }
  }
  {
    std::ifstream input("fixtures/math_scheduler_v3.tsv");
    std::string line;
    std::getline(input, line);
    while (std::getline(input, line)) {
      std::istringstream row(line);
      int feedback = 0, reason = 0, hint = 0, intensity = 0, quality = 0,
          expected_feedback = 0;
      std::int64_t reviewed_at = 0, expected_due_at = 0;
      std::uint32_t duration = 0, failures = 0, expected_mastery = 0,
                    expected_algorithm = 0, expected_parameter = 0, expected_flags = 0;
      row >> feedback >> reason >> hint >> reviewed_at >> intensity >> duration >> quality >>
          failures >> expected_mastery >> expected_due_at >> expected_feedback >>
          expected_algorithm >> expected_parameter >> expected_flags;
      const auto result = review_math_v3(
          {}, {static_cast<MathFeedback>(feedback), static_cast<MathErrorReason>(reason),
               hint != 0, reviewed_at, static_cast<MathIntensity>(intensity), duration,
               static_cast<DurationQualityV3>(quality), failures});
      expect(result.state.mastery_level == expected_mastery &&
                 result.state.due_at == expected_due_at &&
                 static_cast<int>(result.event.applied_feedback) == expected_feedback &&
                 result.event.algorithm_version == expected_algorithm &&
                 result.event.parameter_version == expected_parameter &&
                 result.event.decision_flags == expected_flags,
             "math v3 golden fixture remains exact");
    }
  }
}

}  // namespace

int main() {
  test_memory_guards_and_personalization();
  test_math_quality_and_failure_rules();
  test_c_abi_v3();
  test_v3_golden_fixtures();
  if (failures != 0) {
    std::cerr << failures << " v3 scheduler test(s) failed\n";
    return EXIT_FAILURE;
  }
  std::cout << "All v3 scheduler tests passed\n";
  return EXIT_SUCCESS;
}
