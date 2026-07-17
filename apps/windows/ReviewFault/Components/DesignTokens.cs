using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;

namespace ReviewFault.Components;

public static class DesignTokens
{
    public static readonly Windows.UI.Color Brand = Windows.UI.Color.FromArgb(255, 49, 92, 73);
    public static readonly Windows.UI.Color LightBackground = Windows.UI.Color.FromArgb(255, 247, 245, 239);
    public static readonly Windows.UI.Color DarkBackground = Windows.UI.Color.FromArgb(255, 17, 24, 20);
    public static readonly Windows.UI.Color LightHeading = Windows.UI.Color.FromArgb(255, 30, 54, 43);
    public static readonly Windows.UI.Color DarkHeading = Windows.UI.Color.FromArgb(255, 216, 232, 222);
    public static CornerRadius CardRadius => new(12);
    public static Thickness CardPadding => new(20);
    public const double MinimumTarget = 48;
}
