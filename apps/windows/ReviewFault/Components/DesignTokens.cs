using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;

namespace ReviewFault.Components;

public static class DesignTokens
{
    public static readonly Windows.UI.Color Brand = Windows.UI.Color.FromArgb(255, 212, 243, 106);
    public static readonly Windows.UI.Color LightBackground = Windows.UI.Color.FromArgb(255, 242, 243, 244);
    public static readonly Windows.UI.Color DarkBackground = Windows.UI.Color.FromArgb(255, 9, 10, 12);
    public static readonly Windows.UI.Color LightHeading = Windows.UI.Color.FromArgb(255, 25, 27, 30);
    public static readonly Windows.UI.Color DarkHeading = Windows.UI.Color.FromArgb(255, 240, 242, 243);
    public static readonly Windows.UI.Color GlassSurface = Windows.UI.Color.FromArgb(255, 18, 20, 23);
    public static readonly Windows.UI.Color GlassBorder = Windows.UI.Color.FromArgb(255, 42, 46, 51);
    public static readonly Windows.UI.Color Purple = Windows.UI.Color.FromArgb(255, 157, 163, 169);
    public static CornerRadius CardRadius => new(8);
    public static Thickness CardPadding => new(18);
    public const double MinimumTarget = 48;

    public static Brush BackgroundBrush => new SolidColorBrush(DarkBackground);

    public static Brush GlassBrush => new SolidColorBrush(GlassSurface);
}
