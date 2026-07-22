#ifndef REVIEWFAULT_REVIEWFAULT_C_H
#define REVIEWFAULT_REVIEWFAULT_C_H

#include <stddef.h>
#include <stdint.h>

#ifdef _WIN32
#ifdef REVIEWFAULT_BUILD_SHARED
#define RF_API __declspec(dllexport)
#else
#define RF_API
#endif
#else
#define RF_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define RF_SCHEDULER_ABI_VERSION 5u

typedef enum rf_rating {
  RF_RATING_AGAIN = 1,
  RF_RATING_HARD = 2,
  RF_RATING_GOOD = 3,
  RF_RATING_EASY = 4
} rf_rating;

typedef enum rf_card_state {
  RF_CARD_NEW = 0,
  RF_CARD_LEARNING = 1,
  RF_CARD_REVIEW = 2,
  RF_CARD_RELEARNING = 3
} rf_card_state;

typedef struct rf_scheduler_config {
  uint32_t struct_size;
  double target_retention;
  double maximum_interval_days;
  double minimum_review_interval_days;
  int64_t again_step_seconds;
  int64_t hard_step_seconds;
} rf_scheduler_config;

typedef struct rf_card {
  uint32_t struct_size;
  int32_t state;
  double difficulty;
  double stability_days;
  int64_t due_at;
  int64_t last_reviewed_at;
  uint32_t repetitions;
  uint32_t lapses;
} rf_card;

typedef struct rf_review_log {
  uint32_t struct_size;
  int32_t rating;
  int32_t state_before;
  int32_t state_after;
  int64_t reviewed_at;
  double elapsed_days;
  double scheduled_days;
  double retrievability_before;
  double difficulty_before;
  double difficulty_after;
  double stability_before;
  double stability_after;
} rf_review_log;

typedef struct rf_review_result {
  uint32_t struct_size;
  rf_card card;
  rf_review_log log;
} rf_review_result;

RF_API uint32_t rf_scheduler_abi_version(void);
RF_API size_t rf_scheduler_config_size(void);
RF_API size_t rf_card_size(void);
RF_API size_t rf_review_result_size(void);
RF_API rf_scheduler_config rf_default_scheduler_config(void);
RF_API rf_card rf_new_card(void);

/* Returns 0 on success. On failure, returns a stable non-zero error code and
 * writes a NUL-terminated diagnostic when error_buffer is available. */
RF_API int32_t rf_review(const rf_scheduler_config* config,
                         const rf_card* card,
                         int32_t rating,
                         int64_t reviewed_at,
                         rf_review_result* result,
                         char* error_buffer,
                         size_t error_buffer_size);

/* ABI v2 keeps the v1 entry points above for history replay, but new reviews
 * use a type-specific state and result. Every structure is size-versioned so
 * an old application fails safely instead of reading a changed layout. */
typedef enum rf_memory_preset_v2 {
  RF_MEMORY_TIME_SAVING = 0,
  RF_MEMORY_BALANCED = 1,
  RF_MEMORY_REINFORCED = 2
} rf_memory_preset_v2;

typedef enum rf_math_intensity_v2 {
  RF_MATH_INTENSIVE = 0,
  RF_MATH_BALANCED = 1,
  RF_MATH_RELAXED = 2
} rf_math_intensity_v2;

typedef enum rf_math_feedback_v2 {
  RF_MATH_CANNOT_START = 0,
  RF_MATH_INCORRECT = 1,
  RF_MATH_EFFORTFUL_CORRECT = 2,
  RF_MATH_FLUENT_CORRECT = 3
} rf_math_feedback_v2;

typedef enum rf_math_error_reason_v2 {
  RF_MATH_ERROR_NONE = 0,
  RF_MATH_ERROR_CONCEPT = 1,
  RF_MATH_ERROR_APPROACH = 2,
  RF_MATH_ERROR_CALCULATION = 3,
  RF_MATH_ERROR_MISREAD = 4,
  RF_MATH_ERROR_FORGOTTEN_FACT = 5,
  RF_MATH_ERROR_TIMEOUT = 6,
  RF_MATH_ERROR_OTHER = 7
} rf_math_error_reason_v2;

typedef struct rf_memory_schedule_state_v2 {
  uint32_t struct_size;
  int32_t state;
  double difficulty;
  double stability_days;
  int64_t due_at;
  int64_t last_reviewed_at;
  uint32_t repetitions;
  uint32_t lapses;
} rf_memory_schedule_state_v2;

typedef struct rf_memory_review_input_v2 {
  uint32_t struct_size;
  int32_t rating;
  int32_t preset;
  int64_t reviewed_at;
} rf_memory_review_input_v2;

typedef struct rf_memory_review_event_v2 {
  uint32_t struct_size;
  uint32_t algorithm_version;
  uint32_t parameter_version;
  int32_t rating;
  int32_t preset;
  int32_t state_before;
  int32_t state_after;
  int64_t reviewed_at;
  int64_t due_at_before;
  int64_t due_at_after;
  double target_retention;
  double elapsed_days;
  double scheduled_days;
  double retrievability_before;
  double difficulty_before;
  double difficulty_after;
  double stability_before;
  double stability_after;
} rf_memory_review_event_v2;

typedef struct rf_memory_review_result_v2 {
  uint32_t struct_size;
  rf_memory_schedule_state_v2 state;
  rf_memory_review_event_v2 event;
} rf_memory_review_result_v2;

typedef struct rf_math_schedule_state_v2 {
  uint32_t struct_size;
  uint32_t mastery_level;
  uint32_t fluent_streak;
  int64_t due_at;
  int64_t last_reviewed_at;
  uint32_t repetitions;
} rf_math_schedule_state_v2;

typedef struct rf_math_attempt_input_v2 {
  uint32_t struct_size;
  int32_t feedback;
  int32_t error_reason;
  int32_t hint_revealed;
  int32_t intensity;
  int64_t reviewed_at;
} rf_math_attempt_input_v2;

typedef struct rf_math_review_event_v2 {
  uint32_t struct_size;
  uint32_t algorithm_version;
  uint32_t parameter_version;
  int32_t requested_feedback;
  int32_t applied_feedback;
  int32_t error_reason;
  int32_t intensity;
  int32_t hint_revealed;
  uint32_t mastery_before;
  uint32_t mastery_after;
  uint32_t fluent_streak_before;
  uint32_t fluent_streak_after;
  int64_t reviewed_at;
  int64_t due_at_before;
  int64_t due_at_after;
  double scheduled_days;
} rf_math_review_event_v2;

typedef struct rf_math_review_result_v2 {
  uint32_t struct_size;
  rf_math_schedule_state_v2 state;
  rf_math_review_event_v2 event;
} rf_math_review_result_v2;

RF_API size_t rf_memory_schedule_state_v2_size(void);
RF_API size_t rf_memory_review_result_v2_size(void);
RF_API size_t rf_math_schedule_state_v2_size(void);
RF_API size_t rf_math_review_result_v2_size(void);
RF_API rf_memory_schedule_state_v2 rf_new_memory_state_v2(void);
RF_API rf_math_schedule_state_v2 rf_new_math_state_v2(void);

RF_API int32_t rf_review_memory_v2(const rf_memory_schedule_state_v2* state,
                                   const rf_memory_review_input_v2* input,
                                   rf_memory_review_result_v2* result,
                                   char* error_buffer,
                                   size_t error_buffer_size);
RF_API int32_t rf_review_math_v2(const rf_math_schedule_state_v2* state,
                                 const rf_math_attempt_input_v2* input,
                                 rf_math_review_result_v2* result,
                                 char* error_buffer,
                                 size_t error_buffer_size);

/* Unprefixed names are specified by the cross-platform v2 contract. */
RF_API int32_t review_memory_v2(const rf_memory_schedule_state_v2* state,
                                const rf_memory_review_input_v2* input,
                                rf_memory_review_result_v2* result,
                                char* error_buffer,
                                size_t error_buffer_size);
RF_API int32_t review_math_v2(const rf_math_schedule_state_v2* state,
                              const rf_math_attempt_input_v2* input,
                              rf_math_review_result_v2* result,
                              char* error_buffer,
                              size_t error_buffer_size);

/* ABI v3 adds explicit rollout inputs and decision metadata. ABI v1/v2 entry
 * points remain exported for deterministic history replay. */
typedef enum rf_duration_quality_v3 {
  RF_DURATION_UNKNOWN = 0,
  RF_DURATION_RELIABLE = 1,
  RF_DURATION_TOO_SHORT = 2,
  RF_DURATION_INTERRUPTED = 3
} rf_duration_quality_v3;

typedef struct rf_memory_review_input_v3 {
  uint32_t struct_size;
  int32_t rating;
  int32_t preset;
  int64_t reviewed_at;
  uint32_t history_event_count;
  double calibration_improvement;
  uint32_t consecutive_lapses;
} rf_memory_review_input_v3;

typedef struct rf_memory_review_event_v3 {
  uint32_t struct_size;
  uint32_t algorithm_version;
  uint32_t parameter_version;
  uint32_t decision_flags;
  int32_t personalized;
  int32_t learning_step;
  int64_t due_at_after;
  double target_retention;
  double elapsed_days;
  double scheduled_days;
  double retrievability_before;
  double overdue_days;
} rf_memory_review_event_v3;

typedef struct rf_memory_review_result_v3 {
  uint32_t struct_size;
  rf_memory_schedule_state_v2 state;
  rf_memory_review_event_v3 event;
} rf_memory_review_result_v3;

typedef struct rf_math_attempt_input_v3 {
  uint32_t struct_size;
  int32_t feedback;
  int32_t error_reason;
  int32_t hint_revealed;
  int32_t intensity;
  int64_t reviewed_at;
  uint32_t duration_seconds;
  int32_t duration_quality;
  uint32_t consecutive_failures;
} rf_math_attempt_input_v3;

typedef struct rf_math_review_event_v3 {
  uint32_t struct_size;
  uint32_t algorithm_version;
  uint32_t parameter_version;
  uint32_t decision_flags;
  int32_t requested_feedback;
  int32_t applied_feedback;
  int64_t due_at_after;
  double scheduled_days;
  uint32_t duration_seconds;
  int32_t duration_quality;
  uint32_t consecutive_failures;
} rf_math_review_event_v3;

typedef struct rf_math_review_result_v3 {
  uint32_t struct_size;
  rf_math_schedule_state_v2 state;
  rf_math_review_event_v3 event;
} rf_math_review_result_v3;

RF_API size_t rf_memory_review_result_v3_size(void);
RF_API size_t rf_math_review_result_v3_size(void);
RF_API int32_t review_memory_v3(const rf_memory_schedule_state_v2* state,
                                const rf_memory_review_input_v3* input,
                                rf_memory_review_result_v3* result,
                                char* error_buffer,
                                size_t error_buffer_size);
RF_API int32_t review_math_v3(const rf_math_schedule_state_v2* state,
                              const rf_math_attempt_input_v3* input,
                              rf_math_review_result_v3* result,
                              char* error_buffer,
                              size_t error_buffer_size);

/* ABI v4 canonicalizes immutable facts before replaying the frozen v3
 * scheduler. Strings are borrowed for the duration of each call. */
typedef struct rf_review_action_v4 {
  uint32_t struct_size;
  const char* action_id;
  const char* device_id;
  uint64_t device_counter;
  uint64_t causal_cursor;
  int32_t feedback;
  int64_t reviewed_at;
  uint32_t duration_seconds;
  int32_t error_reason;
  int32_t hint_revealed;
} rf_review_action_v4;

typedef struct rf_memory_replay_config_v4 {
  uint32_t struct_size;
  int32_t preset;
  double calibration_improvement;
} rf_memory_replay_config_v4;

typedef struct rf_math_replay_config_v4 {
  uint32_t struct_size;
  int32_t intensity;
} rf_math_replay_config_v4;

typedef struct rf_memory_replay_result_v4 {
  uint32_t struct_size;
  rf_memory_schedule_state_v2 state;
  uint64_t action_count;
} rf_memory_replay_result_v4;

typedef struct rf_math_replay_result_v4 {
  uint32_t struct_size;
  rf_math_schedule_state_v2 state;
  uint64_t action_count;
} rf_math_replay_result_v4;

RF_API size_t rf_review_action_v4_size(void);
RF_API size_t rf_memory_replay_result_v4_size(void);
RF_API size_t rf_math_replay_result_v4_size(void);
RF_API int32_t canonical_review_order_v4(
    const rf_review_action_v4* actions, size_t action_count,
    size_t* output_indices, size_t output_count,
    char* error_buffer, size_t error_buffer_size);
RF_API int32_t replay_memory_history_v4(
    const rf_memory_schedule_state_v2* initial_state,
    const rf_review_action_v4* actions, size_t action_count,
    const rf_memory_replay_config_v4* config,
    rf_memory_replay_result_v4* result,
    char* error_buffer, size_t error_buffer_size);
RF_API int32_t replay_math_history_v4(
    const rf_math_schedule_state_v2* initial_state,
    const rf_review_action_v4* actions, size_t action_count,
    const rf_math_replay_config_v4* config,
    rf_math_replay_result_v4* result,
    char* error_buffer, size_t error_buffer_size);

/* ABI v5 schedules evidence-backed learning tasks.  Existing v1-v4 calls are
 * intentionally retained for legacy-history replay.  Error reasons are a bit
 * set (RF_MATH_ERROR_MASK_*), allowing one attempt to record several causes. */
typedef enum rf_learning_task_type_v5 {
  RF_TASK_MATH_REPAIR = 0, RF_TASK_MATH_ORIGINAL = 1,
  RF_TASK_MATH_VARIANT = 2, RF_TASK_MATH_TRANSFER = 3,
  RF_TASK_MATH_RETENTION = 4, RF_TASK_MEMORY_RECALL = 5,
  RF_TASK_MEMORY_EXPLAIN = 6, RF_TASK_MEMORY_COMPARE = 7,
  RF_TASK_MEMORY_DIAGRAM = 8, RF_TASK_MEMORY_CALCULATE = 9
} rf_learning_task_type_v5;

typedef enum rf_math_task_phase_v5 {
  RF_MATH_PHASE_REPAIR = 0, RF_MATH_PHASE_ORIGINAL = 1,
  RF_MATH_PHASE_VARIANT = 2, RF_MATH_PHASE_TRANSFER = 3,
  RF_MATH_PHASE_RETENTION = 4, RF_MATH_PHASE_AWAITING_VARIANT = 5,
  RF_MATH_PHASE_GRADUATED = 6
} rf_math_task_phase_v5;

#define RF_MATH_ERROR_MASK_NONE 0u
#define RF_MATH_ERROR_MASK_CONCEPT (1u << 0)
#define RF_MATH_ERROR_MASK_APPROACH (1u << 1)
#define RF_MATH_ERROR_MASK_CALCULATION (1u << 2)
#define RF_MATH_ERROR_MASK_MISREAD (1u << 3)
#define RF_MATH_ERROR_MASK_FORGOTTEN_FACT (1u << 4)
#define RF_MATH_ERROR_MASK_TIMEOUT (1u << 5)
#define RF_MATH_ERROR_MASK_OTHER (1u << 6)

typedef struct rf_memory_task_review_input_v5 {
  uint32_t struct_size;
  rf_memory_schedule_state_v2 state;
  int32_t preset;
  int64_t reviewed_at;
  uint32_t point_hits;
  uint32_t point_count;
  uint32_t hint_level;
  int32_t answer_revealed;
  int32_t duration_reliable;
  uint32_t duration_seconds;
  uint32_t confidence;
  uint32_t history_event_count;
  double calibration_improvement;
  uint32_t consecutive_lapses;
} rf_memory_task_review_input_v5;

typedef struct rf_memory_task_review_result_v5 {
  uint32_t struct_size;
  rf_memory_review_result_v3 review;
  int32_t effective_rating;
  double point_coverage;
  int32_t rating_capped_by_help;
} rf_memory_task_review_result_v5;

typedef struct rf_math_task_state_v5 {
  uint32_t struct_size;
  int32_t phase;
  int64_t due_at;
  int64_t last_reviewed_at;
  uint32_t repetitions;
  uint32_t consecutive_failures;
  int32_t original_verified;
  int32_t variant_verified;
  int32_t transfer_verified;
  int32_t speed_verified;
} rf_math_task_state_v5;

typedef struct rf_math_task_review_input_v5 {
  uint32_t struct_size;
  rf_math_task_state_v5 state;
  int64_t reviewed_at;
  int32_t correct;
  int32_t hint_revealed;
  int32_t speed_target_met;
  int32_t variant_available;
  uint32_t error_mask;
  uint32_t duration_seconds;
  uint32_t confidence;
} rf_math_task_review_input_v5;

typedef struct rf_math_task_review_result_v5 {
  uint32_t struct_size;
  rf_math_task_state_v5 state;
  int32_t phase_before;
  int32_t advanced;
  int32_t regressed;
  uint32_t repair_mask;
} rf_math_task_review_result_v5;

typedef struct rf_learning_profile_v5 {
  uint32_t struct_size;
  int64_t exam_at;
  uint32_t daily_available_minutes;
  uint8_t study_days_mask;
  uint8_t math_percent;
  double target_retention;
} rf_learning_profile_v5;

typedef struct rf_session_task_v5 {
  uint32_t struct_size;
  const char* id;
  const char* unit_id;
  const char* subject;
  const char* chapter_id;
  int32_t type;
  int64_t due_at;
  uint32_t estimated_seconds;
  uint32_t consecutive_failures;
  int32_t dependency_ready;
  int32_t is_new;
  int32_t confusable;
  uint32_t remaining_validations;
} rf_session_task_v5;

typedef struct rf_planned_task_v5 {
  uint32_t struct_size;
  size_t input_index;
  int32_t section;
} rf_planned_task_v5;

typedef struct rf_session_plan_summary_v5 {
  uint32_t struct_size;
  uint32_t estimated_seconds;
  uint32_t review_debt_seconds;
  uint32_t omitted_new_count;
  int32_t new_learning_blocked;
  size_t task_count;
} rf_session_plan_summary_v5;

RF_API size_t rf_memory_task_review_result_v5_size(void);
RF_API size_t rf_math_task_review_result_v5_size(void);
RF_API size_t rf_session_plan_summary_v5_size(void);
RF_API int32_t review_memory_task_v5(const rf_memory_task_review_input_v5* input,
                                      rf_memory_task_review_result_v5* result,
                                      char* error_buffer, size_t error_buffer_size);
RF_API int32_t review_math_task_v5(const rf_math_task_review_input_v5* input,
                                    rf_math_task_review_result_v5* result,
                                    char* error_buffer, size_t error_buffer_size);
RF_API uint32_t remaining_exam_validations_v5(int64_t now, int64_t exam_at);
RF_API int32_t plan_session_v5(const rf_session_task_v5* tasks, size_t task_count,
                               const rf_learning_profile_v5* profile, int64_t now,
                               uint32_t available_seconds, rf_planned_task_v5* output_tasks,
                               size_t output_count, rf_session_plan_summary_v5* summary,
                               char* error_buffer, size_t error_buffer_size);

#ifdef __cplusplus
}
#endif

#endif
