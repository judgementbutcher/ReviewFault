#include "reviewfault/scheduler_v2.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace reviewfault {
namespace {

constexpr std::int64_t kSecondsPerDay = 86'400;
constexpr std::int64_t kAgainStepSeconds = 10 * 60;
constexpr std::int64_t kHardStepSeconds = 8 * 60 * 60;
constexpr double kMaximumMemoryIntervalDays = 3650.0;
constexpr std::array<double, 7> kMathIntervals{1, 3, 7, 14, 30, 60, 120};
// FSRS-6 default parameters, frozen for ReviewFault parameter version 1.
constexpr std::array<double, 21> kFsrs6Weights{
    0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194,
    0.001, 1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629,
    1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542};

void validate_memory_preset(MemoryPreset preset) {
  const auto value = static_cast<std::int32_t>(preset);
  if (value < 0 || value > 2) {
    throw std::invalid_argument("memory preset is invalid");
  }
}

void validate_math_intensity(MathIntensity intensity) {
  const auto value = static_cast<std::int32_t>(intensity);
  if (value < 0 || value > 2) {
    throw std::invalid_argument("math intensity is invalid");
  }
}

void validate_math_state(const MathScheduleState& state,
                         std::int64_t reviewed_at) {
  if (reviewed_at <= 0) {
    throw std::invalid_argument("reviewed_at must be a positive UTC timestamp");
  }
  if (state.mastery_level > 6) {
    throw std::invalid_argument("mastery level must be in [0, 6]");
  }
  if (state.repetitions != 0 && state.last_reviewed_at <= 0) {
    throw std::invalid_argument("reviewed math state needs last_reviewed_at");
  }
  if (state.last_reviewed_at > reviewed_at) {
    throw std::invalid_argument("reviewed_at precedes last review");
  }
}

std::int64_t add_seconds(std::int64_t time, std::int64_t seconds) {
  if (seconds > 0 && time > std::numeric_limits<std::int64_t>::max() - seconds) {
    throw std::overflow_error("scheduled time is too large");
  }
  return time + seconds;
}

std::uint32_t increment(std::uint32_t value, const char* message) {
  if (value == std::numeric_limits<std::uint32_t>::max()) {
    throw std::overflow_error(message);
  }
  return value + 1;
}

bool slow_retry_reason(MathErrorReason reason) {
  return reason == MathErrorReason::Calculation || reason == MathErrorReason::Misread ||
         reason == MathErrorReason::Timeout;
}

void validate_memory_state(const MemoryScheduleState& state,
                           std::int64_t reviewed_at) {
  if (reviewed_at <= 0) {
    throw std::invalid_argument("reviewed_at must be a positive UTC timestamp");
  }
  const auto raw_state = static_cast<std::int32_t>(state.state);
  if (raw_state < 0 || raw_state > 3) {
    throw std::invalid_argument("memory state is invalid");
  }
  if (state.state != CardState::New) {
    if (!std::isfinite(state.difficulty) || state.difficulty < 1.0 ||
        state.difficulty > 10.0 || !std::isfinite(state.stability_days) ||
        state.stability_days <= 0.0 || state.last_reviewed_at <= 0) {
      throw std::invalid_argument("reviewed memory state is invalid");
    }
    if (reviewed_at < state.last_reviewed_at) {
      throw std::invalid_argument("reviewed_at precedes last review");
    }
  }
}

void validate_rating(Rating rating) {
  const auto value = static_cast<std::int32_t>(rating);
  if (value < 1 || value > 4) {
    throw std::invalid_argument("rating must be between Again and Easy");
  }
}

double initial_difficulty(Rating rating) {
  const auto grade = static_cast<double>(static_cast<std::int32_t>(rating));
  return std::clamp(kFsrs6Weights[4] - std::exp(kFsrs6Weights[5] * (grade - 1.0)) +
                        1.0,
                    1.0, 10.0);
}

double next_difficulty(double difficulty, Rating rating) {
  const auto grade = static_cast<double>(static_cast<std::int32_t>(rating));
  const double delta = -kFsrs6Weights[6] * (grade - 3.0);
  const double damped = difficulty + delta * (10.0 - difficulty) / 9.0;
  const double easy_initial = initial_difficulty(Rating::Easy);
  return std::clamp(kFsrs6Weights[7] * easy_initial +
                        (1.0 - kFsrs6Weights[7]) * damped,
                    1.0, 10.0);
}

double retrievability(double elapsed_days, double stability_days) {
  const double decay = -kFsrs6Weights[20];
  const double factor = std::pow(0.9, 1.0 / decay) - 1.0;
  return std::pow(1.0 + factor * elapsed_days / stability_days, decay);
}

double interval_for_retention(double stability, double retention) {
  const double decay = -kFsrs6Weights[20];
  const double factor = std::pow(0.9, 1.0 / decay) - 1.0;
  return stability / factor * (std::pow(retention, 1.0 / decay) - 1.0);
}

double recall_stability(double stability, double difficulty, double recall,
                        Rating rating) {
  const double hard_penalty = rating == Rating::Hard ? kFsrs6Weights[15] : 1.0;
  const double easy_bonus = rating == Rating::Easy ? kFsrs6Weights[16] : 1.0;
  return stability *
         (1.0 + std::exp(kFsrs6Weights[8]) * (11.0 - difficulty) *
                    std::pow(stability, -kFsrs6Weights[9]) *
                    (std::exp(kFsrs6Weights[10] * (1.0 - recall)) - 1.0) *
                    hard_penalty * easy_bonus);
}

double forget_stability(double stability, double difficulty, double recall) {
  return std::max(
      kFsrs6Weights[0],
      kFsrs6Weights[11] * std::pow(difficulty, -kFsrs6Weights[12]) *
          (std::pow(stability + 1.0, kFsrs6Weights[13]) - 1.0) *
          std::exp(kFsrs6Weights[14] * (1.0 - recall)));
}

double short_term_stability(double stability, Rating rating) {
  const double grade = static_cast<double>(static_cast<std::int32_t>(rating));
  const double multiplier = std::exp(kFsrs6Weights[17] *
                                     (grade - 3.0 + kFsrs6Weights[18])) *
                            std::pow(stability, -kFsrs6Weights[19]);
  return std::max(kFsrs6Weights[0], stability * multiplier);
}

void validate_feedback(MathFeedback feedback, MathErrorReason reason) {
  const auto value = static_cast<std::int32_t>(feedback);
  if (value < 0 || value > 3) {
    throw std::invalid_argument("math feedback is invalid");
  }
  const auto reason_value = static_cast<std::int32_t>(reason);
  if (reason_value < 0 || reason_value > 7) {
    throw std::invalid_argument("math error reason is invalid");
  }
  if (feedback != MathFeedback::Incorrect && reason != MathErrorReason::None) {
    throw std::invalid_argument("an error reason is only valid for an incorrect answer");
  }
}

}  // namespace

double target_retention(MemoryPreset preset) {
  validate_memory_preset(preset);
  switch (preset) {
    case MemoryPreset::TimeSaving:
      return 0.85;
    case MemoryPreset::Balanced:
      return 0.90;
    case MemoryPreset::Reinforced:
      return 0.93;
  }
  throw std::invalid_argument("memory preset is invalid");
}

double interval_multiplier(MathIntensity intensity) {
  validate_math_intensity(intensity);
  switch (intensity) {
    case MathIntensity::Intensive:
      return 0.75;
    case MathIntensity::Balanced:
      return 1.0;
    case MathIntensity::Relaxed:
      return 1.25;
  }
  throw std::invalid_argument("math intensity is invalid");
}

MemoryScheduleResult review_memory_v2(const MemoryScheduleState& state,
                                      const MemoryReviewInput& input) {
  const double retention = target_retention(input.preset);
  validate_rating(input.rating);
  validate_memory_state(state, input.reviewed_at);
  MemoryScheduleResult result;
  result.state = state;
  double elapsed_days = 0.0;
  double recall_before = 0.0;
  if (state.state == CardState::New) {
    const auto index = static_cast<std::size_t>(static_cast<std::int32_t>(input.rating) - 1);
    result.state.difficulty = initial_difficulty(input.rating);
    result.state.stability_days = kFsrs6Weights[index];
  } else {
    elapsed_days = static_cast<double>(input.reviewed_at - state.last_reviewed_at) /
                   static_cast<double>(kSecondsPerDay);
    recall_before = retrievability(elapsed_days, state.stability_days);
    result.state.difficulty = next_difficulty(state.difficulty, input.rating);
    if (state.state == CardState::Review) {
      result.state.stability_days =
          input.rating == Rating::Again
              ? forget_stability(state.stability_days, state.difficulty, recall_before)
              : recall_stability(state.stability_days, state.difficulty, recall_before,
                                 input.rating);
    } else {
      result.state.stability_days = short_term_stability(state.stability_days, input.rating);
    }
  }
  result.state.last_reviewed_at = input.reviewed_at;
  result.state.repetitions = increment(state.repetitions, "repetition count is too large");
  const double maximum_useful_stability =
      kMaximumMemoryIntervalDays / interval_for_retention(1.0, retention);
  if (!std::isfinite(result.state.stability_days)) {
    result.state.stability_days = maximum_useful_stability;
  } else {
    result.state.stability_days =
        std::min(result.state.stability_days, maximum_useful_stability);
  }

  double scheduled_days = 0.0;
  if (input.rating == Rating::Again) {
    result.state.state = state.state == CardState::Review ? CardState::Relearning
                                                          : state.state == CardState::Relearning
                                                                ? CardState::Relearning
                                                                : CardState::Learning;
    result.state.due_at = add_seconds(input.reviewed_at, kAgainStepSeconds);
    if (state.state == CardState::Review) {
      result.state.lapses = increment(state.lapses, "lapse count is too large");
    }
    scheduled_days = static_cast<double>(kAgainStepSeconds) / kSecondsPerDay;
  } else if (input.rating == Rating::Hard && state.state != CardState::Review) {
    result.state.state = state.state == CardState::Relearning ? CardState::Relearning
                                                              : CardState::Learning;
    result.state.due_at = add_seconds(input.reviewed_at, kHardStepSeconds);
    scheduled_days = static_cast<double>(kHardStepSeconds) / kSecondsPerDay;
  } else {
    result.state.state = CardState::Review;
    scheduled_days = std::clamp(
        interval_for_retention(result.state.stability_days, retention), 1.0,
        kMaximumMemoryIntervalDays);
    result.state.due_at = add_seconds(
        input.reviewed_at,
        static_cast<std::int64_t>(std::llround(scheduled_days * kSecondsPerDay)));
  }
  result.event = {kSchedulerAbiVersionV2,
                  kMemoryParameterVersionV2,
                  input.rating,
                  input.preset,
                  state.state,
                  result.state.state,
                  input.reviewed_at,
                  state.due_at,
                  result.state.due_at,
                  retention,
                  elapsed_days,
                  scheduled_days,
                  recall_before,
                  state.difficulty,
                  result.state.difficulty,
                  state.stability_days,
                  result.state.stability_days};
  return result;
}

MathScheduleResult review_math_v2(const MathScheduleState& state,
                                  const MathAttemptInput& input) {
  validate_math_intensity(input.intensity);
  validate_math_state(state, input.reviewed_at);
  validate_feedback(input.feedback, input.error_reason);

  MathScheduleResult result;
  result.state = state;
  result.event.requested_feedback = input.feedback;
  result.event.applied_feedback =
      input.hint_revealed && input.feedback == MathFeedback::FluentCorrect
          ? MathFeedback::EffortfulCorrect
          : input.feedback;
  result.event.error_reason = input.error_reason;
  result.event.intensity = input.intensity;
  result.event.hint_revealed = input.hint_revealed;
  result.event.mastery_before = state.mastery_level;
  result.event.fluent_streak_before = state.fluent_streak;
  result.event.reviewed_at = input.reviewed_at;
  result.event.due_at_before = state.due_at;

  double days = 1.0;
  switch (result.event.applied_feedback) {
    case MathFeedback::CannotStart:
      result.state.mastery_level = state.mastery_level > 1 ? state.mastery_level - 2 : 0;
      result.state.fluent_streak = 0;
      days = 1.0;
      break;
    case MathFeedback::Incorrect:
      result.state.mastery_level = state.mastery_level > 0 ? state.mastery_level - 1 : 0;
      result.state.fluent_streak = 0;
      days = slow_retry_reason(input.error_reason) ? 2.0 : 1.0;
      break;
    case MathFeedback::EffortfulCorrect:
      result.state.mastery_level = std::max<std::uint32_t>(1, state.mastery_level);
      result.state.fluent_streak = 0;
      days = kMathIntervals[result.state.mastery_level] *
             interval_multiplier(input.intensity);
      break;
    case MathFeedback::FluentCorrect:
      result.state.mastery_level = std::min<std::uint32_t>(6, state.mastery_level + 1);
      result.state.fluent_streak = increment(state.fluent_streak, "fluent streak is too large");
      days = kMathIntervals[result.state.mastery_level] *
             interval_multiplier(input.intensity);
      break;
  }
  days = std::clamp(days, 1.0, 180.0);
  const auto seconds = static_cast<std::int64_t>(std::llround(days * kSecondsPerDay));
  result.state.due_at = add_seconds(input.reviewed_at, seconds);
  result.state.last_reviewed_at = input.reviewed_at;
  result.state.repetitions = increment(state.repetitions, "repetition count is too large");
  result.event.mastery_after = result.state.mastery_level;
  result.event.fluent_streak_after = result.state.fluent_streak;
  result.event.due_at_after = result.state.due_at;
  result.event.scheduled_days = days;
  return result;
}

MemoryScheduleState replay_memory_v2(const std::vector<MemoryReviewInput>& history) {
  MemoryScheduleState state;
  for (const auto& input : history) {
    state = review_memory_v2(state, input).state;
  }
  return state;
}

MathScheduleState replay_math_v2(const std::vector<MathAttemptInput>& history) {
  MathScheduleState state;
  for (const auto& input : history) {
    state = review_math_v2(state, input).state;
  }
  return state;
}

}  // namespace reviewfault
