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
