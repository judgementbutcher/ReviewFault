#include "reviewfault/reviewfault_c.h"
#include "reviewfault/scheduler.hpp"

#include <cmath>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <limits>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

using reviewfault::Card;
using reviewfault::CardState;
using reviewfault::Rating;
using reviewfault::Scheduler;
using reviewfault::SchedulerConfig;

constexpr std::int64_t kNow = 1'800'000'000;
constexpr std::int64_t kDay = 86'400;
int failures = 0;

void expect(bool condition, const std::string& message) {
  if (!condition) {
    ++failures;
    std::cerr << "FAIL: " << message << '\n';
  }
}

void expect_near(double actual, double expected, double tolerance,
                 const std::string& message) {
  expect(std::abs(actual - expected) <= tolerance,
         message + " (actual=" + std::to_string(actual) +
             ", expected=" + std::to_string(expected) + ")");
}

template <typename Callable>
void expect_invalid(Callable callable, const std::string& message) {
  try {
    callable();
    expect(false, message);
  } catch (const std::invalid_argument&) {
  }
}

template <typename Callable>
void expect_failure(Callable callable, const std::string& message) {
  try {
    callable();
    expect(false, message);
  } catch (const std::exception&) {
  }
}

void test_new_card_paths() {
  const Scheduler scheduler;
  const Card card;

  const auto again = scheduler.review(card, Rating::Again, kNow);
  expect(again.card.state == CardState::Learning,
         "new Again stays in learning");
  expect(again.card.due_at == kNow + 600, "new Again is due in ten minutes");
  expect(again.card.lapses == 0, "first exposure is not a lapse");

  const auto hard = scheduler.review(card, Rating::Hard, kNow);
  expect(hard.card.state == CardState::Learning, "new Hard stays in learning");
  expect(hard.card.due_at == kNow + 8 * 3600, "new Hard is due in eight hours");

  const auto good = scheduler.review(card, Rating::Good, kNow);
  expect(good.card.state == CardState::Review, "new Good graduates to review");
  expect(good.card.due_at == kNow + 2 * kDay,
         "default retention maps initial Good stability to two days");

  const auto easy = scheduler.review(card, Rating::Easy, kNow);
  expect(easy.card.due_at == kNow + 5 * kDay,
         "default retention maps initial Easy stability to five days");
  expect(easy.card.difficulty < good.card.difficulty,
         "Easy starts with lower difficulty than Good");
}

Card mature_card() {
  Card card;
  card.state = CardState::Review;
  card.difficulty = 5.0;
  card.stability_days = 10.0;
  card.due_at = kNow;
  card.last_reviewed_at = kNow - 10 * kDay;
  card.repetitions = 4;
  return card;
}

void test_review_rating_order() {
  const Scheduler scheduler;
  const Card card = mature_card();
  const auto hard = scheduler.review(card, Rating::Hard, kNow);
  const auto good = scheduler.review(card, Rating::Good, kNow);
  const auto easy = scheduler.review(card, Rating::Easy, kNow);

  expect(hard.card.stability_days < good.card.stability_days,
         "Hard grows stability less than Good");
  expect(good.card.stability_days < easy.card.stability_days,
         "Good grows stability less than Easy");
  expect(hard.card.due_at < good.card.due_at && good.card.due_at < easy.card.due_at,
         "rating order produces increasing intervals");
  expect_near(good.log.retrievability_before, 0.9, 1e-12,
              "retrievability is 90% at one stability interval");
}

void test_late_review_has_spacing_effect() {
  const Scheduler scheduler;
  Card on_time = mature_card();
  Card late = on_time;

  const auto on_time_result = scheduler.review(on_time, Rating::Good, kNow);
  const auto late_result = scheduler.review(late, Rating::Good, kNow + 20 * kDay);
  expect(late_result.log.retrievability_before < on_time_result.log.retrievability_before,
         "late review has lower retrievability");
  expect(late_result.card.stability_days > on_time_result.card.stability_days,
         "successful late recall supplies stronger spacing evidence");
}

void test_failure_relearning() {
  const Scheduler scheduler;
  const Card card = mature_card();
  const auto failed = scheduler.review(card, Rating::Again, kNow);

  expect(failed.card.state == CardState::Relearning,
         "failed mature card enters relearning");
  expect(failed.card.due_at == kNow + 600, "failed card gets short retry");
  expect(failed.card.lapses == card.lapses + 1, "failed review increments lapses");
  expect(failed.card.stability_days < card.stability_days,
         "failure reduces stability");
  expect(failed.card.difficulty > card.difficulty, "failure increases difficulty");

  const auto recovered = scheduler.review(failed.card, Rating::Good, kNow + 600);
  expect(recovered.card.state == CardState::Review,
         "successful relearning graduates to review");
  expect(recovered.card.due_at >= kNow + 600 + kDay,
         "graduated card respects minimum review interval");

  const auto failed_again = scheduler.review(failed.card, Rating::Again, kNow + 600);
  expect(failed_again.card.state == CardState::Relearning,
         "repeated failure stays in the same relearning episode");
  expect(failed_again.card.lapses == failed.card.lapses,
         "repeated relearning failure does not add another lapse");
}

void test_learning_failure_is_not_a_lapse() {
  const Scheduler scheduler;
  const auto first = scheduler.review(Card{}, Rating::Again, kNow);
  const auto second = scheduler.review(first.card, Rating::Again, kNow + 600);
  expect(second.card.state == CardState::Learning,
         "failed initial learning remains learning");
  expect(second.card.lapses == 0,
         "failed initial learning does not count as a mature lapse");
}

void test_retention_configuration() {
  SchedulerConfig lower_config;
  lower_config.target_retention = 0.80;
  SchedulerConfig higher_config;
  higher_config.target_retention = 0.95;

  const Card card = mature_card();
  const auto lower = Scheduler(lower_config).review(card, Rating::Good, kNow);
  const auto higher = Scheduler(higher_config).review(card, Rating::Good, kNow);
  expect(lower.card.due_at > higher.card.due_at,
         "lower target retention permits a longer interval");
}

void test_limits_and_overflow() {
  Card long_lived = mature_card();
  long_lived.stability_days = 3000.0;
  long_lived.last_reviewed_at = kNow - 3000 * kDay;
  const auto capped = Scheduler().review(long_lived, Rating::Easy, kNow);
  expect_near(capped.log.scheduled_days, 3650.0, 1e-12,
              "review interval respects the configured maximum");
  expect(capped.card.due_at == kNow + 3650 * kDay,
         "maximum interval is reflected in the due timestamp");

  expect_failure([] {
    (void)Scheduler().review(Card{}, Rating::Again,
                             std::numeric_limits<std::int64_t>::max());
  }, "due timestamp overflow is rejected");

  expect_failure([] {
    Card card = mature_card();
    card.repetitions = std::numeric_limits<std::uint32_t>::max();
    (void)Scheduler().review(card, Rating::Good, kNow);
  }, "repetition counter overflow is rejected");
}

void test_validation() {
  expect_invalid([] {
    SchedulerConfig config;
    config.target_retention = 0.50;
    Scheduler scheduler(config);
    (void)scheduler;
  }, "invalid retention is rejected");

  expect_invalid([] {
    Card card = mature_card();
    (void)Scheduler().review(card, Rating::Good, card.last_reviewed_at - 1);
  }, "time travel review is rejected");

  expect_invalid([] {
    Card card = mature_card();
    card.difficulty = 11.0;
    (void)Scheduler().review(card, Rating::Good, kNow);
  }, "out of range difficulty is rejected");

  expect_invalid([] {
    (void)Scheduler().review(Card{}, Rating::Good, 0);
  }, "non-positive UTC timestamp is rejected");
}

void test_c_abi() {
  expect(rf_scheduler_abi_version() == 5,
         "C ABI reports v5 while retaining the v1 review export");
  expect(rf_scheduler_config_size() == sizeof(rf_scheduler_config),
         "C ABI reports config layout size");
  expect(rf_card_size() == sizeof(rf_card), "C ABI reports card layout size");
  expect(rf_review_result_size() == sizeof(rf_review_result),
         "C ABI reports result layout size");
  rf_scheduler_config config = rf_default_scheduler_config();
  rf_card card = rf_new_card();
  rf_review_result result{};
  result.struct_size = sizeof(result);
  char error[128]{};

  const int32_t status = rf_review(&config, &card, RF_RATING_GOOD, kNow, &result,
                                   error, sizeof(error));
  expect(status == 0, std::string("C ABI reviews a new card: ") + error);
  expect(result.card.state == RF_CARD_REVIEW, "C ABI returns review state");
  expect(result.card.due_at == kNow + 2 * kDay,
         "C ABI agrees with C++ result");

  card.struct_size = 0;
  expect(rf_review(&config, &card, RF_RATING_GOOD, kNow, &result, error,
                   sizeof(error)) != 0,
         "C ABI rejects a structure size mismatch");
  expect(error[0] != '\0', "C ABI supplies an error diagnostic");
}

std::vector<std::string> split_tabs(const std::string& line) {
  std::vector<std::string> fields;
  std::istringstream stream(line);
  std::string field;
  while (std::getline(stream, field, '\t')) {
    fields.push_back(field);
  }
  return fields;
}

void test_golden_fixtures() {
  std::ifstream fixture("fixtures/scheduler_v1.tsv");
  expect(fixture.good(), "scheduler golden fixture can be opened");
  if (!fixture.good()) {
    return;
  }

  std::string line;
  int vectors = 0;
  while (std::getline(fixture, line)) {
    if (line.empty() || line.front() == '#' || line.starts_with("case\t")) {
      continue;
    }
    const auto f = split_tabs(line);
    expect(f.size() == 19, "golden fixture row has 19 fields");
    if (f.size() != 19) {
      continue;
    }

    Card card;
    card.state = static_cast<CardState>(std::stoi(f[1]));
    card.difficulty = std::stod(f[2]);
    card.stability_days = std::stod(f[3]);
    card.due_at = std::stoll(f[4]);
    card.last_reviewed_at = std::stoll(f[5]);
    card.repetitions = static_cast<std::uint32_t>(std::stoul(f[6]));
    card.lapses = static_cast<std::uint32_t>(std::stoul(f[7]));
    const auto rating = static_cast<Rating>(std::stoi(f[8]));
    const auto reviewed_at = std::stoll(f[9]);
    SchedulerConfig config;
    config.target_retention = std::stod(f[10]);

    const auto actual = Scheduler(config).review(card, rating, reviewed_at);
    const std::string prefix = "golden vector " + f[0] + ": ";
    expect(static_cast<int>(actual.card.state) == std::stoi(f[11]),
           prefix + "state");
    expect_near(actual.card.difficulty, std::stod(f[12]), 1e-12,
                prefix + "difficulty");
    expect_near(actual.card.stability_days, std::stod(f[13]), 1e-12,
                prefix + "stability");
    expect(actual.card.due_at == std::stoll(f[14]), prefix + "due_at");
    expect(actual.card.repetitions == std::stoul(f[15]), prefix + "repetitions");
    expect(actual.card.lapses == std::stoul(f[16]), prefix + "lapses");
    expect_near(actual.log.scheduled_days, std::stod(f[17]), 1e-12,
                prefix + "scheduled days");
    expect_near(actual.log.retrievability_before, std::stod(f[18]), 1e-12,
                prefix + "retrievability");
    ++vectors;
  }
  expect(vectors >= 6, "golden fixture covers all four mature ratings");
}

}  // namespace

int main() {
  test_new_card_paths();
  test_review_rating_order();
  test_late_review_has_spacing_effect();
  test_failure_relearning();
  test_learning_failure_is_not_a_lapse();
  test_retention_configuration();
  test_limits_and_overflow();
  test_validation();
  test_c_abi();
  test_golden_fixtures();

  if (failures != 0) {
    std::cerr << failures << " test(s) failed\n";
    return EXIT_FAILURE;
  }
  std::cout << "All scheduler tests passed\n";
  return EXIT_SUCCESS;
}
