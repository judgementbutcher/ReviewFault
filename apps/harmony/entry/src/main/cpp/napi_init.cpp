#include "reviewfault/reviewfault_c.h"
#include <napi/native_api.h>
#include <string>
#include <vector>

namespace {
napi_value AbiVersion(napi_env env, napi_callback_info) {
  napi_value value{};
  napi_create_uint32(env, rf_scheduler_abi_version(), &value);
  return value;
}

std::string StringProperty(napi_env env, napi_value object, const char* name) {
  napi_value value{};
  napi_get_named_property(env, object, name, &value);
  size_t size = 0;
  napi_get_value_string_utf8(env, value, nullptr, 0, &size);
  std::string result(size + 1, '\0');
  napi_get_value_string_utf8(env, value, result.data(), size + 1, &size);
  result.resize(size);
  return result;
}

int64_t IntegerProperty(napi_env env, napi_value object, const char* name) {
  napi_value value{};
  int64_t result = 0;
  napi_get_named_property(env, object, name, &value);
  napi_get_value_int64(env, value, &result);
  return result;
}

double DoubleProperty(napi_env env, napi_value object, const char* name) {
  napi_value value{}; double result = 0;
  napi_get_named_property(env, object, name, &value);
  napi_get_value_double(env, value, &result);
  return result;
}

void SetInt(napi_env env, napi_value object, const char* name, int64_t value) {
  napi_value item{}; napi_create_int64(env, value, &item); napi_set_named_property(env, object, name, item);
}
void SetDouble(napi_env env, napi_value object, const char* name, double value) {
  napi_value item{}; napi_create_double(env, value, &item); napi_set_named_property(env, object, name, item);
}

napi_value ReviewMemory(napi_env env, napi_callback_info info) {
  size_t argc = 1; napi_value args[1]{}; napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
  rf_memory_schedule_state_v2 state{
      sizeof(rf_memory_schedule_state_v2), static_cast<int32_t>(IntegerProperty(env, args[0], "state")),
      DoubleProperty(env, args[0], "difficulty"), DoubleProperty(env, args[0], "stabilityDays"),
      IntegerProperty(env, args[0], "dueAt"), IntegerProperty(env, args[0], "lastReviewedAt"),
      static_cast<uint32_t>(IntegerProperty(env, args[0], "repetitions")),
      static_cast<uint32_t>(IntegerProperty(env, args[0], "lapses"))};
  rf_memory_review_input_v3 input{
      sizeof(rf_memory_review_input_v3), static_cast<int32_t>(IntegerProperty(env, args[0], "rating")),
      static_cast<int32_t>(IntegerProperty(env, args[0], "preset")), IntegerProperty(env, args[0], "reviewedAt"),
      static_cast<uint32_t>(IntegerProperty(env, args[0], "historyCount")),
      DoubleProperty(env, args[0], "calibrationImprovement"),
      static_cast<uint32_t>(IntegerProperty(env, args[0], "consecutiveLapses"))};
  rf_memory_review_result_v3 result{}; result.struct_size = sizeof(result); char error[256]{};
  if (review_memory_v3(&state, &input, &result, error, sizeof(error)) != 0) {
    napi_throw_error(env, "MEMORY_REVIEW", error); return nullptr;
  }
  napi_value value{}; napi_create_object(env, &value);
  SetInt(env, value, "state", result.state.state); SetDouble(env, value, "difficulty", result.state.difficulty);
  SetDouble(env, value, "stabilityDays", result.state.stability_days); SetInt(env, value, "dueAt", result.state.due_at);
  SetInt(env, value, "lastReviewedAt", result.state.last_reviewed_at); SetInt(env, value, "repetitions", result.state.repetitions);
  SetInt(env, value, "lapses", result.state.lapses); SetInt(env, value, "parameterVersion", result.event.parameter_version);
  SetInt(env, value, "decisionFlags", result.event.decision_flags); SetDouble(env, value, "scheduledDays", result.event.scheduled_days);
  return value;
}

napi_value ReviewMath(napi_env env, napi_callback_info info) {
  size_t argc = 1; napi_value args[1]{}; napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
  rf_math_schedule_state_v2 state{
      sizeof(rf_math_schedule_state_v2), static_cast<uint32_t>(IntegerProperty(env, args[0], "masteryLevel")),
      static_cast<uint32_t>(IntegerProperty(env, args[0], "fluentStreak")), IntegerProperty(env, args[0], "dueAt"),
      IntegerProperty(env, args[0], "lastReviewedAt"), static_cast<uint32_t>(IntegerProperty(env, args[0], "repetitions"))};
  rf_math_attempt_input_v3 input{
      sizeof(rf_math_attempt_input_v3), static_cast<int32_t>(IntegerProperty(env, args[0], "feedback")),
      static_cast<int32_t>(IntegerProperty(env, args[0], "errorReason")),
      static_cast<int32_t>(IntegerProperty(env, args[0], "hintRevealed")),
      static_cast<int32_t>(IntegerProperty(env, args[0], "intensity")), IntegerProperty(env, args[0], "reviewedAt"),
      static_cast<uint32_t>(IntegerProperty(env, args[0], "durationSeconds")),
      static_cast<int32_t>(IntegerProperty(env, args[0], "durationQuality")),
      static_cast<uint32_t>(IntegerProperty(env, args[0], "consecutiveFailures"))};
  rf_math_review_result_v3 result{}; result.struct_size = sizeof(result); char error[256]{};
  if (review_math_v3(&state, &input, &result, error, sizeof(error)) != 0) {
    napi_throw_error(env, "MATH_REVIEW", error); return nullptr;
  }
  napi_value value{}; napi_create_object(env, &value);
  SetInt(env, value, "masteryLevel", result.state.mastery_level); SetInt(env, value, "fluentStreak", result.state.fluent_streak);
  SetInt(env, value, "dueAt", result.state.due_at); SetInt(env, value, "lastReviewedAt", result.state.last_reviewed_at);
  SetInt(env, value, "repetitions", result.state.repetitions); SetInt(env, value, "appliedFeedback", result.event.applied_feedback);
  SetInt(env, value, "parameterVersion", result.event.parameter_version); SetInt(env, value, "decisionFlags", result.event.decision_flags);
  SetDouble(env, value, "scheduledDays", result.event.scheduled_days); return value;
}

napi_value CanonicalOrder(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value args[1]{};
  napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
  uint32_t count = 0;
  napi_get_array_length(env, args[0], &count);
  std::vector<std::string> action_ids(count);
  std::vector<std::string> device_ids(count);
  std::vector<rf_review_action_v4> actions(count);
  for (uint32_t index = 0; index < count; ++index) {
    napi_value item{};
    napi_get_element(env, args[0], index, &item);
    action_ids[index] = StringProperty(env, item, "actionId");
    device_ids[index] = StringProperty(env, item, "deviceId");
    actions[index] = {sizeof(rf_review_action_v4), action_ids[index].c_str(),
                      device_ids[index].c_str(),
                      static_cast<uint64_t>(IntegerProperty(env, item, "deviceCounter")),
                      static_cast<uint64_t>(IntegerProperty(env, item, "causalCursor")),
                      static_cast<int32_t>(IntegerProperty(env, item, "feedback")),
                      IntegerProperty(env, item, "reviewedAt"), 0,
                      RF_MATH_ERROR_NONE, 0};
  }
  std::vector<size_t> order(count);
  char error[256]{};
  if (canonical_review_order_v4(actions.data(), actions.size(), order.data(),
                                order.size(), error, sizeof(error)) != 0) {
    napi_throw_error(env, "REPLAY_ORDER", error);
    return nullptr;
  }
  napi_value result{};
  napi_create_array_with_length(env, count, &result);
  for (uint32_t index = 0; index < count; ++index) {
    napi_value value{};
    napi_create_uint32(env, static_cast<uint32_t>(order[index]), &value);
    napi_set_element(env, result, index, value);
  }
  return result;
}
}

EXTERN_C_START
static napi_value Init(napi_env env, napi_value exports) {
  napi_property_descriptor properties[] = {
      {"abiVersion", nullptr, AbiVersion, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"canonicalOrderV4", nullptr, CanonicalOrder, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"reviewMemoryV3", nullptr, ReviewMemory, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"reviewMathV3", nullptr, ReviewMath, nullptr, nullptr, nullptr, napi_default, nullptr},
  };
  napi_define_properties(env, exports, sizeof(properties) / sizeof(properties[0]), properties);
  return exports;
}
EXTERN_C_END

static napi_module module = {1, 0, nullptr, Init, "libreviewfault.so", nullptr, {0}};
extern "C" __attribute__((constructor)) void RegisterReviewFaultModule() {
  napi_module_register(&module);
}
