#include "reviewfault/scheduler_v4.hpp"

#include <algorithm>
#include <cassert>
#include <iostream>
#include <stdexcept>
#include <vector>

using reviewfault::MathErrorReason;
using reviewfault::ReviewActionV4;

namespace {

ReviewActionV4 action(const char* id, const char* device,
                      std::uint64_t counter, std::uint64_t cursor,
                      std::int32_t feedback, std::int64_t reviewed_at) {
  return {id, device, counter, cursor, feedback, reviewed_at, 0,
          MathErrorReason::None, false};
}

}  // namespace

int main() {
  const std::vector<ReviewActionV4> concurrent{
      action("z", "device-a", 2, 0, 3, 1'800'000'100),
      action("b", "device-b", 1, 0, 2, 1'800'000'000),
      action("a", "device-c", 1, 0, 3, 1'800'000'000),
      action("first-a", "device-a", 1, 0, 1, 1'800'000'200),
  };
  const auto order = reviewfault::canonical_order_v4(concurrent);
  assert((order == std::vector<std::size_t>{2, 1, 3, 0}));

  const std::vector<ReviewActionV4> cursored{
      action("later", "device-b", 1, 11, 3, 1'800'000'000),
      action("earlier", "device-a", 1, 10, 3, 1'800'000'100),
  };
  assert((reviewfault::canonical_order_v4(cursored) ==
          std::vector<std::size_t>{1, 0}));

  auto shuffled = concurrent;
  std::reverse(shuffled.begin(), shuffled.end());
  const auto first = reviewfault::replay_memory_history_v4(
      reviewfault::MemoryScheduleState{}, concurrent, {});
  const auto second = reviewfault::replay_memory_history_v4(
      reviewfault::MemoryScheduleState{}, shuffled, {});
  assert(first.action_count == concurrent.size());
  assert(first.state.state == second.state.state);
  assert(first.state.difficulty == second.state.difficulty);
  assert(first.state.stability_days == second.state.stability_days);
  assert(first.state.due_at == second.state.due_at);
  assert(first.state.repetitions == second.state.repetitions);
  assert(first.state.lapses == second.state.lapses);

  auto duplicate = concurrent;
  duplicate[1].action_id = duplicate[0].action_id;
  try {
    (void)reviewfault::canonical_order_v4(duplicate);
    assert(false && "duplicate action ids must fail");
  } catch (const std::invalid_argument&) {
  }

  const std::vector<ReviewActionV4> cycle{
      action("one", "same", 1, 20, 3, 1'800'000'000),
      action("two", "same", 2, 10, 3, 1'800'000'100),
      action("bridge", "other", 1, 15, 3, 1'800'000'050),
  };
  try {
    (void)reviewfault::canonical_order_v4(cycle);
    assert(false && "contradictory causal facts must fail");
  } catch (const std::invalid_argument&) {
  }

  const std::vector<ReviewActionV4> math{
      action("m2", "pen", 2, 0, 3, 1'800'086'400),
      action("m1", "pen", 1, 0, 1, 1'800'000'000),
  };
  const auto math_result = reviewfault::replay_math_history_v4(
      reviewfault::MathScheduleState{}, math, {});
  assert(math_result.action_count == 2);
  assert(math_result.state.mastery_level == 1);

  std::cout << "Scheduler v4 canonical replay tests passed\n";
  return 0;
}
