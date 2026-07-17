#pragma once

#include "reviewfault/scheduler.hpp"
#include "reviewfault/scheduler_v2.hpp"

#include <cstddef>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace reviewfault {

enum class StudyKind : std::int32_t {
  MathProblem = 0,
  MemoryCard = 1,
};

enum class MemoryTemplate : std::int32_t {
  QuestionAnswer = 0,
  Cloze = 1,
  LayeredHint = 2,
  Enumeration = 3,
  ImageOcclusion = 4,
  Comparison = 5,
};

enum class MathAttemptResult : std::int32_t {
  CannotStart = 0,
  Incorrect = 1,
  EffortfulCorrect = 2,
  FluentCorrect = 3,
};

struct ValidationError {
  std::string field;
  std::string code;
};

struct MemoryCardDraft {
  MemoryTemplate template_type = MemoryTemplate::QuestionAnswer;
  std::string prompt_markdown;
  std::string answer_markdown;
  std::vector<std::string> hints;
  std::vector<std::string> answer_points;
  std::size_t image_count = 0;
  std::size_t occlusion_count = 0;
};

struct MathProblemDraft {
  std::string prompt_markdown;
  std::size_t prompt_image_count = 0;
  std::string source_name;
  std::string source_problem_number;
  std::string solution_markdown;
  std::size_t solution_image_count = 0;
};

struct LearningPreferences {
  std::uint32_t daily_new_memory_limit = 20;
  std::uint32_t session_minutes = 20;
  bool enable_data_structures = true;
  bool enable_computer_organization = true;
  bool enable_operating_systems = true;
  bool enable_computer_networks = true;
  bool include_memory_cards = true;
  bool include_math_problems = true;
  MemoryPreset memory_preset = MemoryPreset::Balanced;
  MathIntensity math_intensity = MathIntensity::Balanced;
};

enum class LibraryStatus : std::int32_t {
  All = 0,
  New = 1,
  Due = 2,
  Suspended = 3,
};

struct LibraryFilter {
  std::string query;
  std::vector<std::string> subjects;
  std::vector<StudyKind> kinds;
  std::vector<std::string> tag_ids;
  LibraryStatus status = LibraryStatus::All;
  bool include_deleted = false;
  std::uint32_t offset = 0;
  std::uint32_t limit = 50;
};

struct DeletionState {
  std::vector<std::string> item_ids;
  std::int64_t deleted_at = 0;
  std::int64_t undo_until = 0;

  [[nodiscard]] bool can_undo(std::int64_t now) const {
    return deleted_at > 0 && now >= deleted_at && now <= undo_until;
  }
};

[[nodiscard]] std::vector<ValidationError> validate(const MemoryCardDraft& draft);
[[nodiscard]] std::vector<ValidationError> validate(const MathProblemDraft& draft);
[[nodiscard]] Rating scheduler_rating(MathAttemptResult result);
[[nodiscard]] std::vector<ValidationError> validate(
    const LearningPreferences& preferences);
[[nodiscard]] std::vector<ValidationError> validate(const LibraryFilter& filter);

enum class QueueSection : std::int32_t {
  Overdue = 0,
  DueToday = 1,
  NewToday = 2,
};

struct QueueCandidate {
  std::string id;
  StudyKind kind = StudyKind::MemoryCard;
  CardState scheduler_state = CardState::New;
  std::int64_t due_at = 0;
  std::uint32_t estimated_seconds = 0;
  bool suspended = false;
  std::string chapter_id;
  bool deleted = false;

  QueueCandidate() = default;
  QueueCandidate(std::string item_id, StudyKind item_kind, CardState state,
                 std::int64_t item_due_at, std::uint32_t estimate,
                 bool is_suspended, std::string item_chapter_id = {},
                 bool is_deleted = false)
      : id(std::move(item_id)),
        kind(item_kind),
        scheduler_state(state),
        due_at(item_due_at),
        estimated_seconds(estimate),
        suspended(is_suspended),
        chapter_id(std::move(item_chapter_id)),
        deleted(is_deleted) {}
};

struct QueuePlanConfig {
  std::int64_t now = 0;
  std::int64_t local_day_start_utc = 0;
  // Applies to new 408 memory cards. New math problems remain available for
  // deliberate rework and are not consumed by this daily learning limit.
  std::uint32_t new_item_limit = 20;
  // Zero means no time-budget filtering.
  std::uint32_t available_seconds = 0;
};

struct PlannedItem {
  QueueCandidate item;
  QueueSection section = QueueSection::DueToday;
};

struct QueuePlan {
  std::vector<PlannedItem> items;
  std::uint32_t estimated_seconds = 0;
  std::uint32_t omitted_due_count = 0;
  std::uint32_t omitted_new_count = 0;
};

[[nodiscard]] QueuePlan plan_queue(const std::vector<QueueCandidate>& candidates,
                                   const QueuePlanConfig& config);

}  // namespace reviewfault
