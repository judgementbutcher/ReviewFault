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

public static class NativeScheduler
{
    private const string Library = "reviewfault_core";
    private const uint ExpectedAbiVersion = 1;

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

    private static ScheduleCard ToManaged(CardNative card) => new(
        (CardState)card.State,
        card.Difficulty,
        card.StabilityDays,
        card.DueAt,
        card.LastReviewedAt,
        card.Repetitions,
        card.Lapses);
}

