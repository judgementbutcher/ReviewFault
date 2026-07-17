#pragma once

#include <cstdint>

namespace reviewfault {

enum class Rating : std::int32_t {
  Again = 1,
  Hard = 2,
  Good = 3,
  Easy = 4,
};

enum class CardState : std::int32_t {
  New = 0,
  Learning = 1,
  Review = 2,
  Relearning = 3,
};

struct SchedulerConfig {
  double target_retention = 0.90;
  double maximum_interval_days = 3650.0;
  double minimum_review_interval_days = 1.0;
  std::int64_t again_step_seconds = 10 * 60;
  std::int64_t hard_step_seconds = 8 * 60 * 60;
};

struct Card {
  CardState state = CardState::New;
  double difficulty = 0.0;
  double stability_days = 0.0;
  std::int64_t due_at = 0;
  std::int64_t last_reviewed_at = 0;
  std::uint32_t repetitions = 0;
  std::uint32_t lapses = 0;
};

struct ReviewLog {
  Rating rating = Rating::Again;
  CardState state_before = CardState::New;
  CardState state_after = CardState::New;
  std::int64_t reviewed_at = 0;
  double elapsed_days = 0.0;
  double scheduled_days = 0.0;
  double retrievability_before = 0.0;
  double difficulty_before = 0.0;
  double difficulty_after = 0.0;
  double stability_before = 0.0;
  double stability_after = 0.0;
};

struct ReviewResult {
  Card card;
  ReviewLog log;
};

class Scheduler {
 public:
  explicit Scheduler(SchedulerConfig config = {});

  [[nodiscard]] ReviewResult review(const Card& card,
                                    Rating rating,
                                    std::int64_t reviewed_at) const;

  [[nodiscard]] static double retrievability(double elapsed_days,
                                             double stability_days);

 private:
  SchedulerConfig config_;
};

}  // namespace reviewfault

