using System.Runtime.InteropServices;
using System.Text;

namespace ReviewFault.Core;

public enum Rating : int
{
    Again = 1,
    Hard = 2,
    Good = 3,
    Easy = 4,
}

public enum CardState : int
{
    New = 0,
    Learning = 1,
    Review = 2,
    Relearning = 3,
}

[StructLayout(LayoutKind.Sequential)]
internal struct SchedulerConfigNative
{
    public uint StructSize;
    public double TargetRetention;
    public double MaximumIntervalDays;
    public double MinimumReviewIntervalDays;
    public long AgainStepSeconds;
    public long HardStepSeconds;
}

[StructLayout(LayoutKind.Sequential)]
internal struct CardNative
{
    public uint StructSize;
    public int State;
    public double Difficulty;
    public double StabilityDays;
    public long DueAt;
    public long LastReviewedAt;
    public uint Repetitions;
    public uint Lapses;
}

[StructLayout(LayoutKind.Sequential)]
internal struct ReviewLogNative
{
    public uint StructSize;
    public int Rating;
    public int StateBefore;
    public int StateAfter;
    public long ReviewedAt;
    public double ElapsedDays;
    public double ScheduledDays;
    public double RetrievabilityBefore;
    public double DifficultyBefore;
    public double DifficultyAfter;
    public double StabilityBefore;
    public double StabilityAfter;
}

[StructLayout(LayoutKind.Sequential)]
internal struct ReviewResultNative
{
    public uint StructSize;
    public CardNative Card;
    public ReviewLogNative Log;
}

[StructLayout(LayoutKind.Sequential)]
internal struct MemoryStateV2Native
{
    public uint StructSize; public int State; public double Difficulty;
    public double StabilityDays; public long DueAt; public long LastReviewedAt;
    public uint Repetitions; public uint Lapses;
}

[StructLayout(LayoutKind.Sequential)]
internal struct MemoryInputV2Native
{
    public uint StructSize; public int Rating; public int Preset; public long ReviewedAt;
}

[StructLayout(LayoutKind.Sequential)]
internal struct MemoryEventV2Native
{
    public uint StructSize, AlgorithmVersion, ParameterVersion;
    public int Rating, Preset, StateBefore, StateAfter;
    public long ReviewedAt, DueAtBefore, DueAtAfter;
    public double TargetRetention, ElapsedDays, ScheduledDays, RetrievabilityBefore;
    public double DifficultyBefore, DifficultyAfter, StabilityBefore, StabilityAfter;
}

[StructLayout(LayoutKind.Sequential)]
internal struct MemoryResultV2Native
{
    public uint StructSize; public MemoryStateV2Native State; public MemoryEventV2Native Event;
}

[StructLayout(LayoutKind.Sequential)]
internal struct MathStateV2Native
{
    public uint StructSize, MasteryLevel, FluentStreak;
    public long DueAt, LastReviewedAt; public uint Repetitions;
}

[StructLayout(LayoutKind.Sequential)]
internal struct MathInputV2Native
{
    public uint StructSize; public int Feedback, ErrorReason, HintRevealed, Intensity;
    public long ReviewedAt;
}

[StructLayout(LayoutKind.Sequential)]
internal struct MathEventV2Native
{
    public uint StructSize, AlgorithmVersion, ParameterVersion;
    public int RequestedFeedback, AppliedFeedback, ErrorReason, Intensity, HintRevealed;
    public uint MasteryBefore, MasteryAfter, FluentStreakBefore, FluentStreakAfter;
    public long ReviewedAt, DueAtBefore, DueAtAfter; public double ScheduledDays;
}

[StructLayout(LayoutKind.Sequential)]
internal struct MathResultV2Native
{
    public uint StructSize; public MathStateV2Native State; public MathEventV2Native Event;
}

public sealed record ScheduleCard(
    CardState State,
    double Difficulty,
    double StabilityDays,
    long DueAt,
    long LastReviewedAt,
    uint Repetitions,
    uint Lapses);

public sealed record ScheduleResult(
    ScheduleCard Card,
    double ElapsedDays,
    double ScheduledDays,
    double RetrievabilityBefore);

public sealed record MathScheduleResult(
    uint MasteryLevel, uint FluentStreak, long DueAt, long LastReviewedAt,
    uint Repetitions, double ScheduledDays, int AppliedFeedback);

public static class NativeScheduler
{
    private const string Library = "reviewfault_core";
    private const uint ExpectedAbiVersion = 2;

    [DllImport(Library, CallingConvention = CallingConvention.Cdecl)]
    private static extern uint rf_scheduler_abi_version();

    [DllImport(Library, CallingConvention = CallingConvention.Cdecl)]
    private static extern nuint rf_scheduler_config_size();

    [DllImport(Library, CallingConvention = CallingConvention.Cdecl)]
    private static extern nuint rf_card_size();

    [DllImport(Library, CallingConvention = CallingConvention.Cdecl)]
    private static extern nuint rf_review_result_size();

    [DllImport(Library, CallingConvention = CallingConvention.Cdecl)]
    private static extern SchedulerConfigNative rf_default_scheduler_config();

    [DllImport(Library, CallingConvention = CallingConvention.Cdecl)]
    private static extern CardNative rf_new_card();

    [DllImport(Library, CallingConvention = CallingConvention.Cdecl)]
    private static extern nuint rf_memory_schedule_state_v2_size();
    [DllImport(Library, CallingConvention = CallingConvention.Cdecl)]
    private static extern nuint rf_memory_review_result_v2_size();
    [DllImport(Library, CallingConvention = CallingConvention.Cdecl)]
    private static extern nuint rf_math_schedule_state_v2_size();
    [DllImport(Library, CallingConvention = CallingConvention.Cdecl)]
    private static extern nuint rf_math_review_result_v2_size();
    [DllImport(Library, CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Ansi)]
    private static extern int review_memory_v2(in MemoryStateV2Native state,
        in MemoryInputV2Native input, ref MemoryResultV2Native result,
        StringBuilder errorBuffer, nuint errorBufferSize);
    [DllImport(Library, CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Ansi)]
    private static extern int review_math_v2(in MathStateV2Native state,
        in MathInputV2Native input, ref MathResultV2Native result,
        StringBuilder errorBuffer, nuint errorBufferSize);

    [DllImport(Library, CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Ansi)]
    private static extern int rf_review(
        in SchedulerConfigNative config,
        in CardNative card,
        int rating,
        long reviewedAt,
        ref ReviewResultNative result,
        StringBuilder errorBuffer,
        nuint errorBufferSize);

    public static void ValidateAbi()
    {
        if (rf_scheduler_abi_version() != ExpectedAbiVersion ||
            rf_scheduler_config_size() != (nuint)Marshal.SizeOf<SchedulerConfigNative>() ||
            rf_card_size() != (nuint)Marshal.SizeOf<CardNative>() ||
            rf_review_result_size() != (nuint)Marshal.SizeOf<ReviewResultNative>())
        {
            throw new InvalidOperationException("调度器 ABI 不匹配，请重新安装应用。");
        }
        if (rf_memory_schedule_state_v2_size() != (nuint)Marshal.SizeOf<MemoryStateV2Native>() ||
            rf_memory_review_result_v2_size() != (nuint)Marshal.SizeOf<MemoryResultV2Native>() ||
            rf_math_schedule_state_v2_size() != (nuint)Marshal.SizeOf<MathStateV2Native>() ||
            rf_math_review_result_v2_size() != (nuint)Marshal.SizeOf<MathResultV2Native>())
            throw new InvalidOperationException("调度器 ABI v2 结构不匹配，请重新安装应用。");
    }

    public static ScheduleCard NewCard()
    {
        ValidateAbi();
        return ToManaged(rf_new_card());
    }

    public static ScheduleResult Review(
        ScheduleCard card,
        Rating rating,
        long reviewedAt,
        double targetRetention = 0.90)
    {
        ValidateAbi();
        var config = rf_default_scheduler_config();
        config.TargetRetention = targetRetention;
        var nativeCard = new CardNative
        {
            StructSize = (uint)Marshal.SizeOf<CardNative>(),
            State = (int)card.State,
            Difficulty = card.Difficulty,
            StabilityDays = card.StabilityDays,
            DueAt = card.DueAt,
            LastReviewedAt = card.LastReviewedAt,
            Repetitions = card.Repetitions,
            Lapses = card.Lapses,
        };
        var result = new ReviewResultNative
        {
            StructSize = (uint)Marshal.SizeOf<ReviewResultNative>(),
        };
        var error = new StringBuilder(256);
        var status = rf_review(
            in config,
            in nativeCard,
            (int)rating,
            reviewedAt,
            ref result,
            error,
            (nuint)error.Capacity);
        if (status != 0)
        {
            throw new ArgumentException(error.ToString());
        }
        return new ScheduleResult(
            ToManaged(result.Card),
            result.Log.ElapsedDays,
            result.Log.ScheduledDays,
            result.Log.RetrievabilityBefore);
    }

    public static ScheduleResult ReviewMemoryV2(
        ScheduleCard card, Rating rating, long reviewedAt, int preset = 1)
    {
        ValidateAbi();
        var state = new MemoryStateV2Native {
            StructSize = (uint)Marshal.SizeOf<MemoryStateV2Native>(), State = (int)card.State,
            Difficulty = card.Difficulty, StabilityDays = card.StabilityDays,
            DueAt = card.DueAt, LastReviewedAt = card.LastReviewedAt,
            Repetitions = card.Repetitions, Lapses = card.Lapses };
        var input = new MemoryInputV2Native {
            StructSize = (uint)Marshal.SizeOf<MemoryInputV2Native>(), Rating = (int)rating,
            Preset = preset, ReviewedAt = reviewedAt };
        var result = new MemoryResultV2Native { StructSize = (uint)Marshal.SizeOf<MemoryResultV2Native>() };
        var error = new StringBuilder(256);
        var status = review_memory_v2(in state, in input, ref result, error, (nuint)error.Capacity);
        if (status != 0) throw new ArgumentException(error.ToString());
        return new ScheduleResult(new ScheduleCard((CardState)result.State.State,
            result.State.Difficulty, result.State.StabilityDays, result.State.DueAt,
            result.State.LastReviewedAt, result.State.Repetitions, result.State.Lapses),
            result.Event.ElapsedDays, result.Event.ScheduledDays,
            result.Event.RetrievabilityBefore);
    }

    public static MathScheduleResult ReviewMathV2(
        uint masteryLevel, uint fluentStreak, long dueAt, long lastReviewedAt,
        uint repetitions, int feedback, int errorReason, bool hintRevealed,
        long reviewedAt, int intensity = 1)
    {
        ValidateAbi();
        var state = new MathStateV2Native { StructSize = (uint)Marshal.SizeOf<MathStateV2Native>(),
            MasteryLevel = masteryLevel, FluentStreak = fluentStreak, DueAt = dueAt,
            LastReviewedAt = lastReviewedAt, Repetitions = repetitions };
        var input = new MathInputV2Native { StructSize = (uint)Marshal.SizeOf<MathInputV2Native>(),
            Feedback = feedback, ErrorReason = errorReason, HintRevealed = hintRevealed ? 1 : 0,
            Intensity = intensity, ReviewedAt = reviewedAt };
        var result = new MathResultV2Native { StructSize = (uint)Marshal.SizeOf<MathResultV2Native>() };
        var error = new StringBuilder(256);
        var status = review_math_v2(in state, in input, ref result, error, (nuint)error.Capacity);
        if (status != 0) throw new ArgumentException(error.ToString());
        return new MathScheduleResult(result.State.MasteryLevel, result.State.FluentStreak,
            result.State.DueAt, result.State.LastReviewedAt, result.State.Repetitions,
            result.Event.ScheduledDays, result.Event.AppliedFeedback);
    }

    private static ScheduleCard ToManaged(CardNative card) => new(
        (CardState)card.State,
        card.Difficulty,
        card.StabilityDays,
        card.DueAt,
        card.LastReviewedAt,
        card.Repetitions,
        card.Lapses);
}
