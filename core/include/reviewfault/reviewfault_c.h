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

#define RF_SCHEDULER_ABI_VERSION 1u

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

#ifdef __cplusplus
}
#endif

#endif
