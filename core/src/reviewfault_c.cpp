#include "reviewfault/reviewfault_c.h"

#include "reviewfault/scheduler.hpp"
#include "reviewfault/scheduler_v2.hpp"
#include "reviewfault/scheduler_v3.hpp"

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

rf_memory_schedule_state_v2 to_c_memory_state(
    const reviewfault::MemoryScheduleState& state) {
  return {sizeof(rf_memory_schedule_state_v2),
          static_cast<int32_t>(state.state),
          state.difficulty,
          state.stability_days,
          state.due_at,
          state.last_reviewed_at,
          state.repetitions,
          state.lapses};
}

reviewfault::MemoryScheduleState to_cpp_memory_state(
    const rf_memory_schedule_state_v2& state) {
  return {static_cast<reviewfault::CardState>(state.state),
          state.difficulty,
          state.stability_days,
          state.due_at,
          state.last_reviewed_at,
          state.repetitions,
          state.lapses};
}

rf_memory_review_event_v2 to_c_memory_event(
    const reviewfault::MemoryReviewEvent& event) {
  return {sizeof(rf_memory_review_event_v2),
          event.algorithm_version,
          event.parameter_version,
          static_cast<int32_t>(event.rating),
          static_cast<int32_t>(event.preset),
          static_cast<int32_t>(event.state_before),
          static_cast<int32_t>(event.state_after),
          event.reviewed_at,
          event.due_at_before,
          event.due_at_after,
          event.target_retention,
          event.elapsed_days,
          event.scheduled_days,
          event.retrievability_before,
          event.difficulty_before,
          event.difficulty_after,
          event.stability_before,
          event.stability_after};
}

rf_math_schedule_state_v2 to_c_math_state(
    const reviewfault::MathScheduleState& state) {
  return {sizeof(rf_math_schedule_state_v2),
          state.mastery_level,
          state.fluent_streak,
          state.due_at,
          state.last_reviewed_at,
          state.repetitions};
}

reviewfault::MathScheduleState to_cpp_math_state(
    const rf_math_schedule_state_v2& state) {
  return {state.mastery_level, state.fluent_streak, state.due_at,
          state.last_reviewed_at, state.repetitions};
}

rf_math_review_event_v2 to_c_math_event(
    const reviewfault::MathReviewEvent& event) {
  return {sizeof(rf_math_review_event_v2),
          event.algorithm_version,
          event.parameter_version,
          static_cast<int32_t>(event.requested_feedback),
          static_cast<int32_t>(event.applied_feedback),
          static_cast<int32_t>(event.error_reason),
          static_cast<int32_t>(event.intensity),
          event.hint_revealed ? 1 : 0,
          event.mastery_before,
          event.mastery_after,
          event.fluent_streak_before,
          event.fluent_streak_after,
          event.reviewed_at,
          event.due_at_before,
          event.due_at_after,
          event.scheduled_days};
}

rf_memory_review_event_v3 to_c_memory_event_v3(
    const reviewfault::MemoryReviewEventV3& event) {
  return {sizeof(rf_memory_review_event_v3), event.algorithm_version,
          event.parameter_version, event.decision_flags,
          event.personalized ? 1 : 0, event.learning_step ? 1 : 0,
          event.due_at_after, event.target_retention, event.elapsed_days,
          event.scheduled_days, event.retrievability_before, event.overdue_days};
}

rf_math_review_event_v3 to_c_math_event_v3(
    const reviewfault::MathReviewEventV3& event) {
  return {sizeof(rf_math_review_event_v3), event.algorithm_version,
          event.parameter_version, event.decision_flags,
          static_cast<int32_t>(event.requested_feedback),
          static_cast<int32_t>(event.applied_feedback), event.due_at_after,
          event.scheduled_days, event.duration_seconds,
          static_cast<int32_t>(event.duration_quality), event.consecutive_failures};
}

template <typename Callable>
int32_t invoke_v2(Callable callable, char* error_buffer, size_t error_buffer_size) {
  try {
    callable();
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

size_t rf_memory_schedule_state_v2_size(void) {
  return sizeof(rf_memory_schedule_state_v2);
}

size_t rf_memory_review_result_v2_size(void) {
  return sizeof(rf_memory_review_result_v2);
}

size_t rf_math_schedule_state_v2_size(void) {
  return sizeof(rf_math_schedule_state_v2);
}

size_t rf_math_review_result_v2_size(void) {
  return sizeof(rf_math_review_result_v2);
}

rf_memory_schedule_state_v2 rf_new_memory_state_v2(void) {
  return to_c_memory_state(reviewfault::MemoryScheduleState{});
}

rf_math_schedule_state_v2 rf_new_math_state_v2(void) {
  return to_c_math_state(reviewfault::MathScheduleState{});
}

int32_t rf_review_memory_v2(const rf_memory_schedule_state_v2* state,
                            const rf_memory_review_input_v2* input,
                            rf_memory_review_result_v2* result,
                            char* error_buffer,
                            size_t error_buffer_size) {
  return invoke_v2([&] {
    if (state == nullptr || input == nullptr || result == nullptr) {
      throw std::invalid_argument("state, input, and result are required");
    }
    if (state->struct_size != sizeof(*state) || input->struct_size != sizeof(*input) ||
        result->struct_size != sizeof(*result)) {
      throw std::invalid_argument("ABI v2 structure size mismatch");
    }
    const auto reviewed = reviewfault::review_memory_v2(
        to_cpp_memory_state(*state),
        {static_cast<reviewfault::Rating>(input->rating), input->reviewed_at,
         static_cast<reviewfault::MemoryPreset>(input->preset)});
    result->state = to_c_memory_state(reviewed.state);
    result->event = to_c_memory_event(reviewed.event);
  }, error_buffer, error_buffer_size);
}

int32_t rf_review_math_v2(const rf_math_schedule_state_v2* state,
                          const rf_math_attempt_input_v2* input,
                          rf_math_review_result_v2* result,
                          char* error_buffer,
                          size_t error_buffer_size) {
  return invoke_v2([&] {
    if (state == nullptr || input == nullptr || result == nullptr) {
      throw std::invalid_argument("state, input, and result are required");
    }
    if (state->struct_size != sizeof(*state) || input->struct_size != sizeof(*input) ||
        result->struct_size != sizeof(*result)) {
      throw std::invalid_argument("ABI v2 structure size mismatch");
    }
    const auto reviewed = reviewfault::review_math_v2(
        to_cpp_math_state(*state),
        {static_cast<reviewfault::MathFeedback>(input->feedback),
         static_cast<reviewfault::MathErrorReason>(input->error_reason),
         input->hint_revealed != 0, input->reviewed_at,
         static_cast<reviewfault::MathIntensity>(input->intensity)});
    result->state = to_c_math_state(reviewed.state);
    result->event = to_c_math_event(reviewed.event);
  }, error_buffer, error_buffer_size);
}

int32_t review_memory_v2(const rf_memory_schedule_state_v2* state,
                         const rf_memory_review_input_v2* input,
                         rf_memory_review_result_v2* result,
                         char* error_buffer,
                         size_t error_buffer_size) {
  return rf_review_memory_v2(state, input, result, error_buffer, error_buffer_size);
}

int32_t review_math_v2(const rf_math_schedule_state_v2* state,
                       const rf_math_attempt_input_v2* input,
                       rf_math_review_result_v2* result,
                       char* error_buffer,
                       size_t error_buffer_size) {
  return rf_review_math_v2(state, input, result, error_buffer, error_buffer_size);
}

size_t rf_memory_review_result_v3_size(void) {
  return sizeof(rf_memory_review_result_v3);
}

size_t rf_math_review_result_v3_size(void) {
  return sizeof(rf_math_review_result_v3);
}

int32_t review_memory_v3(const rf_memory_schedule_state_v2* state,
                         const rf_memory_review_input_v3* input,
                         rf_memory_review_result_v3* result,
                         char* error_buffer,
                         size_t error_buffer_size) {
  return invoke_v2([&] {
    if (state == nullptr || input == nullptr || result == nullptr) {
      throw std::invalid_argument("state, input, and result are required");
    }
    if (state->struct_size != sizeof(*state) || input->struct_size != sizeof(*input) ||
        result->struct_size != sizeof(*result)) {
      throw std::invalid_argument("ABI v3 structure size mismatch");
    }
    const auto reviewed = reviewfault::review_memory_v3(
        to_cpp_memory_state(*state),
        {static_cast<reviewfault::Rating>(input->rating), input->reviewed_at,
         static_cast<reviewfault::MemoryPreset>(input->preset),
         input->history_event_count, input->calibration_improvement,
         input->consecutive_lapses});
    result->state = to_c_memory_state(reviewed.state);
    result->event = to_c_memory_event_v3(reviewed.event);
  }, error_buffer, error_buffer_size);
}

int32_t review_math_v3(const rf_math_schedule_state_v2* state,
                       const rf_math_attempt_input_v3* input,
                       rf_math_review_result_v3* result,
                       char* error_buffer,
                       size_t error_buffer_size) {
  return invoke_v2([&] {
    if (state == nullptr || input == nullptr || result == nullptr) {
      throw std::invalid_argument("state, input, and result are required");
    }
    if (state->struct_size != sizeof(*state) || input->struct_size != sizeof(*input) ||
        result->struct_size != sizeof(*result)) {
      throw std::invalid_argument("ABI v3 structure size mismatch");
    }
    const auto reviewed = reviewfault::review_math_v3(
        to_cpp_math_state(*state),
        {static_cast<reviewfault::MathFeedback>(input->feedback),
         static_cast<reviewfault::MathErrorReason>(input->error_reason),
         input->hint_revealed != 0, input->reviewed_at,
         static_cast<reviewfault::MathIntensity>(input->intensity),
         input->duration_seconds,
         static_cast<reviewfault::DurationQualityV3>(input->duration_quality),
         input->consecutive_failures});
    result->state = to_c_math_state(reviewed.state);
    result->event = to_c_math_event_v3(reviewed.event);
  }, error_buffer, error_buffer_size);
}

}  // extern "C"
