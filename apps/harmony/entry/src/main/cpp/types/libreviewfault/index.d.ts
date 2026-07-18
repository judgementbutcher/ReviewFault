export const abiVersion: () => number;
export interface ReviewActionV4 {
  actionId: string;
  deviceId: string;
  deviceCounter: number;
  causalCursor: number;
  feedback: number;
  reviewedAt: number;
}
export const canonicalOrderV4: (actions: ReviewActionV4[]) => number[];
export interface MemoryReviewInput {
  state: number; difficulty: number; stabilityDays: number; dueAt: number; lastReviewedAt: number;
  repetitions: number; lapses: number; rating: number; preset: number; reviewedAt: number;
  historyCount: number; calibrationImprovement: number; consecutiveLapses: number;
}
export interface MemoryReviewResult {
  state: number; difficulty: number; stabilityDays: number; dueAt: number; lastReviewedAt: number;
  repetitions: number; lapses: number; parameterVersion: number; decisionFlags: number; scheduledDays: number;
}
export interface MathReviewInput {
  masteryLevel: number; fluentStreak: number; dueAt: number; lastReviewedAt: number; repetitions: number;
  feedback: number; errorReason: number; hintRevealed: number; intensity: number; reviewedAt: number;
  durationSeconds: number; durationQuality: number; consecutiveFailures: number;
}
export interface MathReviewResult {
  masteryLevel: number; fluentStreak: number; dueAt: number; lastReviewedAt: number; repetitions: number;
  appliedFeedback: number; parameterVersion: number; decisionFlags: number; scheduledDays: number;
}
export const reviewMemoryV3: (input: MemoryReviewInput) => MemoryReviewResult;
export const reviewMathV3: (input: MathReviewInput) => MathReviewResult;
