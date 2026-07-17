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

