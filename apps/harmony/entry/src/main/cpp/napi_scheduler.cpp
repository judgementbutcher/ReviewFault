#include "napi/native_api.h"

#include "reviewfault/reviewfault_c.h"
#include "schema_sql.hpp"

#include <cstdint>

namespace {

bool get_int32(napi_env env, napi_value value, int32_t& output) {
  return napi_get_value_int32(env, value, &output) == napi_ok;
}

bool get_int64(napi_env env, napi_value value, int64_t& output) {
  return napi_get_value_int64(env, value, &output) == napi_ok;
}

bool get_double(napi_env env, napi_value value, double& output) {
  return napi_get_value_double(env, value, &output) == napi_ok;
}

void set_int32(napi_env env, napi_value object, const char* name, int32_t value) {
  napi_value property;
  napi_create_int32(env, value, &property);
  napi_set_named_property(env, object, name, property);
}

void set_int64(napi_env env, napi_value object, const char* name, int64_t value) {
  napi_value property;
  napi_create_int64(env, value, &property);
  napi_set_named_property(env, object, name, property);
}

void set_double(napi_env env, napi_value object, const char* name, double value) {
  napi_value property;
  napi_create_double(env, value, &property);
  napi_set_named_property(env, object, name, property);
}

napi_value AbiVersion(napi_env env, napi_callback_info) {
  napi_value value;
  napi_create_uint32(env, rf_scheduler_abi_version(), &value);
  return value;
}

napi_value SchemaV1(napi_env env, napi_callback_info) {
  napi_value value;
  napi_create_string_utf8(env, kReviewFaultSchemaV1, NAPI_AUTO_LENGTH, &value);
  return value;
}

napi_value Review(napi_env env, napi_callback_info info) {
  size_t argc = 10;
  napi_value argv[10];
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  if (argc != 10) {
    napi_throw_type_error(env, nullptr, "review requires exactly 10 arguments");
    return nullptr;
  }

  int32_t state = 0;
  double difficulty = 0;
  double stability_days = 0;
  int64_t due_at = 0;
  int64_t last_reviewed_at = 0;
  int32_t repetitions = 0;
  int32_t lapses = 0;
  int32_t rating = 0;
  int64_t reviewed_at = 0;
  double target_retention = 0;
  if (!get_int32(env, argv[0], state) || !get_double(env, argv[1], difficulty) ||
      !get_double(env, argv[2], stability_days) || !get_int64(env, argv[3], due_at) ||
      !get_int64(env, argv[4], last_reviewed_at) ||
      !get_int32(env, argv[5], repetitions) || !get_int32(env, argv[6], lapses) ||
      !get_int32(env, argv[7], rating) || !get_int64(env, argv[8], reviewed_at) ||
      !get_double(env, argv[9], target_retention) || repetitions < 0 || lapses < 0) {
    napi_throw_type_error(env, nullptr, "review argument type or range is invalid");
    return nullptr;
  }

  rf_scheduler_config config = rf_default_scheduler_config();
  config.target_retention = target_retention;
  rf_card card = rf_new_card();
  card.state = state;
  card.difficulty = difficulty;
  card.stability_days = stability_days;
  card.due_at = due_at;
  card.last_reviewed_at = last_reviewed_at;
  card.repetitions = static_cast<uint32_t>(repetitions);
  card.lapses = static_cast<uint32_t>(lapses);
  rf_review_result result{};
  result.struct_size = sizeof(result);
  char error[256]{};
  if (rf_review(&config, &card, rating, reviewed_at, &result, error, sizeof(error)) != 0) {
    napi_throw_error(env, nullptr, error);
    return nullptr;
  }

  napi_value output;
  napi_create_object(env, &output);
  set_int32(env, output, "state", result.card.state);
  set_double(env, output, "difficulty", result.card.difficulty);
  set_double(env, output, "stabilityDays", result.card.stability_days);
  set_int64(env, output, "dueAt", result.card.due_at);
  set_int32(env, output, "repetitions", static_cast<int32_t>(result.card.repetitions));
  set_int32(env, output, "lapses", static_cast<int32_t>(result.card.lapses));
  set_double(env, output, "scheduledDays", result.log.scheduled_days);
  set_double(env, output, "retrievabilityBefore", result.log.retrievability_before);
  return output;
}

napi_value Init(napi_env env, napi_value exports) {
  napi_property_descriptor descriptors[] = {
      {"abiVersion", nullptr, AbiVersion, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"schemaV1", nullptr, SchemaV1, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"review", nullptr, Review, nullptr, nullptr, nullptr, napi_default, nullptr},
  };
  napi_define_properties(env, exports, sizeof(descriptors) / sizeof(descriptors[0]),
                         descriptors);
  return exports;
}

}  // namespace

static napi_module reviewfault_module = {
    .nm_version = 1,
    .nm_flags = 0,
    .nm_filename = nullptr,
    .nm_register_func = Init,
    .nm_modname = "reviewfault",
    .nm_priv = nullptr,
    .reserved = {nullptr},
};

#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
extern "C" __attribute__((constructor)) void RegisterReviewFaultModule() {
  napi_module_register(&reviewfault_module);
}
#pragma GCC diagnostic pop
