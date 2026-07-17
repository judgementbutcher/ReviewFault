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

#define RF_SCHEDULER_ABI_VERSION 2u

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

#ifdef __cplusplus
}
#endif

#endif
