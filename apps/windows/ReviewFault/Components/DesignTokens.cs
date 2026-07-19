using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;

namespace ReviewFault.Components;

public static class DesignTokens
{
    public static readonly Windows.UI.Color Brand = Windows.UI.Color.FromArgb(255, 120, 232, 193);
    public static readonly Windows.UI.Color LightBackground = Windows.UI.Color.FromArgb(255, 247, 245, 239);
    public static readonly Windows.UI.Color DarkBackground = Windows.UI.Color.FromArgb(255, 7, 17, 15);
    public static readonly Windows.UI.Color LightHeading = Windows.UI.Color.FromArgb(255, 30, 54, 43);
    public static readonly Windows.UI.Color DarkHeading = Windows.UI.Color.FromArgb(255, 229, 240, 236);
    public static readonly Windows.UI.Color GlassSurface = Windows.UI.Color.FromArgb(220, 16, 28, 25);
    public static readonly Windows.UI.Color GlassBorder = Windows.UI.Color.FromArgb(130, 62, 91, 82);
    public static readonly Windows.UI.Color Purple = Windows.UI.Color.FromArgb(255, 201, 184, 255);
    public static CornerRadius CardRadius => new(22);
    public static Thickness CardPadding => new(20);
    public const double MinimumTarget = 48;

    public static Brush BackgroundBrush => new LinearGradientBrush
    {
        StartPoint = new Windows.Foundation.Point(0, 0),
        EndPoint = new Windows.Foundation.Point(1, 1),
        GradientStops =
        {
            new GradientStop { Color = DarkBackground, Offset = 0 },
            new GradientStop { Color = Windows.UI.Color.FromArgb(255, 10, 27, 27), Offset = .52 },
            new GradientStop { Color = Windows.UI.Color.FromArgb(255, 18, 17, 34), Offset = 1 },
        },
    };

    public static Brush GlassBrush => new AcrylicBrush
    {
        TintColor = GlassSurface,
        TintOpacity = .84,
        FallbackColor = GlassSurface,
    };
}
