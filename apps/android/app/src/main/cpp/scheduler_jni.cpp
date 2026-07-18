#include <jni.h>

#include "reviewfault/reviewfault_c.h"

#include <cstdint>

namespace {

void throw_illegal_argument(JNIEnv* env, const char* message) {
  jclass type = env->FindClass("java/lang/IllegalArgumentException");
  if (type != nullptr) {
    env->ThrowNew(type, message);
  }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_cn_reviewfault_app_core_NativeScheduler_nativeAbiVersion(JNIEnv*, jobject) {
  return static_cast<jint>(rf_scheduler_abi_version());
}

extern "C" JNIEXPORT jobject JNICALL
Java_cn_reviewfault_app_core_NativeScheduler_nativeReview(
    JNIEnv* env,
    jobject,
    jint state,
    jdouble difficulty,
    jdouble stability_days,
    jlong due_at,
    jlong last_reviewed_at,
    jint repetitions,
    jint lapses,
    jint rating,
    jlong reviewed_at,
    jdouble target_retention) {
  rf_scheduler_config config = rf_default_scheduler_config();
  config.target_retention = target_retention;
  rf_card card = rf_new_card();
  card.state = state;
  card.difficulty = difficulty;
  card.stability_days = stability_days;
  card.due_at = static_cast<int64_t>(due_at);
  card.last_reviewed_at = static_cast<int64_t>(last_reviewed_at);
  card.repetitions = static_cast<uint32_t>(repetitions);
  card.lapses = static_cast<uint32_t>(lapses);
  rf_review_result result{};
  result.struct_size = sizeof(result);
  char error[256]{};
  const int32_t status = rf_review(&config, &card, rating, reviewed_at, &result,
                                   error, sizeof(error));
  if (status != 0) {
    throw_illegal_argument(env, error);
    return nullptr;
  }

  jclass result_class =
      env->FindClass("cn/reviewfault/app/core/NativeScheduleResult");
  if (result_class == nullptr) {
    return nullptr;
  }
  jmethodID constructor = env->GetMethodID(result_class, "<init>", "(IDDJIIDD)V");
  if (constructor == nullptr) {
    return nullptr;
  }
  return env->NewObject(
      result_class, constructor, static_cast<jint>(result.card.state),
      static_cast<jdouble>(result.card.difficulty),
      static_cast<jdouble>(result.card.stability_days),
      static_cast<jlong>(result.card.due_at),
      static_cast<jint>(result.card.repetitions), static_cast<jint>(result.card.lapses),
      static_cast<jdouble>(result.log.scheduled_days),
      static_cast<jdouble>(result.log.retrievability_before));
}

extern "C" JNIEXPORT jobject JNICALL
Java_cn_reviewfault_app_core_NativeScheduler_nativeReviewMemoryV2(
    JNIEnv* env, jobject, jint state, jdouble difficulty, jdouble stability_days,
    jlong due_at, jlong last_reviewed_at, jint repetitions, jint lapses,
    jint rating, jlong reviewed_at, jint preset) {
  rf_memory_schedule_state_v2 memory = rf_new_memory_state_v2();
  memory.state = state;
  memory.difficulty = difficulty;
  memory.stability_days = stability_days;
  memory.due_at = due_at;
  memory.last_reviewed_at = last_reviewed_at;
  memory.repetitions = static_cast<uint32_t>(repetitions);
  memory.lapses = static_cast<uint32_t>(lapses);
  rf_memory_review_input_v2 input{sizeof(input), rating, preset, reviewed_at};
  rf_memory_review_result_v2 result{};
  result.struct_size = sizeof(result);
  char error[256]{};
  if (review_memory_v2(&memory, &input, &result, error, sizeof(error)) != 0) {
    throw_illegal_argument(env, error);
    return nullptr;
  }
  jclass result_class = env->FindClass("cn/reviewfault/app/core/NativeScheduleResult");
  if (result_class == nullptr) return nullptr;
  jmethodID constructor = env->GetMethodID(result_class, "<init>", "(IDDJIIDD)V");
  if (constructor == nullptr) return nullptr;
  return env->NewObject(
      result_class, constructor, static_cast<jint>(result.state.state),
      result.state.difficulty, result.state.stability_days,
      static_cast<jlong>(result.state.due_at),
      static_cast<jint>(result.state.repetitions),
      static_cast<jint>(result.state.lapses), result.event.scheduled_days,
      result.event.retrievability_before);
}

extern "C" JNIEXPORT jobject JNICALL
Java_cn_reviewfault_app_core_NativeScheduler_nativeReviewMathV2(
    JNIEnv* env, jobject, jint mastery_level, jint fluent_streak, jlong due_at,
    jlong last_reviewed_at, jint repetitions, jint feedback, jint error_reason,
    jboolean hint_revealed, jlong reviewed_at, jint intensity) {
  rf_math_schedule_state_v2 state = rf_new_math_state_v2();
  state.mastery_level = static_cast<uint32_t>(mastery_level);
  state.fluent_streak = static_cast<uint32_t>(fluent_streak);
  state.due_at = due_at;
  state.last_reviewed_at = last_reviewed_at;
  state.repetitions = static_cast<uint32_t>(repetitions);
  rf_math_attempt_input_v2 input{sizeof(input), feedback, error_reason,
                                 hint_revealed ? 1 : 0, intensity, reviewed_at};
  rf_math_review_result_v2 result{};
  result.struct_size = sizeof(result);
  char error[256]{};
  if (review_math_v2(&state, &input, &result, error, sizeof(error)) != 0) {
    throw_illegal_argument(env, error);
    return nullptr;
  }
  jclass result_class =
      env->FindClass("cn/reviewfault/app/core/NativeMathScheduleResult");
  if (result_class == nullptr) return nullptr;
  jmethodID constructor = env->GetMethodID(result_class, "<init>", "(IIJIDI)V");
  if (constructor == nullptr) return nullptr;
  return env->NewObject(result_class, constructor,
                        static_cast<jint>(result.state.mastery_level),
                        static_cast<jint>(result.state.fluent_streak),
                        static_cast<jlong>(result.state.due_at),
                        static_cast<jint>(result.state.repetitions),
                        result.event.scheduled_days,
                        static_cast<jint>(result.event.applied_feedback));
}

extern "C" JNIEXPORT jobject JNICALL
Java_cn_reviewfault_app_core_NativeScheduler_nativeReviewMemoryV3(
    JNIEnv* env, jobject, jint state, jdouble difficulty, jdouble stability_days,
    jlong due_at, jlong last_reviewed_at, jint repetitions, jint lapses,
    jint rating, jlong reviewed_at, jint preset, jint history_event_count,
    jdouble calibration_improvement, jint consecutive_lapses) {
  rf_memory_schedule_state_v2 memory = rf_new_memory_state_v2();
  memory.state = state;
  memory.difficulty = difficulty;
  memory.stability_days = stability_days;
  memory.due_at = due_at;
  memory.last_reviewed_at = last_reviewed_at;
  memory.repetitions = static_cast<uint32_t>(repetitions);
  memory.lapses = static_cast<uint32_t>(lapses);
  rf_memory_review_input_v3 input{sizeof(input), rating, preset, reviewed_at,
                                   static_cast<uint32_t>(history_event_count),
                                   calibration_improvement,
                                   static_cast<uint32_t>(consecutive_lapses)};
  rf_memory_review_result_v3 result{};
  result.struct_size = sizeof(result);
  char error[256]{};
  if (review_memory_v3(&memory, &input, &result, error, sizeof(error)) != 0) {
    throw_illegal_argument(env, error);
    return nullptr;
  }
  jclass result_class = env->FindClass(
      "cn/reviewfault/app/core/NativeScheduleResultV3");
  if (result_class == nullptr) return nullptr;
  jmethodID constructor = env->GetMethodID(result_class, "<init>",
                                            "(IDDJIIDDIIIDZZD)V");
  if (constructor == nullptr) return nullptr;
  return env->NewObject(
      result_class, constructor, static_cast<jint>(result.state.state),
      result.state.difficulty, result.state.stability_days,
      static_cast<jlong>(result.state.due_at),
      static_cast<jint>(result.state.repetitions),
      static_cast<jint>(result.state.lapses), result.event.scheduled_days,
      result.event.retrievability_before,
      static_cast<jint>(result.event.algorithm_version),
      static_cast<jint>(result.event.parameter_version),
      static_cast<jint>(result.event.decision_flags), result.event.target_retention,
      result.event.personalized != 0, result.event.learning_step != 0,
      result.event.overdue_days);
}

extern "C" JNIEXPORT jobject JNICALL
Java_cn_reviewfault_app_core_NativeScheduler_nativeReviewMathV3(
    JNIEnv* env, jobject, jint mastery_level, jint fluent_streak, jlong due_at,
    jlong last_reviewed_at, jint repetitions, jint feedback, jint error_reason,
    jboolean hint_revealed, jlong reviewed_at, jint intensity,
    jint duration_seconds, jint duration_quality, jint consecutive_failures) {
  rf_math_schedule_state_v2 state = rf_new_math_state_v2();
  state.mastery_level = static_cast<uint32_t>(mastery_level);
  state.fluent_streak = static_cast<uint32_t>(fluent_streak);
  state.due_at = due_at;
  state.last_reviewed_at = last_reviewed_at;
  state.repetitions = static_cast<uint32_t>(repetitions);
  rf_math_attempt_input_v3 input{sizeof(input), feedback, error_reason,
                                  hint_revealed ? 1 : 0, intensity, reviewed_at,
                                  static_cast<uint32_t>(duration_seconds),
                                  duration_quality,
                                  static_cast<uint32_t>(consecutive_failures)};
  rf_math_review_result_v3 result{};
  result.struct_size = sizeof(result);
  char error[256]{};
  if (review_math_v3(&state, &input, &result, error, sizeof(error)) != 0) {
    throw_illegal_argument(env, error);
    return nullptr;
  }
  jclass result_class = env->FindClass(
      "cn/reviewfault/app/core/NativeMathScheduleResultV3");
  if (result_class == nullptr) return nullptr;
  jmethodID constructor = env->GetMethodID(result_class, "<init>", "(IIJIDIIII)V");
  if (constructor == nullptr) return nullptr;
  return env->NewObject(result_class, constructor,
                        static_cast<jint>(result.state.mastery_level),
                        static_cast<jint>(result.state.fluent_streak),
                        static_cast<jlong>(result.state.due_at),
                        static_cast<jint>(result.state.repetitions),
                        result.event.scheduled_days,
                        static_cast<jint>(result.event.applied_feedback),
                        static_cast<jint>(result.event.algorithm_version),
                        static_cast<jint>(result.event.parameter_version),
                        static_cast<jint>(result.event.decision_flags));
}
