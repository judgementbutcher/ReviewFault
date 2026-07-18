#pragma once

#include "reviewfault/scheduler_v3.hpp"

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace reviewfault {

inline constexpr std::uint32_t kSchedulerAbiVersionV4 = 4;

struct ReviewActionV4 {
  std::string action_id;
  std::string device_id;
  std::uint64_t device_counter = 0;
  std::uint64_t causal_cursor = 0;
  std::int32_t feedback = 0;
  std::int64_t reviewed_at = 0;
  std::uint32_t duration_seconds = 0;
  MathErrorReason error_reason = MathErrorReason::None;
  bool hint_revealed = false;
};

struct ReplayMemoryConfigV4 {
  MemoryPreset preset = MemoryPreset::Balanced;
  double calibration_improvement = 0.0;
};

struct ReplayMathConfigV4 {
  MathIntensity intensity = MathIntensity::Balanced;
};

struct MemoryReplayResultV4 {
  MemoryScheduleState state;
  std::size_t action_count = 0;
};

struct MathReplayResultV4 {
  MathScheduleState state;
  std::size_t action_count = 0;
};

[[nodiscard]] std::vector<std::size_t> canonical_order_v4(
    const std::vector<ReviewActionV4>& actions);
[[nodiscard]] MemoryReplayResultV4 replay_memory_history_v4(
    const MemoryScheduleState& initial_state,
    const std::vector<ReviewActionV4>& actions,
    const ReplayMemoryConfigV4& config);
[[nodiscard]] MathReplayResultV4 replay_math_history_v4(
    const MathScheduleState& initial_state,
    const std::vector<ReviewActionV4>& actions,
    const ReplayMathConfigV4& config);

}  // namespace reviewfault
