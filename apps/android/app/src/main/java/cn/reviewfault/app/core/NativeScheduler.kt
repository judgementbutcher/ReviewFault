package cn.reviewfault.app.core

data class NativeScheduleResult(
    val state: Int,
    val difficulty: Double,
    val stabilityDays: Double,
    val dueAt: Long,
    val repetitions: Int,
    val lapses: Int,
    val scheduledDays: Double,
    val retrievabilityBefore: Double,
)

data class NativeMathScheduleResult(
    val masteryLevel: Int,
    val fluentStreak: Int,
    val dueAt: Long,
    val repetitions: Int,
    val scheduledDays: Double,
    val appliedFeedback: Int,
)

object NativeScheduler {
    init {
        System.loadLibrary("reviewfault")
        check(nativeAbiVersion() == EXPECTED_ABI_VERSION) {
            "调度器 ABI 不匹配，请重新安装应用"
        }
        val probe = nativeReviewMemoryV2(
            state = 0, difficulty = 0.0, stabilityDays = 0.0,
            dueAt = 0, lastReviewedAt = 0, repetitions = 0, lapses = 0,
            rating = 3, reviewedAt = 1_800_000_000, preset = 1,
        )
        check(probe.state == 2 && probe.stabilityDays == 2.3065) {
            "调度器黄金样例不一致，请重新安装应用"
        }
    }

    private const val EXPECTED_ABI_VERSION = 2

    external fun nativeAbiVersion(): Int

    external fun nativeReview(
        state: Int,
        difficulty: Double,
        stabilityDays: Double,
        dueAt: Long,
        lastReviewedAt: Long,
        repetitions: Int,
        lapses: Int,
        rating: Int,
        reviewedAt: Long,
        targetRetention: Double = 0.90,
    ): NativeScheduleResult

    external fun nativeReviewMemoryV2(
        state: Int,
        difficulty: Double,
        stabilityDays: Double,
        dueAt: Long,
        lastReviewedAt: Long,
        repetitions: Int,
        lapses: Int,
        rating: Int,
        reviewedAt: Long,
        preset: Int,
    ): NativeScheduleResult

    external fun nativeReviewMathV2(
        masteryLevel: Int,
        fluentStreak: Int,
        dueAt: Long,
        lastReviewedAt: Long,
        repetitions: Int,
        feedback: Int,
        errorReason: Int,
        hintRevealed: Boolean,
        reviewedAt: Long,
        intensity: Int,
    ): NativeMathScheduleResult
}
