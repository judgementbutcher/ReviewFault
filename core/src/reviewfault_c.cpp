#include "reviewfault/reviewfault_c.h"

#include "reviewfault/scheduler.hpp"

#include <algorithm>
#include <cstring>
#include <exception>
#include <stdexcept>

namespace {

void write_error(const char* message, char* buffer, size_t size) {
  if (buffer == nullptr || size == 0) {
    return;
  }
  const size_t length = std::min(std::strlen(message), size - 1);
  std::memcpy(buffer, message, length);
  buffer[length] = '\0';
}

reviewfault::Card to_cpp_card(const rf_card& card) {
  return {
      static_cast<reviewfault::CardState>(card.state),
      card.difficulty,
      card.stability_days,
      card.due_at,
      card.last_reviewed_at,
      card.repetitions,
      card.lapses,
  };
}

rf_card to_c_card(const reviewfault::Card& card) {
  return {
      sizeof(rf_card),
      static_cast<int32_t>(card.state),
      card.difficulty,
      card.stability_days,
      card.due_at,
      card.last_reviewed_at,
      card.repetitions,
      card.lapses,
  };
}

rf_review_log to_c_log(const reviewfault::ReviewLog& log) {
  return {
      sizeof(rf_review_log),
      static_cast<int32_t>(log.rating),
      static_cast<int32_t>(log.state_before),
      static_cast<int32_t>(log.state_after),
      log.reviewed_at,
      log.elapsed_days,
      log.scheduled_days,
      log.retrievability_before,
      log.difficulty_before,
      log.difficulty_after,
      log.stability_before,
      log.stability_after,
  };
}

}  // namespace

extern "C" {

uint32_t rf_scheduler_abi_version(void) {
  return RF_SCHEDULER_ABI_VERSION;
}

size_t rf_scheduler_config_size(void) {
  return sizeof(rf_scheduler_config);
}

size_t rf_card_size(void) {
  return sizeof(rf_card);
}

size_t rf_review_result_size(void) {
  return sizeof(rf_review_result);
}

rf_scheduler_config rf_default_scheduler_config(void) {
  const reviewfault::SchedulerConfig config{};
  return {
      sizeof(rf_scheduler_config),
      config.target_retention,
      config.maximum_interval_days,
      config.minimum_review_interval_days,
      config.again_step_seconds,
      config.hard_step_seconds,
  };
}

rf_card rf_new_card(void) {
  return to_c_card(reviewfault::Card{});
}

int32_t rf_review(const rf_scheduler_config* config,
                  const rf_card* card,
                  int32_t rating,
                  int64_t reviewed_at,
                  rf_review_result* result,
                  char* error_buffer,
                  size_t error_buffer_size) {
  try {
    if (config == nullptr || card == nullptr || result == nullptr) {
      throw std::invalid_argument("config, card, and result are required");
    }
    if (config->struct_size != sizeof(rf_scheduler_config) ||
        card->struct_size != sizeof(rf_card) ||
        result->struct_size != sizeof(rf_review_result)) {
      throw std::invalid_argument("ABI structure size mismatch");
    }

    const reviewfault::Scheduler scheduler({
        config->target_retention,
        config->maximum_interval_days,
        config->minimum_review_interval_days,
        config->again_step_seconds,
        config->hard_step_seconds,
    });
    const auto reviewed = scheduler.review(
        to_cpp_card(*card), static_cast<reviewfault::Rating>(rating), reviewed_at);
    result->card = to_c_card(reviewed.card);
    result->log = to_c_log(reviewed.log);
    if (error_buffer != nullptr && error_buffer_size > 0) {
      error_buffer[0] = '\0';
    }
    return 0;
  } catch (const std::invalid_argument& error) {
    write_error(error.what(), error_buffer, error_buffer_size);
    return 1;
  } catch (const std::exception& error) {
    write_error(error.what(), error_buffer, error_buffer_size);
    return 2;
  } catch (...) {
    write_error("unknown scheduler error", error_buffer, error_buffer_size);
    return 3;
  }
}

}  // extern "C"
