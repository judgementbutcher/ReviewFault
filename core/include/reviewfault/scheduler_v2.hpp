#pragma once

#include "reviewfault/scheduler.hpp"

#include <cstdint>
#include <vector>

namespace reviewfault {

inline constexpr std::uint32_t kSchedulerAbiVersionV2 = 2;
inline constexpr std::uint32_t kMemoryParameterVersionV2 = 1;
inline constexpr std::uint32_t kMathParameterVersionV2 = 1;

enum class MemoryPreset : std::int32_t {
  TimeSaving = 0,
  Balanced = 1,
  Reinforced = 2,
};

enum class MathIntensity : std::int32_t {
  Intensive = 0,
  Balanced = 1,
  Relaxed = 2,
};

enum class MathFeedback : std::int32_t {
  CannotStart = 0,
  Incorrect = 1,
  EffortfulCorrect = 2,
  FluentCorrect = 3,
};

enum class MathErrorReason : std::int32_t {
  None = 0,
  Concept = 1,
  Approach = 2,
  Calculation = 3,
  Misread = 4,
  ForgottenFact = 5,
  Timeout = 6,
  Other = 7,
};

struct MemoryScheduleState {
  CardState state = CardState::New;
  double difficulty = 0.0;
  double stability_days = 0.0;
  std::int64_t due_at = 0;
  std::int64_t last_reviewed_at = 0;
  std::uint32_t repetitions = 0;
  std::uint32_t lapses = 0;
};

struct MemoryReviewInput {
  Rating rating = Rating::Again;
  std::int64_t reviewed_at = 0;
  MemoryPreset preset = MemoryPreset::Balanced;
};

struct MemoryReviewEvent {
  std::uint32_t algorithm_version = kSchedulerAbiVersionV2;
  std::uint32_t parameter_version = kMemoryParameterVersionV2;
  Rating rating = Rating::Again;
  MemoryPreset preset = MemoryPreset::Balanced;
  CardState state_before = CardState::New;
  CardState state_after = CardState::New;
  std::int64_t reviewed_at = 0;
  std::int64_t due_at_before = 0;
  std::int64_t due_at_after = 0;
  double target_retention = 0.90;
  double elapsed_days = 0.0;
  double scheduled_days = 0.0;
  double retrievability_before = 0.0;
  double difficulty_before = 0.0;
  double difficulty_after = 0.0;
  double stability_before = 0.0;
  double stability_after = 0.0;
};

struct MemoryScheduleResult {
  MemoryScheduleState state;
  MemoryReviewEvent event;
};

struct MathScheduleState {
  std::uint32_t mastery_level = 0;
  std::uint32_t fluent_streak = 0;
  std::int64_t due_at = 0;
  std::int64_t last_reviewed_at = 0;
  std::uint32_t repetitions = 0;
};

struct MathAttemptInput {
  MathFeedback feedback = MathFeedback::CannotStart;
  MathErrorReason error_reason = MathErrorReason::None;
  bool hint_revealed = false;
  std::int64_t reviewed_at = 0;
  MathIntensity intensity = MathIntensity::Balanced;
};

struct MathReviewEvent {
  std::uint32_t algorithm_version = kSchedulerAbiVersionV2;
  std::uint32_t parameter_version = kMathParameterVersionV2;
  MathFeedback requested_feedback = MathFeedback::CannotStart;
  MathFeedback applied_feedback = MathFeedback::CannotStart;
  MathErrorReason error_reason = MathErrorReason::None;
  MathIntensity intensity = MathIntensity::Balanced;
  bool hint_revealed = false;
  std::uint32_t mastery_before = 0;
  std::uint32_t mastery_after = 0;
  std::uint32_t fluent_streak_before = 0;
  std::uint32_t fluent_streak_after = 0;
  std::int64_t reviewed_at = 0;
  std::int64_t due_at_before = 0;
  std::int64_t due_at_after = 0;
  double scheduled_days = 0.0;
};

struct MathScheduleResult {
  MathScheduleState state;
  MathReviewEvent event;
};

[[nodiscard]] double target_retention(MemoryPreset preset);
[[nodiscard]] double interval_multiplier(MathIntensity intensity);
[[nodiscard]] MemoryScheduleResult review_memory_v2(
    const MemoryScheduleState& state, const MemoryReviewInput& input);
[[nodiscard]] MathScheduleResult review_math_v2(
    const MathScheduleState& state, const MathAttemptInput& input);

// Replays are deliberately pure. Repositories use them once when an item with
// v1 history receives its first v2 answer, then persist the returned state.
[[nodiscard]] MemoryScheduleState replay_memory_v2(
    const std::vector<MemoryReviewInput>& history);
[[nodiscard]] MathScheduleState replay_math_v2(
    const std::vector<MathAttemptInput>& history);

}  // namespace reviewfault
