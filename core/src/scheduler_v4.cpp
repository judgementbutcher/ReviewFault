#include "reviewfault/scheduler_v4.hpp"

#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <unordered_set>

namespace reviewfault {
namespace {

void validate_actions(const std::vector<ReviewActionV4>& actions) {
  std::unordered_set<std::string> action_ids;
  std::unordered_set<std::string> device_counters;
  for (const auto& action : actions) {
    if (action.action_id.empty() || action.device_id.empty() || action.device_counter == 0 ||
        action.reviewed_at <= 0) {
      throw std::invalid_argument("review action identity and time are required");
    }
    if (!action_ids.insert(action.action_id).second) {
      throw std::invalid_argument("duplicate review action id");
    }
    const auto device_counter = action.device_id + "\n" +
                                std::to_string(action.device_counter);
    if (!device_counters.insert(device_counter).second) {
      throw std::invalid_argument("duplicate device counter");
    }
  }
}

bool causal_before(const ReviewActionV4& left, const ReviewActionV4& right) {
  if (left.device_id == right.device_id) {
    return left.device_counter < right.device_counter;
  }
  return left.causal_cursor > 0 && right.causal_cursor > 0 &&
         left.causal_cursor < right.causal_cursor;
}

bool stable_fact_order(const ReviewActionV4& left, const ReviewActionV4& right) {
  if (left.reviewed_at != right.reviewed_at) {
    return left.reviewed_at < right.reviewed_at;
  }
  return left.action_id < right.action_id;
}

}  // namespace

std::vector<std::size_t> canonical_order_v4(
    const std::vector<ReviewActionV4>& actions) {
  validate_actions(actions);
  std::vector<std::vector<std::size_t>> outgoing(actions.size());
  std::vector<std::size_t> indegree(actions.size(), 0);
  for (std::size_t left = 0; left < actions.size(); ++left) {
    for (std::size_t right = left + 1; right < actions.size(); ++right) {
      if (causal_before(actions[left], actions[right])) {
        outgoing[left].push_back(right);
        ++indegree[right];
      }
      if (causal_before(actions[right], actions[left])) {
        outgoing[right].push_back(left);
        ++indegree[left];
      }
    }
  }

  std::vector<std::size_t> order;
  order.reserve(actions.size());
  std::vector<bool> emitted(actions.size(), false);
  while (order.size() < actions.size()) {
    std::size_t selected = actions.size();
    for (std::size_t index = 0; index < actions.size(); ++index) {
      if (emitted[index] || indegree[index] != 0) continue;
      if (selected == actions.size() ||
          stable_fact_order(actions[index], actions[selected])) {
        selected = index;
      }
    }
    if (selected == actions.size()) {
      throw std::invalid_argument("review action causal order contains a cycle");
    }
    emitted[selected] = true;
    order.push_back(selected);
    for (const auto next : outgoing[selected]) --indegree[next];
  }
  return order;
}

MemoryReplayResultV4 replay_memory_history_v4(
    const MemoryScheduleState& initial_state,
    const std::vector<ReviewActionV4>& actions,
    const ReplayMemoryConfigV4& config) {
  if (!std::isfinite(config.calibration_improvement)) {
    throw std::invalid_argument("calibration improvement must be finite");
  }
  MemoryReplayResultV4 result{initial_state, 0};
  std::uint32_t consecutive_lapses = 0;
  for (const auto index : canonical_order_v4(actions)) {
    const auto& action = actions[index];
    if (action.feedback < static_cast<std::int32_t>(Rating::Again) ||
        action.feedback > static_cast<std::int32_t>(Rating::Easy)) {
      throw std::invalid_argument("memory feedback is invalid");
    }
    const auto rating = static_cast<Rating>(action.feedback);
    const auto effective_reviewed_at = std::max(
        action.reviewed_at, result.state.last_reviewed_at);
    result.state = review_memory_v3(
        result.state,
        {rating, effective_reviewed_at, config.preset,
         static_cast<std::uint32_t>(result.action_count),
         config.calibration_improvement, consecutive_lapses}).state;
    consecutive_lapses = rating == Rating::Again ? consecutive_lapses + 1 : 0;
    ++result.action_count;
  }
  return result;
}

MathReplayResultV4 replay_math_history_v4(
    const MathScheduleState& initial_state,
    const std::vector<ReviewActionV4>& actions,
    const ReplayMathConfigV4& config) {
  MathReplayResultV4 result{initial_state, 0};
  std::uint32_t consecutive_failures = 0;
  for (const auto index : canonical_order_v4(actions)) {
    const auto& action = actions[index];
    if (action.feedback < static_cast<std::int32_t>(MathFeedback::CannotStart) ||
        action.feedback > static_cast<std::int32_t>(MathFeedback::FluentCorrect)) {
      throw std::invalid_argument("math feedback is invalid");
    }
    const auto feedback = static_cast<MathFeedback>(action.feedback);
    const auto effective_reviewed_at = std::max(
        action.reviewed_at, result.state.last_reviewed_at);
    result.state = review_math_v3(
        result.state,
        {feedback, action.error_reason, action.hint_revealed, effective_reviewed_at,
         config.intensity, action.duration_seconds, DurationQualityV3::Unknown,
         consecutive_failures}).state;
    consecutive_failures = feedback <= MathFeedback::Incorrect
        ? consecutive_failures + 1 : 0;
    ++result.action_count;
  }
  return result;
}

}  // namespace reviewfault
