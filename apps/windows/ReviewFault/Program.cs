using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;

namespace ReviewFault;

public static class Program
{
    private static App? application;

    [STAThread]
    public static void Main(string[] args)
    {
        WinRT.ComWrappersSupport.InitializeComWrappers();
        Application.Start(_ =>
        {
            var context = new DispatcherQueueSynchronizationContext(
                DispatcherQueue.GetForCurrentThread());
            SynchronizationContext.SetSynchronizationContext(context);
            application = new App();
        });
    }
}
