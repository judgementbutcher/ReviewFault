#include "reviewfault/domain.hpp"

#include <algorithm>
#include <cctype>
#include <limits>
#include <stdexcept>
#include <tuple>

namespace reviewfault {
namespace {

bool is_blank(const std::string& value) {
  return std::all_of(value.begin(), value.end(), [](unsigned char character) {
    return std::isspace(character) != 0;
  });
}

bool has_text(const std::string& value) {
  return !value.empty() && !is_blank(value);
}

bool has_cloze(const std::string& value) {
  const auto opening = value.find("{{c");
  return opening != std::string::npos && value.find("::", opening + 3) != std::string::npos &&
         value.find("}}", opening + 5) != std::string::npos;
}

bool valid_template(MemoryTemplate value) {
  const auto raw = static_cast<std::int32_t>(value);
  return raw >= static_cast<std::int32_t>(MemoryTemplate::QuestionAnswer) &&
         raw <= static_cast<std::int32_t>(MemoryTemplate::Comparison);
}

std::uint32_t safe_add(std::uint32_t left, std::uint32_t right) {
  if (right > std::numeric_limits<std::uint32_t>::max() - left) {
    return std::numeric_limits<std::uint32_t>::max();
  }
  return left + right;
}

std::uint32_t effective_estimate(const QueueCandidate& item) {
  if (item.estimated_seconds != 0) {
    return item.estimated_seconds;
  }
  return item.kind == StudyKind::MathProblem ? 8 * 60 : 45;
}

int kind_priority(StudyKind kind) {
  return kind == StudyKind::MemoryCard ? 0 : 1;
}

bool valid_kind(StudyKind kind) {
  return kind == StudyKind::MathProblem || kind == StudyKind::MemoryCard;
}

void interleave_math_chapters(std::vector<PlannedItem>& items) {
  if (items.size() < 3) {
    return;
  }
  for (std::size_t index = 2; index < items.size(); ++index) {
    const auto& current = items[index];
    const auto& previous = items[index - 1];
    const auto& before_previous = items[index - 2];
    const bool repeats = current.item.kind == StudyKind::MathProblem &&
                         previous.item.kind == StudyKind::MathProblem &&
                         before_previous.item.kind == StudyKind::MathProblem &&
                         !current.item.chapter_id.empty() &&
                         current.item.chapter_id == previous.item.chapter_id &&
                         current.item.chapter_id == before_previous.item.chapter_id;
    if (!repeats) {
      continue;
    }
    const auto section_end = std::find_if(
        items.begin() + static_cast<std::ptrdiff_t>(index + 1), items.end(),
        [&](const PlannedItem& candidate) { return candidate.section != current.section; });
    const auto replacement = std::find_if(
        items.begin() + static_cast<std::ptrdiff_t>(index + 1), section_end,
        [&](const PlannedItem& candidate) {
          return candidate.item.kind != StudyKind::MathProblem ||
                 candidate.item.chapter_id != current.item.chapter_id;
        });
    if (replacement != section_end) {
      std::rotate(items.begin() + static_cast<std::ptrdiff_t>(index), replacement,
                  replacement + 1);
    } else if (section_end != items.end()) {
      index = static_cast<std::size_t>(std::distance(items.begin(), section_end)) - 1;
    } else {
      break;
    }
  }
}

}  // namespace

std::vector<ValidationError> validate(const MemoryCardDraft& draft) {
  std::vector<ValidationError> errors;
  if (!valid_template(draft.template_type)) {
    errors.push_back({"template_type", "invalid_template"});
    return errors;
  }
  if (!has_text(draft.prompt_markdown)) {
    errors.push_back({"prompt_markdown", "prompt_required"});
  }

  switch (draft.template_type) {
    case MemoryTemplate::QuestionAnswer:
    case MemoryTemplate::Comparison:
      if (!has_text(draft.answer_markdown)) {
        errors.push_back({"answer_markdown", "answer_required"});
      }
      break;
    case MemoryTemplate::Cloze:
      if (!has_cloze(draft.prompt_markdown)) {
        errors.push_back({"prompt_markdown", "cloze_marker_required"});
      }
      break;
    case MemoryTemplate::LayeredHint:
      if (!has_text(draft.answer_markdown)) {
        errors.push_back({"answer_markdown", "answer_required"});
      }
      if (draft.hints.empty() ||
          std::any_of(draft.hints.begin(), draft.hints.end(),
                      [](const std::string& hint) { return !has_text(hint); })) {
        errors.push_back({"hints", "non_empty_hints_required"});
      }
      break;
    case MemoryTemplate::Enumeration:
      if (draft.answer_points.size() < 2 ||
          std::any_of(draft.answer_points.begin(), draft.answer_points.end(),
                      [](const std::string& point) { return !has_text(point); })) {
        errors.push_back({"answer_points", "at_least_two_points_required"});
      }
      break;
    case MemoryTemplate::ImageOcclusion:
      if (draft.image_count == 0) {
        errors.push_back({"image_count", "image_required"});
      }
      if (draft.occlusion_count == 0) {
        errors.push_back({"occlusion_count", "occlusion_required"});
      }
      break;
  }
  return errors;
}

std::vector<ValidationError> validate(const MathProblemDraft& draft) {
  std::vector<ValidationError> errors;
  if (!has_text(draft.prompt_markdown) && draft.prompt_image_count == 0) {
    errors.push_back({"prompt", "text_or_image_required"});
  }
  return errors;
}

Rating scheduler_rating(MathAttemptResult result) {
  switch (result) {
    case MathAttemptResult::CannotStart:
    case MathAttemptResult::Incorrect:
      return Rating::Again;
    case MathAttemptResult::EffortfulCorrect:
      return Rating::Hard;
    case MathAttemptResult::FluentCorrect:
      return Rating::Easy;
  }
  throw std::invalid_argument("math attempt result is invalid");
}

std::vector<ValidationError> validate(const LearningPreferences& preferences) {
  std::vector<ValidationError> errors;
  if (preferences.daily_new_memory_limit > 500) {
    errors.push_back({"daily_new_memory_limit", "limit_out_of_range"});
  }
  if (preferences.session_minutes == 0 || preferences.session_minutes > 240) {
    errors.push_back({"session_minutes", "duration_out_of_range"});
  }
  if (!preferences.include_memory_cards && !preferences.include_math_problems) {
    errors.push_back({"queue_filter", "at_least_one_kind_required"});
  }
  try {
    (void)target_retention(preferences.memory_preset);
  } catch (const std::invalid_argument&) {
    errors.push_back({"memory_preset", "invalid_preset"});
  }
  try {
    (void)interval_multiplier(preferences.math_intensity);
  } catch (const std::invalid_argument&) {
    errors.push_back({"math_intensity", "invalid_intensity"});
  }
  return errors;
}

std::vector<ValidationError> validate(const LibraryFilter& filter) {
  std::vector<ValidationError> errors;
  if (filter.limit == 0 || filter.limit > 200) {
    errors.push_back({"limit", "page_size_out_of_range"});
  }
  if (std::any_of(filter.kinds.begin(), filter.kinds.end(),
                  [](StudyKind kind) { return !valid_kind(kind); })) {
    errors.push_back({"kinds", "invalid_kind"});
  }
  const auto status = static_cast<std::int32_t>(filter.status);
  if (status < 0 || status > 3) {
    errors.push_back({"status", "invalid_status"});
  }
  return errors;
}

QueuePlan plan_queue(const std::vector<QueueCandidate>& candidates,
                     const QueuePlanConfig& config) {
  if (config.now <= 0 || config.local_day_start_utc <= 0 ||
      config.local_day_start_utc > config.now) {
    throw std::invalid_argument("queue plan times are invalid");
  }

  std::vector<PlannedItem> due;
  std::vector<PlannedItem> fresh;
  for (const auto& candidate : candidates) {
    if (candidate.id.empty() || candidate.suspended || candidate.deleted) {
      continue;
    }
    if (candidate.scheduler_state == CardState::New) {
      fresh.push_back({candidate, QueueSection::NewToday});
      continue;
    }
    if (candidate.due_at <= config.now) {
      due.push_back({candidate,
                     candidate.due_at < config.local_day_start_utc
                         ? QueueSection::Overdue
                         : QueueSection::DueToday});
    }
  }

  std::stable_sort(due.begin(), due.end(), [](const PlannedItem& left,
                                               const PlannedItem& right) {
    if (left.section != right.section) {
      return left.section < right.section;
    }
    if (left.section == QueueSection::Overdue) {
      const bool left_short = effective_estimate(left.item) <= 90;
      const bool right_short = effective_estimate(right.item) <= 90;
      if (left_short != right_short) {
        return left_short;
      }
    }
    return std::tuple(kind_priority(left.item.kind), left.item.due_at, left.item.id) <
           std::tuple(kind_priority(right.item.kind), right.item.due_at, right.item.id);
  });
  std::stable_sort(fresh.begin(), fresh.end(), [](const PlannedItem& left,
                                                   const PlannedItem& right) {
    return std::tuple(kind_priority(left.item.kind), left.item.id) <
           std::tuple(kind_priority(right.item.kind), right.item.id);
  });
  interleave_math_chapters(due);
  interleave_math_chapters(fresh);

  QueuePlan plan;
  std::uint32_t used_new = 0;
  auto try_append = [&](const PlannedItem& planned, bool is_new) {
    const auto estimate = effective_estimate(planned.item);
    const bool exceeds_budget =
        config.available_seconds != 0 && !plan.items.empty() &&
        (estimate > config.available_seconds -
                        std::min(plan.estimated_seconds, config.available_seconds));
    if (exceeds_budget) {
      if (is_new) {
        ++plan.omitted_new_count;
      } else {
        ++plan.omitted_due_count;
      }
      return;
    }
    plan.items.push_back(planned);
    plan.estimated_seconds = safe_add(plan.estimated_seconds, estimate);
  };

  for (const auto& item : due) {
    try_append(item, false);
  }
  for (const auto& item : fresh) {
    if (used_new >= config.new_item_limit) {
      ++plan.omitted_new_count;
      continue;
    }
    const auto before = plan.items.size();
    try_append(item, true);
    if (plan.items.size() != before) {
      ++used_new;
    }
  }
  return plan;
}

}  // namespace reviewfault
