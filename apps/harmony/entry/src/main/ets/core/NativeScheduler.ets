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

const EXPECTED_ABI_VERSION: number = 1;

export class NativeScheduler {
  static validateAbi(): void {
    if (reviewfault.abiVersion() !== EXPECTED_ABI_VERSION) {
      throw new Error('调度器 ABI 不匹配，请重新安装应用');
    }
    const probe = reviewfault.review(0, 0, 0, 0, 0, 0, 0, 3, 1800000000, 0.90);
    if (probe.state !== 2 || probe.stabilityDays !== 2 || probe.dueAt !== 1800172800) {
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
}
