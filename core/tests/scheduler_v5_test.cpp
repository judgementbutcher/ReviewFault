#include "reviewfault/scheduler_v5.hpp"

#include <cassert>
#include <iostream>

using namespace reviewfault;

int main() {
  // Looking at an answer is capped at Hard even when every point is present.
  assert(effective_memory_rating_v5(4, 4, 1, false, true, 5) == Rating::Hard);
  assert(effective_memory_rating_v5(2, 4, 0, false, true, 5) == Rating::Again);
  const auto memory = review_memory_task_v5({{}, MemoryPreset::Balanced, 1'800'000'000,
      4, 4, 0, false, true, 40, 5, 20, .01, 0});
  assert(memory.effective_rating == Rating::Easy);

  MathTaskStateV5 state{};
  auto failed = review_math_task_v5({state, 1'800'000'000, false, false, false, true,
                                     MathErrorMaskConcept, 0, 3});
  assert(failed.state.phase == MathTaskPhaseV5::Repair);
  assert(failed.repair_mask == MathErrorMaskConcept);
  auto original = review_math_task_v5({failed.state, 1'800'000'600, true, false, true, true, 0, 0, 4});
  assert(original.state.phase == MathTaskPhaseV5::Original);
  auto awaiting = review_math_task_v5({original.state, 1'800'002'400, true, false, true, false, 0, 0, 4});
  assert(awaiting.state.phase == MathTaskPhaseV5::AwaitingVariant);
  // No linked variant is explicitly waiting, never silently considered mastered.
  assert(!awaiting.state.variant_verified);

  const std::vector<SessionTaskV5> tasks{
      {"repair", "u1", "math", "calculus", LearningTaskTypeV5::MathRepair, 1, 300, 2, true, false, false, 4},
      {"due", "u2", "operating_systems", "process", LearningTaskTypeV5::MemoryRecall, 1, 120, 0, true, false, true, 4},
      {"new", "u3", "operating_systems", "memory", LearningTaskTypeV5::MemoryExplain, 0, 120, 0, true, true, false, 4},
      {"new-repair", "u4", "math", "algebra", LearningTaskTypeV5::MathRepair, 0, 120, 0, true, true, false, 4},
  };
  const auto plan = plan_session_v5(tasks, {kDefaultExamAtV5, 5, 0x7f, 50, .90}, 1'800'000'000);
  assert(plan.tasks.size() == 2);
  assert(plan.tasks.front().section == SessionSectionV5::Repair);
  assert(plan.new_learning_blocked && plan.omitted_new_count == 2);
  assert(remaining_exam_validations_v5(kDefaultExamAtV5 - 29 * 86400, kDefaultExamAtV5) == 5);
  assert(remaining_exam_validations_v5(kDefaultExamAtV5 - 2 * 86400, kDefaultExamAtV5) == 1);
  assert(personalized_interval_multiplier_v5(19, 0, .90) == 1.0);
  assert(personalized_interval_multiplier_v5(20, 20, .90) > 1.0);
  assert(personalized_interval_multiplier_v5(20, 0, .90) < 1.0);
  std::cout << "Scheduler v5 evidence and session planning tests passed\n";
}
