#pragma once

#include "reviewfault/scheduler_v3.hpp"

#include <cstdint>
#include <string>
#include <vector>

namespace reviewfault {

inline constexpr std::uint32_t kSchedulerAbiVersionV5 = 5;
inline constexpr std::int64_t kDefaultExamAtV5 = 1'797'724'800;  // 2026-12-20T00:00:00Z
inline constexpr std::uint32_t kMinimumPersonalEvidenceV5 = 20;

// These are task-level facts, rather than a replacement for the frozen v3
// schedulers.  They intentionally describe the learning route so a cluster
// cannot graduate merely because its original question was answered once.
enum class LearningTaskTypeV5 : std::int32_t {
  MathRepair = 0, MathOriginal = 1, MathVariant = 2, MathTransfer = 3,
  MathRetention = 4, MemoryRecall = 5, MemoryExplain = 6,
  MemoryCompare = 7, MemoryDiagram = 8, MemoryCalculate = 9,
};

enum class MathTaskPhaseV5 : std::int32_t {
  Repair = 0, Original = 1, Variant = 2, Transfer = 3, Retention = 4,
  AwaitingVariant = 5, Graduated = 6,
};

enum MathErrorMaskV5 : std::uint32_t {
  MathErrorMaskNone = 0,
  MathErrorMaskConcept = 1u << 0,
  MathErrorMaskApproach = 1u << 1,
  MathErrorMaskCalculation = 1u << 2,
  MathErrorMaskMisread = 1u << 3,
  MathErrorMaskForgottenFact = 1u << 4,
  MathErrorMaskTimeout = 1u << 5,
  MathErrorMaskOther = 1u << 6,
};

struct MemoryTaskReviewInputV5 {
  MemoryScheduleState state;
  MemoryPreset preset = MemoryPreset::Balanced;
  std::int64_t reviewed_at = 0;
  std::uint32_t point_hits = 0;
  std::uint32_t point_count = 0;
  std::uint32_t hint_level = 0;
  bool answer_revealed = false;
  bool duration_reliable = true;
  std::uint32_t duration_seconds = 0;
  std::uint32_t confidence = 3;  // 1..5
  std::uint32_t history_event_count = 0;
  double calibration_improvement = 0.0;
  std::uint32_t consecutive_lapses = 0;
};

struct MemoryTaskReviewResultV5 {
  MemoryScheduleResultV3 review;
  Rating effective_rating = Rating::Again;
  double point_coverage = 0.0;
  bool rating_capped_by_help = false;
};

struct MathTaskStateV5 {
  MathTaskPhaseV5 phase = MathTaskPhaseV5::Repair;
  std::int64_t due_at = 0;
  std::int64_t last_reviewed_at = 0;
  std::uint32_t repetitions = 0;
  std::uint32_t consecutive_failures = 0;
  bool original_verified = false;
  bool variant_verified = false;
  bool transfer_verified = false;
  bool speed_verified = false;
};

struct MathTaskReviewInputV5 {
  MathTaskStateV5 state;
  std::int64_t reviewed_at = 0;
  bool correct = false;
  bool hint_revealed = false;
  bool speed_target_met = true;
  bool variant_available = true;
  std::uint32_t error_mask = MathErrorMaskNone;
  std::uint32_t duration_seconds = 0;
  std::uint32_t confidence = 3;
};

struct MathTaskReviewResultV5 {
  MathTaskStateV5 state;
  MathTaskPhaseV5 phase_before = MathTaskPhaseV5::Repair;
  bool advanced = false;
  bool regressed = false;
  std::uint32_t repair_mask = MathErrorMaskNone;
};

struct LearningProfileV5 {
  std::int64_t exam_at = kDefaultExamAtV5;
  std::uint32_t daily_available_minutes = 60;
  std::uint8_t study_days_mask = 0x7f;  // Monday through Sunday
  std::uint8_t math_percent = 50;
  double target_retention = 0.90;
};

struct SessionTaskV5 {
  std::string id;
  std::string unit_id;
  std::string subject;
  std::string chapter_id;
  LearningTaskTypeV5 type = LearningTaskTypeV5::MemoryRecall;
  std::int64_t due_at = 0;
  std::uint32_t estimated_seconds = 60;
  std::uint32_t consecutive_failures = 0;
  bool dependency_ready = true;
  bool is_new = false;
  bool confusable = false;
  std::uint32_t remaining_validations = 0;
};

enum class SessionSectionV5 : std::int32_t {
  Repair = 0, Due = 1, Verification = 2, New = 3,
};

struct PlannedTaskV5 { SessionTaskV5 task; SessionSectionV5 section; };

struct SessionPlanV5 {
  std::vector<PlannedTaskV5> tasks;
  std::uint32_t estimated_seconds = 0;
  std::uint32_t review_debt_seconds = 0;
  std::uint32_t omitted_new_count = 0;
  bool new_learning_blocked = false;
};

[[nodiscard]] Rating effective_memory_rating_v5(
    std::uint32_t point_hits, std::uint32_t point_count, std::uint32_t hint_level,
    bool answer_revealed, bool duration_reliable, std::uint32_t confidence);
[[nodiscard]] MemoryTaskReviewResultV5 review_memory_task_v5(
    const MemoryTaskReviewInputV5& input);
[[nodiscard]] MathTaskReviewResultV5 review_math_task_v5(
    const MathTaskReviewInputV5& input);
[[nodiscard]] std::uint32_t remaining_exam_validations_v5(
    std::int64_t now, std::int64_t exam_at);
// Below the minimum, the frozen baseline is returned. Above it, the empirical
// signal is deliberately shrunk and bounded so a few unusual sessions cannot
// destabilize future intervals.
[[nodiscard]] double personalized_interval_multiplier_v5(
    std::uint32_t sample_count, std::uint32_t success_count, double target_retention);
[[nodiscard]] SessionPlanV5 plan_session_v5(
    const std::vector<SessionTaskV5>& tasks, const LearningProfileV5& profile,
    std::int64_t now, std::uint32_t available_seconds = 0);

}  // namespace reviewfault
