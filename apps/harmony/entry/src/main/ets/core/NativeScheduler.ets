import reviewfault from 'libreviewfault.so';

export interface ScheduleCard {
  state: number;
  difficulty: number;
  stabilityDays: number;
  dueAt: number;
  lastReviewedAt: number;
  repetitions: number;
  lapses: number;
}

export interface ScheduleResult {
  state: number;
  difficulty: number;
  stabilityDays: number;
  dueAt: number;
  repetitions: number;
  lapses: number;
  scheduledDays: number;
  retrievabilityBefore: number;
}

export interface MathScheduleResult {
  masteryLevel: number;
  fluentStreak: number;
  dueAt: number;
  repetitions: number;
  scheduledDays: number;
  appliedFeedback: number;
}

const EXPECTED_ABI_VERSION: number = 2;

export class NativeScheduler {
  static validateAbi(): void {
    if (reviewfault.abiVersion() !== EXPECTED_ABI_VERSION) {
      throw new Error('调度器 ABI 不匹配，请重新安装应用');
    }
    const probe = reviewfault.reviewMemoryV2(0, 0, 0, 0, 0, 0, 0, 3, 1800000000, 1);
    if (probe.state !== 2 || probe.stabilityDays !== 2.3065) {
      throw new Error('调度器黄金样例不一致，请重新安装应用');
    }
  }

  static review(card: ScheduleCard, rating: number, reviewedAt: number,
    targetRetention: number = 0.90): ScheduleResult {
    NativeScheduler.validateAbi();
    return reviewfault.review(
      card.state,
      card.difficulty,
      card.stabilityDays,
      card.dueAt,
      card.lastReviewedAt,
      card.repetitions,
      card.lapses,
      rating,
      reviewedAt,
      targetRetention,
    );
  }

  static reviewMemoryV2(card: ScheduleCard, rating: number, reviewedAt: number,
    preset: number = 1): ScheduleResult {
    NativeScheduler.validateAbi();
    return reviewfault.reviewMemoryV2(card.state, card.difficulty, card.stabilityDays,
      card.dueAt, card.lastReviewedAt, card.repetitions, card.lapses,
      rating, reviewedAt, preset);
  }

  static reviewMathV2(masteryLevel: number, fluentStreak: number, dueAt: number,
    lastReviewedAt: number, repetitions: number, feedback: number,
    errorReason: number, hintRevealed: boolean, reviewedAt: number,
    intensity: number = 1): MathScheduleResult {
    NativeScheduler.validateAbi();
    return reviewfault.reviewMathV2(masteryLevel, fluentStreak, dueAt, lastReviewedAt,
      repetitions, feedback, errorReason, hintRevealed, reviewedAt, intensity);
  }
}
