#include "reviewfault/scheduler_v5.hpp"

#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <unordered_set>

namespace reviewfault {
namespace {
constexpr std::int64_t kMinute = 60;
constexpr std::int64_t kDay = 86'400;

bool is_repair(LearningTaskTypeV5 type) { return type == LearningTaskTypeV5::MathRepair; }
bool is_math(LearningTaskTypeV5 type) {
  return type >= LearningTaskTypeV5::MathRepair && type <= LearningTaskTypeV5::MathRetention;
}

std::int64_t repair_delay(std::uint32_t mask) {
  if ((mask & (MathErrorMaskConcept | MathErrorMaskForgottenFact)) != 0) return 10 * kMinute;
  if ((mask & MathErrorMaskApproach) != 0) return 15 * kMinute;
  return 20 * kMinute;
}

SessionSectionV5 section_for(const SessionTaskV5& task, std::int64_t now) {
  if (!task.is_new && is_repair(task.type) && task.due_at <= now) return SessionSectionV5::Repair;
  if (!task.is_new && task.due_at <= now) return SessionSectionV5::Due;
  return task.is_new ? SessionSectionV5::New : SessionSectionV5::Verification;
}

int priority(const SessionTaskV5& task, std::int64_t now) {
  const auto section = section_for(task, now);
  int base = section == SessionSectionV5::Repair ? 0 : section == SessionSectionV5::Due ? 1 :
             section == SessionSectionV5::Verification ? 2 : 3;
  // A failed repair is more urgent than an otherwise equivalent due task.
  return base * 1'000'000 - static_cast<int>(std::min(task.consecutive_failures, 999u)) * 1'000;
}
}  // namespace

Rating effective_memory_rating_v5(std::uint32_t hits, std::uint32_t count,
                                  std::uint32_t hint_level, bool answer_revealed,
                                  bool duration_reliable, std::uint32_t confidence) {
  if (count == 0 || hits > count || confidence < 1 || confidence > 5) {
    throw std::invalid_argument("memory evidence is invalid");
  }
  const double coverage = static_cast<double>(hits) / count;
  Rating rating = coverage < .60 ? Rating::Again : coverage < .85 ? Rating::Hard :
                  (confidence >= 4 && duration_reliable ? Rating::Easy : Rating::Good);
  // Help and an exposed answer are evidence of recognition, never independent recall.
  if ((hint_level > 0 || answer_revealed) && rating > Rating::Hard) rating = Rating::Hard;
  return rating;
}

MemoryTaskReviewResultV5 review_memory_task_v5(const MemoryTaskReviewInputV5& input) {
  const auto rating = effective_memory_rating_v5(input.point_hits, input.point_count,
      input.hint_level, input.answer_revealed, input.duration_reliable, input.confidence);
  const auto reviewed_at = std::max(input.reviewed_at, input.state.last_reviewed_at);
  const auto review = review_memory_v3(input.state, {rating, reviewed_at, input.preset,
      input.history_event_count, input.calibration_improvement, input.consecutive_lapses});
  return {review, rating, static_cast<double>(input.point_hits) / input.point_count,
          input.hint_level > 0 || input.answer_revealed};
}

MathTaskReviewResultV5 review_math_task_v5(const MathTaskReviewInputV5& input) {
  if (input.reviewed_at <= 0 || input.confidence < 1 || input.confidence > 5) {
    throw std::invalid_argument("math task evidence is invalid");
  }
  MathTaskReviewResultV5 result{input.state, input.state.phase, false, false, MathErrorMaskNone};
  auto& state = result.state;
  state.last_reviewed_at = std::max(input.reviewed_at, state.last_reviewed_at);
  ++state.repetitions;
  const bool failed = !input.correct || input.hint_revealed || input.error_mask != MathErrorMaskNone;
  if (failed) {
    result.regressed = state.phase != MathTaskPhaseV5::Repair;
    state.phase = MathTaskPhaseV5::Repair;
    state.consecutive_failures++;
    result.repair_mask = input.error_mask == MathErrorMaskNone ? MathErrorMaskOther : input.error_mask;
    state.due_at = state.last_reviewed_at + repair_delay(result.repair_mask);
    return result;
  }
  state.consecutive_failures = 0;
  switch (state.phase) {
    case MathTaskPhaseV5::Repair:
      state.phase = MathTaskPhaseV5::Original; state.due_at = state.last_reviewed_at + 30 * kMinute; break;
    case MathTaskPhaseV5::Original:
      state.original_verified = true;
      state.phase = input.variant_available ? MathTaskPhaseV5::Variant : MathTaskPhaseV5::AwaitingVariant;
      state.due_at = state.last_reviewed_at + (input.variant_available ? kDay : 0); break;
    case MathTaskPhaseV5::Variant:
      state.variant_verified = true; state.phase = MathTaskPhaseV5::Transfer;
      state.due_at = state.last_reviewed_at + 3 * kDay; break;
    case MathTaskPhaseV5::Transfer:
      state.transfer_verified = true;
      state.speed_verified = input.speed_target_met;
      state.phase = MathTaskPhaseV5::Retention;
      state.due_at = state.last_reviewed_at + 7 * kDay; break;
    case MathTaskPhaseV5::Retention:
      if (!input.speed_target_met) { state.speed_verified = false; state.due_at = state.last_reviewed_at + kDay; break; }
      state.speed_verified = true; state.phase = MathTaskPhaseV5::Graduated;
      state.due_at = state.last_reviewed_at + 28 * kDay; break;
    case MathTaskPhaseV5::AwaitingVariant:
      state.due_at = 0; return result;
    case MathTaskPhaseV5::Graduated:
      state.due_at = state.last_reviewed_at + 28 * kDay; break;
  }
  result.advanced = state.phase != result.phase_before;
  return result;
}

std::uint32_t remaining_exam_validations_v5(std::int64_t now, std::int64_t exam_at) {
  if (exam_at <= now) return 0;
  constexpr std::int64_t funnel[] = {28 * kDay, 14 * kDay, 7 * kDay, 3 * kDay, kDay};
  return static_cast<std::uint32_t>(std::count_if(std::begin(funnel), std::end(funnel),
      [=](std::int64_t lead) { return now <= exam_at - lead; }));
}

double personalized_interval_multiplier_v5(std::uint32_t sample_count,
                                           std::uint32_t success_count,
                                           double target_retention) {
  if (success_count > sample_count || target_retention < .80 || target_retention > .99) {
    throw std::invalid_argument("personalization evidence is invalid");
  }
  if (sample_count < kMinimumPersonalEvidenceV5) return 1.0;
  const double observed = static_cast<double>(success_count) / sample_count;
  // A 50% shrinkage prior toward the default prevents small cohort noise from
  // changing the calendar abruptly; the public range is intentionally narrow.
  const double shrunk = .5 * observed + .5 * target_retention;
  return std::clamp(1.0 + (shrunk - target_retention), .75, 1.25);
}

SessionPlanV5 plan_session_v5(const std::vector<SessionTaskV5>& input,
                              const LearningProfileV5& profile,
                              std::int64_t now, std::uint32_t available_seconds) {
  if (now <= 0 || profile.exam_at <= 0 || profile.daily_available_minutes == 0 ||
      profile.math_percent > 100 || profile.target_retention < .80 || profile.target_retention > .99) {
    throw std::invalid_argument("learning profile is invalid");
  }
  const auto budget = available_seconds == 0 ? profile.daily_available_minutes * 60 : available_seconds;
  std::vector<SessionTaskV5> tasks;
  std::unordered_set<std::string> ids;
  for (const auto& task : input) {
    if (task.id.empty() || !ids.insert(task.id).second || task.estimated_seconds == 0) {
      throw std::invalid_argument("session task identity is invalid");
    }
    if (task.dependency_ready) tasks.push_back(task);
  }
  std::stable_sort(tasks.begin(), tasks.end(), [=](const auto& a, const auto& b) {
    const int ap = priority(a, now), bp = priority(b, now);
    if (ap != bp) return ap < bp;
    if (a.due_at != b.due_at) return a.due_at < b.due_at;
    return a.id < b.id;
  });
  SessionPlanV5 plan{};
  std::uint32_t critical_seconds = 0;
  for (const auto& task : tasks) if (section_for(task, now) != SessionSectionV5::New) critical_seconds += task.estimated_seconds;
  plan.new_learning_blocked = critical_seconds > budget;
  std::string last_math_chapter;
  bool last_confusable_memory = false;
  std::vector<bool> selected(tasks.size(), false);
  for (std::size_t pass = 0; pass < tasks.size(); ++pass) {
    std::size_t pick = tasks.size();
    std::size_t fallback = tasks.size();
    for (std::size_t i = 0; i < tasks.size(); ++i) {
      const auto& task = tasks[i]; if (selected[i]) continue;
      const auto section = section_for(task, now);
      if (section == SessionSectionV5::New && plan.new_learning_blocked) continue;
      // Due work is never silently dropped to make a session look feasible.
      // The excess is surfaced as review debt; only optional verification/new
      // work is compressed when a daily budget is exceeded.
      if (plan.estimated_seconds + task.estimated_seconds > budget &&
          section != SessionSectionV5::Repair && section != SessionSectionV5::Due) continue;
      const bool repeats_math = is_math(task.type) && !last_math_chapter.empty() && task.chapter_id == last_math_chapter;
      const bool repeats_confusable = !is_math(task.type) && task.confusable && last_confusable_memory;
      if (repeats_math || repeats_confusable) {
        if (fallback == tasks.size()) fallback = i;
        continue;
      }
      pick = i; break;
    }
    if (pick == tasks.size()) pick = fallback;
    if (pick == tasks.size()) break;
    const auto& task = tasks[pick]; selected[pick] = true;
    plan.tasks.push_back({task, section_for(task, now)});
    plan.estimated_seconds += task.estimated_seconds;
    last_math_chapter = is_math(task.type) ? task.chapter_id : std::string{};
    last_confusable_memory = !is_math(task.type) && task.confusable;
  }
  for (std::size_t i = 0; i < tasks.size(); ++i) if (!selected[i]) {
    if (section_for(tasks[i], now) == SessionSectionV5::New) ++plan.omitted_new_count;
    else plan.review_debt_seconds += tasks[i].estimated_seconds;
  }
  if (plan.estimated_seconds > budget) plan.review_debt_seconds += plan.estimated_seconds - budget;
  return plan;
}

}  // namespace reviewfault
