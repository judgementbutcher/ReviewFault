using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using System.IO.Compression;
using System.Text.Json;
using Windows.Foundation;
using Windows.UI;

namespace ReviewFault.Controls;

public sealed class InkPad : StackPanel
{
    private sealed record InkPoint(double X, double Y, float Pressure, long Time);
    private sealed record InkStroke(string Tool, string Color, double Width, List<InkPoint> Points);

    private readonly Canvas canvas = new() { Height = 420, Background = new SolidColorBrush(Color.FromArgb(255, 255, 255, 255)) };
    private readonly List<InkStroke> strokes = [];
    private readonly Stack<List<InkStroke>> undo = new();
    private readonly Stack<List<InkStroke>> redo = new();
    private InkStroke? working;
    private Polyline? workingLine;
    private string tool = "pen";
    private string color = "#ff172033";
    private double width = 0.008;
    private readonly string pageId = Guid.NewGuid().ToString();

    public event EventHandler<byte[]>? DocumentChanged;
    public byte[] Snapshot() => GzipJson();
    public bool HasInk => strokes.Count > 0 || working is not null;

    public InkPad()
    {
        Spacing = 8;
        var tools = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        tools.Children.Add(ToolButton(Symbol.Edit, "画笔", () => tool = "pen"));
        tools.Children.Add(ToolButton(Symbol.Highlight, "荧光笔", () => tool = "highlighter"));
        tools.Children.Add(ToolButton(Symbol.Clear, "橡皮", () => tool = "eraser"));
        tools.Children.Add(ToolButton(Symbol.Undo, "撤销", Undo));
        tools.Children.Add(ToolButton(Symbol.Redo, "重做", Redo));
        tools.Children.Add(ToolButton(Symbol.Delete, "清空演算", Clear));
        Children.Add(tools);

        var options = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        foreach (var (value, shown) in new[] {
            ("#ff172033", Color.FromArgb(255, 23, 32, 51)),
            ("#ffb3261e", Color.FromArgb(255, 179, 38, 30)),
            ("#ff315c49", Color.FromArgb(255, 49, 92, 73)),
            ("#ffd99b52", Color.FromArgb(255, 217, 155, 82)),
        })
        {
            var swatch = new Button { Width = 48, Height = 48, Background = new SolidColorBrush(shown) };
            ToolTipService.SetToolTip(swatch, "选择颜色"); swatch.Click += (_, _) => color = value;
            options.Children.Add(swatch);
        }
        var sizes = new ComboBox { Header = "笔画粗细", Width = 120, ItemsSource = new[] { "细", "中", "粗" }, SelectedIndex = 1 };
        sizes.SelectionChanged += (_, _) => width = sizes.SelectedIndex switch { 0 => 0.004, 2 => 0.014, _ => 0.008 };
        options.Children.Add(sizes); Children.Add(options);

        var frame = new Border {
            Child = canvas, BorderBrush = new SolidColorBrush(Color.FromArgb(255, 210, 215, 222)),
            BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(8),
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        Children.Add(frame);
        canvas.PointerPressed += OnCanvasPointerPressed;
        canvas.PointerMoved += OnCanvasPointerMoved;
        canvas.PointerReleased += OnCanvasPointerReleased;
        canvas.PointerCanceled += OnCanvasPointerReleased;
        canvas.PointerCaptureLost += OnCanvasPointerReleased;
    }

    private Button ToolButton(Symbol symbol, string tooltip, Action action)
    {
        var button = new Button { Content = new SymbolIcon(symbol), Width = 48, Height = 48 };
        ToolTipService.SetToolTip(button, tooltip); button.Click += (_, _) => action(); return button;
    }

    private void OnCanvasPointerPressed(object sender, PointerRoutedEventArgs args)
    {
        var point = args.GetCurrentPoint(canvas);
        Checkpoint(); redo.Clear();
        working = new InkStroke(tool, color, width, []);
        workingLine = LineFor(working);
        canvas.Children.Add(workingLine); Append(point.Position, point.Properties.Pressure);
        canvas.CapturePointer(args.Pointer); args.Handled = true;
    }

    private void OnCanvasPointerMoved(object sender, PointerRoutedEventArgs args)
    {
        if (working is null) return;
        foreach (var point in args.GetIntermediatePoints(canvas).Reverse())
            Append(point.Position, point.Properties.Pressure);
        args.Handled = true;
    }

    private void OnCanvasPointerReleased(object sender, PointerRoutedEventArgs args)
    {
        if (working is null) return;
        if (working.Points.Count > 0) strokes.Add(working);
        working = null; workingLine = null; canvas.ReleasePointerCapture(args.Pointer);
        DocumentChanged?.Invoke(this, GzipJson()); args.Handled = true;
    }

    private void Append(Point point, float pressure)
    {
        if (working is null || workingLine is null) return;
        var x = Math.Clamp(point.X / Math.Max(1, canvas.ActualWidth), 0, 1);
        var y = Math.Clamp(point.Y / Math.Max(1, canvas.ActualHeight), 0, 1);
        if (working.Points.LastOrDefault() is { } previous &&
            Math.Abs(previous.X - x) + Math.Abs(previous.Y - y) < 0.0005) return;
        working.Points.Add(new InkPoint(x, y, Math.Clamp(pressure, 0, 1), DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));
        workingLine.Points.Add(new Point(x * canvas.ActualWidth, y * canvas.ActualHeight));
    }

    private Polyline LineFor(InkStroke stroke)
    {
        var shown = stroke.Tool == "eraser" ? Color.FromArgb(255, 255, 255, 255) : ParseColor(stroke.Color);
        return new Polyline {
            Stroke = new SolidColorBrush(shown), StrokeThickness = stroke.Width * 420,
            StrokeLineJoin = PenLineJoin.Round, StrokeStartLineCap = PenLineCap.Round,
            StrokeEndLineCap = PenLineCap.Round, Opacity = stroke.Tool == "highlighter" ? .35 : 1,
        };
    }

    private void Checkpoint()
    {
        undo.Push(strokes.Select(Clone).ToList()); if (undo.Count <= 100) return;
        var retained = undo.Reverse().TakeLast(100).Reverse().ToArray(); undo.Clear();
        foreach (var value in retained) undo.Push(value);
    }

    private void Undo() { if (undo.Count == 0) return; redo.Push(strokes.Select(Clone).ToList()); Replace(undo.Pop()); }
    private void Redo() { if (redo.Count == 0) return; undo.Push(strokes.Select(Clone).ToList()); Replace(redo.Pop()); }
    private void Clear() { if (strokes.Count == 0) return; Checkpoint(); redo.Clear(); Replace([]); }
    private void Replace(List<InkStroke> replacement)
    {
        strokes.Clear(); strokes.AddRange(replacement.Select(Clone)); Render();
        DocumentChanged?.Invoke(this, GzipJson());
    }
    private void Render()
    {
        canvas.Children.Clear();
        foreach (var stroke in strokes)
        {
            var line = LineFor(stroke);
            foreach (var point in stroke.Points) line.Points.Add(new Point(
                point.X * canvas.ActualWidth, point.Y * canvas.ActualHeight));
            canvas.Children.Add(line);
        }
    }
    private static InkStroke Clone(InkStroke value) => value with { Points = value.Points.ToList() };

    private byte[] GzipJson()
    {
        var document = new {
            format = "reviewfault-ink", version = 1,
            pages = new[] { new {
                id = pageId, backgroundMediaSha256 = (string?)null,
                strokes = strokes.Select(stroke => new {
                    tool = stroke.Tool, color = stroke.Color, width = stroke.Width,
                    points = stroke.Points.Select(point => new {
                        x = point.X, y = point.Y, pressure = point.Pressure,
                        tiltX = 0, tiltY = 0, time = point.Time,
                    }),
                }),
            } },
        };
        using var output = new MemoryStream();
        using (var gzip = new GZipStream(output, CompressionLevel.SmallestSize, leaveOpen: true))
            JsonSerializer.Serialize(gzip, document);
        return output.ToArray();
    }

    private static Color ParseColor(string value) => Color.FromArgb(
        Convert.ToByte(value.Substring(1, 2), 16), Convert.ToByte(value.Substring(3, 2), 16),
        Convert.ToByte(value.Substring(5, 2), 16), Convert.ToByte(value.Substring(7, 2), 16));
}
