using ReviewFault.Data;

namespace ReviewFault.ViewModels;

public enum AppDestination { Today, Library, Add, Settings, Trash, Review }

public sealed class AppViewModel
{
    public AppRepository Repository { get; } = new();
    public AppDestination Destination { get; private set; } = AppDestination.Today;
    public event Action<AppDestination>? DestinationChanged;

    public async Task InitializeAsync() => await Repository.InitializeAsync();

    public void Navigate(AppDestination destination)
    {
        Destination = destination;
        DestinationChanged?.Invoke(destination);
    }
}
