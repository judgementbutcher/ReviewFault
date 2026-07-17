#include "reviewfault/domain.hpp"

#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

using namespace reviewfault;
int failures = 0;

void expect(bool condition, const std::string& message) {
  if (!condition) {
    ++failures;
    std::cerr << "FAIL: " << message << '\n';
  }
}

bool has_error(const std::vector<ValidationError>& errors, const std::string& code) {
  for (const auto& error : errors) {
    if (error.code == code) {
      return true;
    }
  }
  return false;
}

void test_memory_templates() {
  MemoryCardDraft qa;
  qa.prompt_markdown = "什么是 Belady 异常？";
  expect(has_error(validate(qa), "answer_required"), "Q&A requires an answer");
  qa.answer_markdown = "增加物理块数时缺页次数反而增加。";
  expect(validate(qa).empty(), "complete Q&A is valid");

  MemoryCardDraft cloze;
  cloze.template_type = MemoryTemplate::Cloze;
  cloze.prompt_markdown = "TCP 使用滑动窗口。";
  expect(has_error(validate(cloze), "cloze_marker_required"),
         "cloze requires a structured marker");
  cloze.prompt_markdown = "TCP 使用 {{c1::滑动窗口}} 实现流量控制。";
  expect(validate(cloze).empty(), "marked cloze is valid");

  MemoryCardDraft layered;
  layered.template_type = MemoryTemplate::LayeredHint;
  layered.prompt_markdown = "死锁的四个必要条件是什么？";
  layered.answer_markdown = "互斥、请求并保持、不可剥夺、循环等待";
  expect(has_error(validate(layered), "non_empty_hints_required"),
         "layered card requires at least one hint");
  layered.hints = {"从资源占用关系考虑", "其中一个条件涉及环"};
  expect(validate(layered).empty(), "layered hint card is valid");

  MemoryCardDraft enumeration;
  enumeration.template_type = MemoryTemplate::Enumeration;
  enumeration.prompt_markdown = "进程的基本状态有哪些？";
  enumeration.answer_points = {"运行", "就绪", "阻塞"};
  expect(validate(enumeration).empty(), "enumeration accepts structured points");

  MemoryCardDraft occlusion;
  occlusion.template_type = MemoryTemplate::ImageOcclusion;
  occlusion.prompt_markdown = "填写 IPv4 首部字段";
  occlusion.image_count = 1;
  expect(has_error(validate(occlusion), "occlusion_required"),
         "image occlusion needs at least one mask");
  occlusion.occlusion_count = 3;
  expect(validate(occlusion).empty(), "image with masks is valid");
}

void test_math_capture_and_rating() {
  MathProblemDraft draft;
  expect(has_error(validate(draft), "text_or_image_required"),
         "empty math problem is rejected");
  draft.prompt_image_count = 1;
  expect(validate(draft).empty(), "one image is enough for quick capture");

  expect(scheduler_rating(MathAttemptResult::CannotStart) == Rating::Again,
         "cannot start maps to Again");
  expect(scheduler_rating(MathAttemptResult::Incorrect) == Rating::Again,
         "incorrect maps to Again");
  expect(scheduler_rating(MathAttemptResult::EffortfulCorrect) == Rating::Hard,
         "effortful success maps to Hard");
  expect(scheduler_rating(MathAttemptResult::FluentCorrect) == Rating::Easy,
         "fluent success maps to Easy");
}

void test_queue_order_and_sections() {
  constexpr std::int64_t now = 1'800'050'000;
  constexpr std::int64_t day_start = 1'800'000'000;
  const std::vector<QueueCandidate> candidates = {
      {"math-overdue", StudyKind::MathProblem, CardState::Review,
       day_start - 100, 600, false},
      {"card-overdue-long", StudyKind::MemoryCard, CardState::Review,
       day_start - 200, 120, false},
      {"card-overdue-short", StudyKind::MemoryCard, CardState::Review,
       day_start - 50, 45, false},
      {"card-due", StudyKind::MemoryCard, CardState::Review, now, 45, false},
      {"math-due", StudyKind::MathProblem, CardState::Review, now - 1, 480, false},
      {"future", StudyKind::MemoryCard, CardState::Review, now + 1, 45, false},
      {"new-math", StudyKind::MathProblem, CardState::New, 0, 300, false},
      {"new-card", StudyKind::MemoryCard, CardState::New, 0, 45, false},
      {"suspended", StudyKind::MemoryCard, CardState::Review, 1, 45, true},
  };
  QueuePlanConfig config{now, day_start, 1, 0};
  const auto plan = plan_queue(candidates, config);
  expect(plan.items.size() == 6, "queue includes five due items and one new item");
  expect(plan.items[0].item.id == "card-overdue-short",
         "short overdue reviews come first");
  expect(plan.items[0].section == QueueSection::Overdue,
         "pre-day-start item is overdue");
  expect(plan.items[3].item.id == "card-due",
         "408 due item precedes math due item");
  expect(plan.items[5].item.id == "new-card", "new 408 item precedes new math");
  expect(plan.omitted_new_count == 1, "new item limit is reported");
}

void test_queue_time_budget() {
  constexpr std::int64_t now = 1'800'050'000;
  const std::vector<QueueCandidate> candidates = {
      {"first", StudyKind::MemoryCard, CardState::Review, now - 2, 45, false},
      {"second", StudyKind::MemoryCard, CardState::Review, now - 1, 45, false},
      {"math", StudyKind::MathProblem, CardState::Review, now, 600, false},
  };
  const auto plan = plan_queue(candidates, {now, now - 1000, 0, 80});
  expect(plan.items.size() == 1, "time budget keeps the first fitting item");
  expect(plan.omitted_due_count == 2, "omitted due count remains visible");

  const auto tiny = plan_queue({candidates.back()}, {now, now - 1000, 0, 10});
  expect(tiny.items.size() == 1,
         "a non-empty queue always offers at least one item even over budget");
}

void test_queue_validation() {
  try {
    (void)plan_queue({}, {0, 0, 0, 0});
    expect(false, "invalid queue time is rejected");
  } catch (const std::invalid_argument&) {
  }
}

}  // namespace

int main() {
  test_memory_templates();
  test_math_capture_and_rating();
  test_queue_order_and_sections();
  test_queue_time_budget();
  test_queue_validation();
  if (failures != 0) {
    std::cerr << failures << " domain test(s) failed\n";
    return EXIT_FAILURE;
  }
  std::cout << "All domain tests passed\n";
  return EXIT_SUCCESS;
}

