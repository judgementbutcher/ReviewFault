#include <jni.h>

#include "reviewfault/reviewfault_c.h"

#include <cstdint>
#include <string>
#include <vector>

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

extern "C" JNIEXPORT jintArray JNICALL
Java_cn_reviewfault_app_core_NativeScheduler_nativeCanonicalOrderV4(
    JNIEnv* env, jobject, jobjectArray action_ids, jobjectArray device_ids,
    jlongArray device_counters, jlongArray causal_cursors, jintArray feedback,
    jlongArray reviewed_at) {
  const auto count = env->GetArrayLength(action_ids);
  if (env->GetArrayLength(device_ids) != count ||
      env->GetArrayLength(device_counters) != count ||
      env->GetArrayLength(causal_cursors) != count ||
      env->GetArrayLength(feedback) != count ||
      env->GetArrayLength(reviewed_at) != count) {
    throw_illegal_argument(env, "ABI v4 action arrays must have equal lengths");
    return nullptr;
  }
  std::vector<std::string> action_storage;
  std::vector<std::string> device_storage;
  std::vector<rf_review_action_v4> actions(static_cast<std::size_t>(count));
  action_storage.reserve(count);
  device_storage.reserve(count);
  jlong* counters = env->GetLongArrayElements(device_counters, nullptr);
  jlong* cursors = env->GetLongArrayElements(causal_cursors, nullptr);
  jint* ratings = env->GetIntArrayElements(feedback, nullptr);
  jlong* times = env->GetLongArrayElements(reviewed_at, nullptr);
  for (jsize index = 0; index < count; ++index) {
    auto action = static_cast<jstring>(env->GetObjectArrayElement(action_ids, index));
    auto device = static_cast<jstring>(env->GetObjectArrayElement(device_ids, index));
    const char* action_text = env->GetStringUTFChars(action, nullptr);
    const char* device_text = env->GetStringUTFChars(device, nullptr);
    action_storage.emplace_back(action_text);
    device_storage.emplace_back(device_text);
    env->ReleaseStringUTFChars(action, action_text);
    env->ReleaseStringUTFChars(device, device_text);
    env->DeleteLocalRef(action);
    env->DeleteLocalRef(device);
  }
  for (jsize index = 0; index < count; ++index) {
    actions[index] = {sizeof(rf_review_action_v4), action_storage[index].c_str(),
                      device_storage[index].c_str(),
                      static_cast<uint64_t>(counters[index]),
                      static_cast<uint64_t>(cursors[index]), ratings[index],
                      static_cast<int64_t>(times[index]), 0, RF_MATH_ERROR_NONE, 0};
  }
  env->ReleaseLongArrayElements(device_counters, counters, JNI_ABORT);
  env->ReleaseLongArrayElements(causal_cursors, cursors, JNI_ABORT);
  env->ReleaseIntArrayElements(feedback, ratings, JNI_ABORT);
  env->ReleaseLongArrayElements(reviewed_at, times, JNI_ABORT);
  std::vector<size_t> order(static_cast<std::size_t>(count));
  char error[256]{};
  if (canonical_review_order_v4(actions.data(), actions.size(), order.data(),
                                order.size(), error, sizeof(error)) != 0) {
    throw_illegal_argument(env, error);
    return nullptr;
  }
  std::vector<jint> converted(order.begin(), order.end());
  jintArray result = env->NewIntArray(count);
  env->SetIntArrayRegion(result, 0, count, converted.data());
  return result;
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
