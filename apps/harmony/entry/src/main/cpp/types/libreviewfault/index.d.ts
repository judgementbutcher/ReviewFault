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

export const abiVersion: () => number;
export const schemaV1: () => string;
export const schemaV2: () => string;
export const review: (
  state: number,
  difficulty: number,
  stabilityDays: number,
  dueAt: number,
  lastReviewedAt: number,
  repetitions: number,
  lapses: number,
  rating: number,
  reviewedAt: number,
  targetRetention: number,
) => ScheduleResult;
export const reviewMemoryV2: typeof review;
export interface MathScheduleResult {
  masteryLevel: number;
  fluentStreak: number;
  dueAt: number;
  repetitions: number;
  scheduledDays: number;
  appliedFeedback: number;
}
export const reviewMathV2: (
  masteryLevel: number, fluentStreak: number, dueAt: number,
  lastReviewedAt: number, repetitions: number, feedback: number,
  errorReason: number, hintRevealed: boolean, reviewedAt: number,
  intensity: number,
) => MathScheduleResult;
