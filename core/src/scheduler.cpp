#include "reviewfault/scheduler.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace reviewfault {
namespace {

constexpr double kSecondsPerDay = 86400.0;
constexpr double kDecayFactor = 19.0 / 81.0;
constexpr double kDecayPower = -0.5;

double clamp_difficulty(double value) {
  return std::clamp(value, 1.0, 10.0);
}

void validate_rating(Rating rating) {
  const auto value = static_cast<std::int32_t>(rating);
  if (value < static_cast<std::int32_t>(Rating::Again) ||
      value > static_cast<std::int32_t>(Rating::Easy)) {
    throw std::invalid_argument("rating must be between Again and Easy");
  }
}

void validate_card(const Card& card, std::int64_t reviewed_at) {
  if (reviewed_at <= 0) {
    throw std::invalid_argument("reviewed_at must be a positive UTC timestamp");
  }
  const auto state = static_cast<std::int32_t>(card.state);
  if (state < static_cast<std::int32_t>(CardState::New) ||
      state > static_cast<std::int32_t>(CardState::Relearning)) {
    throw std::invalid_argument("card state is invalid");
  }
  if (card.state != CardState::New) {
    if (!std::isfinite(card.difficulty) || card.difficulty < 1.0 ||
        card.difficulty > 10.0) {
      throw std::invalid_argument("difficulty must be in [1, 10]");
    }
    if (!std::isfinite(card.stability_days) || card.stability_days <= 0.0) {
      throw std::invalid_argument("stability must be positive");
    }
    if (card.last_reviewed_at <= 0) {
      throw std::invalid_argument("reviewed cards need last_reviewed_at");
    }
    if (reviewed_at < card.last_reviewed_at) {
      throw std::invalid_argument("reviewed_at precedes last review");
    }
  }
}

double initial_stability(Rating rating) {
  switch (rating) {
    case Rating::Again:
      return 0.12;
    case Rating::Hard:
      return 0.50;
    case Rating::Good:
      return 2.0;
    case Rating::Easy:
      return 5.0;
  }
  return 0.12;
}

double initial_difficulty(Rating rating) {
  switch (rating) {
    case Rating::Again:
      return 8.0;
    case Rating::Hard:
      return 6.5;
    case Rating::Good:
      return 5.0;
    case Rating::Easy:
      return 3.5;
  }
  return 8.0;
}

double next_difficulty(double current, Rating rating) {
  double delta = 0.0;
  switch (rating) {
    case Rating::Again:
      delta = 1.20;
      break;
    case Rating::Hard:
      delta = 0.35;
      break;
    case Rating::Good:
      delta = -0.15;
      break;
    case Rating::Easy:
      delta = -0.80;
      break;
  }
  // Gentle mean reversion prevents a few unusual reviews from pinning a card.
  return clamp_difficulty(current + delta + 0.05 * (5.0 - current));
}

double recalled_stability(double stability,
                          double difficulty,
                          double retrievability,
                          Rating rating) {
  double rating_gain = 0.0;
  switch (rating) {
    case Rating::Hard:
      rating_gain = 0.35;
      break;
    case Rating::Good:
      rating_gain = 0.90;
      break;
    case Rating::Easy:
      rating_gain = 1.60;
      break;
    case Rating::Again:
      return stability;
  }

  const double difficulty_factor = std::clamp((11.0 - difficulty) / 5.0, 0.2, 2.0);
  const double spacing_factor =
      std::clamp(std::sqrt(std::max(0.01, 1.0 - retrievability) / 0.10), 0.35, 2.5);
  const double growth = rating_gain * difficulty_factor * spacing_factor;
  return stability * (1.0 + growth);
}

double forgotten_stability(double stability,
                           double difficulty,
                           double retrievability) {
  const double ease = (10.0 - difficulty) / 9.0;
  const double multiplier = 0.20 + 0.27 * ease;
  const double evidence = std::clamp(0.65 + 0.35 * retrievability, 0.65, 1.0);
  return std::max(0.12, stability * multiplier * evidence);
}

double interval_for_retention(double stability, double target_retention) {
  const double denominator = kDecayFactor;
  const double powered = std::pow(target_retention, 1.0 / kDecayPower);
  return stability * (powered - 1.0) / denominator;
}

std::int64_t seconds_from_days(double days) {
  const double seconds = std::round(days * kSecondsPerDay);
  if (seconds > static_cast<double>(std::numeric_limits<std::int64_t>::max())) {
    throw std::overflow_error("scheduled time is too large");
  }
  return static_cast<std::int64_t>(seconds);
}

std::int64_t add_seconds(std::int64_t time, std::int64_t seconds) {
  if (seconds > 0 && time > std::numeric_limits<std::int64_t>::max() - seconds) {
    throw std::overflow_error("scheduled time is too large");
  }
  return time + seconds;
}

}  // namespace

Scheduler::Scheduler(SchedulerConfig config) : config_(config) {
  if (!std::isfinite(config_.target_retention) || config_.target_retention < 0.80 ||
      config_.target_retention > 0.97) {
    throw std::invalid_argument("target retention must be in [0.80, 0.97]");
  }
  if (!std::isfinite(config_.maximum_interval_days) ||
      config_.maximum_interval_days < 1.0) {
    throw std::invalid_argument("maximum interval must be at least one day");
  }
  if (!std::isfinite(config_.minimum_review_interval_days) ||
      config_.minimum_review_interval_days <= 0.0 ||
      config_.minimum_review_interval_days > config_.maximum_interval_days) {
    throw std::invalid_argument("minimum review interval is invalid");
  }
  if (config_.again_step_seconds <= 0 || config_.hard_step_seconds <= 0) {
    throw std::invalid_argument("learning steps must be positive");
  }
}

double Scheduler::retrievability(double elapsed_days, double stability_days) {
  if (!std::isfinite(elapsed_days) || elapsed_days < 0.0 ||
      !std::isfinite(stability_days) || stability_days <= 0.0) {
    throw std::invalid_argument("retrievability inputs are invalid");
  }
  return std::pow(1.0 + kDecayFactor * elapsed_days / stability_days, kDecayPower);
}

ReviewResult Scheduler::review(const Card& card,
                               Rating rating,
                               std::int64_t reviewed_at) const {
  validate_rating(rating);
  validate_card(card, reviewed_at);

  ReviewResult result{};
  result.card = card;
  result.log.rating = rating;
  result.log.state_before = card.state;
  result.log.reviewed_at = reviewed_at;
  result.log.difficulty_before = card.difficulty;
  result.log.stability_before = card.stability_days;

  if (card.state == CardState::New) {
    result.card.difficulty = initial_difficulty(rating);
    result.card.stability_days = initial_stability(rating);
    result.log.elapsed_days = 0.0;
    result.log.retrievability_before = 0.0;
  } else {
    result.log.elapsed_days =
        static_cast<double>(reviewed_at - card.last_reviewed_at) / kSecondsPerDay;
    result.log.retrievability_before =
        retrievability(result.log.elapsed_days, card.stability_days);
    result.card.difficulty = next_difficulty(card.difficulty, rating);
    if (rating == Rating::Again) {
      result.card.stability_days = forgotten_stability(
          card.stability_days, card.difficulty, result.log.retrievability_before);
    } else {
      result.card.stability_days = recalled_stability(
          card.stability_days, card.difficulty, result.log.retrievability_before, rating);
    }
  }

  // Stability beyond the value needed to reach the configured maximum
  // interval cannot affect scheduling and would eventually overflow after
  // repeated successful reviews.
  const double retention_interval_per_stability =
      interval_for_retention(1.0, config_.target_retention);
  const double maximum_useful_stability =
      config_.maximum_interval_days / retention_interval_per_stability;
  result.card.stability_days =
      std::min(result.card.stability_days, maximum_useful_stability);

  result.card.last_reviewed_at = reviewed_at;
  if (card.repetitions == std::numeric_limits<std::uint32_t>::max()) {
    throw std::overflow_error("repetition count is too large");
  }
  result.card.repetitions = card.repetitions + 1;

  if (rating == Rating::Again) {
    result.card.state =
        (card.state == CardState::New || card.state == CardState::Learning)
            ? CardState::Learning
            : CardState::Relearning;
    result.card.due_at = add_seconds(reviewed_at, config_.again_step_seconds);
    // A lapse is one failed recall after graduation. Repeated failures during
    // the same learning/relearning episode do not inflate the lapse count.
    if (card.state == CardState::Review) {
      if (card.lapses == std::numeric_limits<std::uint32_t>::max()) {
        throw std::overflow_error("lapse count is too large");
      }
      result.card.lapses = card.lapses + 1;
    }
  } else if (rating == Rating::Hard &&
             (card.state == CardState::New || card.state == CardState::Learning ||
              card.state == CardState::Relearning)) {
    result.card.state = card.state == CardState::Relearning ? CardState::Relearning
                                                            : CardState::Learning;
    result.card.due_at = add_seconds(reviewed_at, config_.hard_step_seconds);
  } else {
    result.card.state = CardState::Review;
    const double raw_interval =
        interval_for_retention(result.card.stability_days, config_.target_retention);
    result.log.scheduled_days =
        std::clamp(raw_interval, config_.minimum_review_interval_days,
                   config_.maximum_interval_days);
    result.card.due_at =
        add_seconds(reviewed_at, seconds_from_days(result.log.scheduled_days));
  }

  if (result.log.scheduled_days == 0.0) {
    result.log.scheduled_days =
        static_cast<double>(result.card.due_at - reviewed_at) / kSecondsPerDay;
  }
  result.log.state_after = result.card.state;
  result.log.difficulty_after = result.card.difficulty;
  result.log.stability_after = result.card.stability_days;
  return result;
}

}  // namespace reviewfault
