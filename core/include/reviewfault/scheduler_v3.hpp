#pragma once

#include "reviewfault/scheduler_v2.hpp"

#include <cstdint>

namespace reviewfault {

inline constexpr std::uint32_t kSchedulerAbiVersionV3 = 3;
inline constexpr std::uint32_t kMemoryParameterVersionV3 = 2;
inline constexpr std::uint32_t kMemoryPersonalParameterVersionV3 = 3;
inline constexpr std::uint32_t kMathParameterVersionV3 = 2;
inline constexpr std::uint32_t kMinimumPersonalHistoryV3 = 200;
inline constexpr double kMinimumCalibrationImprovementV3 = 0.01;

enum class DurationQualityV3 : std::int32_t {
  Unknown = 0,
  Reliable = 1,
  TooShort = 2,
  Interrupted = 3,
};

enum DecisionFlagV3 : std::uint32_t {
  DecisionNoneV3 = 0,
  DecisionLearningStepV3 = 1u << 0,
  DecisionOverdueGuardV3 = 1u << 1,
  DecisionRepeatedFailureGuardV3 = 1u << 2,
  DecisionPersonalParametersV3 = 1u << 3,
  DecisionHintCapV3 = 1u << 4,
  DecisionLongTermExtensionV3 = 1u << 5,
};

struct MemoryReviewInputV3 {
  Rating rating = Rating::Again;
  std::int64_t reviewed_at = 0;
  MemoryPreset preset = MemoryPreset::Balanced;
  std::uint32_t history_event_count = 0;
  double calibration_improvement = 0.0;
  std::uint32_t consecutive_lapses = 0;
};

struct MemoryReviewEventV3 : MemoryReviewEvent {
  bool personalized = false;
  bool learning_step = false;
  double overdue_days = 0.0;
  std::uint32_t decision_flags = DecisionNoneV3;
};

struct MemoryScheduleResultV3 {
  MemoryScheduleState state;
  MemoryReviewEventV3 event;
};

struct MathAttemptInputV3 {
  MathFeedback feedback = MathFeedback::CannotStart;
  MathErrorReason error_reason = MathErrorReason::None;
  bool hint_revealed = false;
  std::int64_t reviewed_at = 0;
  MathIntensity intensity = MathIntensity::Balanced;
  std::uint32_t duration_seconds = 0;
  DurationQualityV3 duration_quality = DurationQualityV3::Unknown;
  std::uint32_t consecutive_failures = 0;
};

struct MathReviewEventV3 : MathReviewEvent {
  std::uint32_t duration_seconds = 0;
  DurationQualityV3 duration_quality = DurationQualityV3::Unknown;
  std::uint32_t consecutive_failures = 0;
  std::uint32_t decision_flags = DecisionNoneV3;
};

struct MathScheduleResultV3 {
  MathScheduleState state;
  MathReviewEventV3 event;
};

[[nodiscard]] bool personal_memory_parameters_eligible_v3(
    std::uint32_t history_event_count, double calibration_improvement);
[[nodiscard]] MemoryScheduleResultV3 review_memory_v3(
    const MemoryScheduleState& state, const MemoryReviewInputV3& input);
[[nodiscard]] MathScheduleResultV3 review_math_v3(
    const MathScheduleState& state, const MathAttemptInputV3& input);

}  // namespace reviewfault
