using Microsoft.UI.Xaml;

namespace ReviewFault;

public sealed class App : Application
{
    private Window? window;

    public App()
    {
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        window = new MainWindow();
        window.Activate();
    }
}
