#include "reviewfault/scheduler_v3.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace reviewfault {
namespace {

constexpr std::int64_t kSecondsPerDay = 86'400;
constexpr double kMaximumMemoryIntervalDays = 3650.0;
constexpr double kMaximumMathIntervalDays = 365.0;
constexpr double kFsrsDecay = -0.1542;

std::int64_t due_after(std::int64_t reviewed_at, double days) {
  const auto seconds = static_cast<std::int64_t>(std::llround(days * kSecondsPerDay));
  if (seconds > 0 && reviewed_at > std::numeric_limits<std::int64_t>::max() - seconds) {
    throw std::overflow_error("scheduled time is too large");
  }
  return reviewed_at + seconds;
}

double interval_for_retention_v3(double stability, double retention) {
  const double factor = std::pow(0.9, 1.0 / kFsrsDecay) - 1.0;
  return stability / factor * (std::pow(retention, 1.0 / kFsrsDecay) - 1.0);
}

void validate_duration_quality(DurationQualityV3 quality) {
  const auto value = static_cast<std::int32_t>(quality);
  if (value < 0 || value > 3) {
    throw std::invalid_argument("duration quality is invalid");
  }
}

bool conceptual_reason(MathErrorReason reason) {
  return reason == MathErrorReason::Concept || reason == MathErrorReason::Approach ||
         reason == MathErrorReason::ForgottenFact;
}

}  // namespace

bool personal_memory_parameters_eligible_v3(std::uint32_t history_event_count,
                                             double calibration_improvement) {
  return history_event_count >= kMinimumPersonalHistoryV3 &&
         std::isfinite(calibration_improvement) &&
         calibration_improvement >= kMinimumCalibrationImprovementV3;
}

MemoryScheduleResultV3 review_memory_v3(const MemoryScheduleState& state,
                                        const MemoryReviewInputV3& input) {
  if (!std::isfinite(input.calibration_improvement)) {
    throw std::invalid_argument("calibration improvement must be finite");
  }
  const auto baseline = review_memory_v2(
      state, {input.rating, input.reviewed_at, input.preset});
  MemoryScheduleResultV3 result;
  result.state = baseline.state;
  static_cast<MemoryReviewEvent&>(result.event) = baseline.event;
  result.event.algorithm_version = kSchedulerAbiVersionV3;
  result.event.parameter_version = kMemoryParameterVersionV3;
  result.event.learning_step = result.state.state == CardState::Learning ||
                               result.state.state == CardState::Relearning;
  if (result.event.learning_step) {
    result.event.decision_flags |= DecisionLearningStepV3;
  }

  if (state.due_at > 0 && input.reviewed_at > state.due_at) {
    result.event.overdue_days = static_cast<double>(input.reviewed_at - state.due_at) /
                                static_cast<double>(kSecondsPerDay);
  }

  result.event.personalized = personal_memory_parameters_eligible_v3(
      input.history_event_count, input.calibration_improvement);
  if (result.event.personalized) {
    result.event.parameter_version = kMemoryPersonalParameterVersionV3;
    result.event.target_retention = std::min(0.95, result.event.target_retention + 0.01);
    result.event.decision_flags |= DecisionPersonalParametersV3;
  }

  if (result.state.state == CardState::Review) {
    double days = interval_for_retention_v3(result.state.stability_days,
                                             result.event.target_retention);
    if (result.event.overdue_days > 30.0 && input.rating != Rating::Easy) {
      days = std::min(days, std::max(1.0, result.event.elapsed_days * 2.0));
      result.event.decision_flags |= DecisionOverdueGuardV3;
    }
    days = std::clamp(days, 1.0, kMaximumMemoryIntervalDays);
    result.state.due_at = due_after(input.reviewed_at, days);
    result.event.due_at_after = result.state.due_at;
    result.event.scheduled_days = days;
  } else if (input.rating == Rating::Again && input.consecutive_lapses >= 2) {
    const auto seconds = input.consecutive_lapses >= 4 ? 8 * 3600 : 30 * 60;
    result.state.due_at = input.reviewed_at + seconds;
    result.event.due_at_after = result.state.due_at;
    result.event.scheduled_days = static_cast<double>(seconds) / kSecondsPerDay;
    result.event.decision_flags |= DecisionRepeatedFailureGuardV3;
  }
  return result;
}

MathScheduleResultV3 review_math_v3(const MathScheduleState& state,
                                    const MathAttemptInputV3& input) {
  validate_duration_quality(input.duration_quality);
  const auto baseline = review_math_v2(
      state, {input.feedback, input.error_reason, input.hint_revealed,
              input.reviewed_at, input.intensity});
  MathScheduleResultV3 result;
  result.state = baseline.state;
  static_cast<MathReviewEvent&>(result.event) = baseline.event;
  result.event.algorithm_version = kSchedulerAbiVersionV3;
  result.event.parameter_version = kMathParameterVersionV3;
  result.event.duration_seconds = input.duration_seconds;
  result.event.duration_quality = input.duration_quality;
  result.event.consecutive_failures = input.consecutive_failures;
  if (input.hint_revealed && input.feedback == MathFeedback::FluentCorrect) {
    result.event.decision_flags |= DecisionHintCapV3;
  }

  double days = result.event.scheduled_days;
  if (result.event.applied_feedback == MathFeedback::Incorrect) {
    days = conceptual_reason(input.error_reason) ? 0.5 : 1.5;
  } else if (result.event.applied_feedback == MathFeedback::CannotStart) {
    days = 0.5;
  }
  if (result.event.applied_feedback <= MathFeedback::Incorrect &&
      input.consecutive_failures >= 2) {
    days = 0.5;
    result.event.decision_flags |= DecisionRepeatedFailureGuardV3;
  }
  if (result.event.applied_feedback == MathFeedback::FluentCorrect &&
      result.state.fluent_streak >= 3) {
    days *= 1.25;
    result.event.decision_flags |= DecisionLongTermExtensionV3;
  }
  days = std::clamp(days, 0.5, kMaximumMathIntervalDays);
  result.state.due_at = due_after(input.reviewed_at, days);
  result.event.due_at_after = result.state.due_at;
  result.event.scheduled_days = days;
  return result;
}

}  // namespace reviewfault
