using System.Runtime.InteropServices;

namespace PhonePadService;

public static class MouseInjector
{
    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint type;
        public MOUSEINPUT mi;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    private const int INPUT_MOUSE = 0;
    private const uint MOUSEEVENTF_MOVE = 0x0001;
    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    private const uint MOUSEEVENTF_LEFTUP = 0x0004;
    private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    private const uint MOUSEEVENTF_WHEEL = 0x0800;

    public static void Move(int dx, int dy)
    {
        var input = new INPUT
        {
            type = INPUT_MOUSE,
            mi = new MOUSEINPUT { dx = dx, dy = dy, dwFlags = MOUSEEVENTF_MOVE }
        };
        SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }

    public static void SendLeftClick()
    {
        SendInputEvent(MOUSEEVENTF_LEFTDOWN);
        SendInputEvent(MOUSEEVENTF_LEFTUP);
    }

    public static void SendLeftDown() => SendInputEvent(MOUSEEVENTF_LEFTDOWN);
    public static void SendLeftUp() => SendInputEvent(MOUSEEVENTF_LEFTUP);
    
    public static void SendRightClick()
    {
        SendInputEvent(MOUSEEVENTF_RIGHTDOWN);
        SendInputEvent(MOUSEEVENTF_RIGHTUP);
    }

    public static void Scroll(int dy)
    {
        var input = new INPUT
        {
            type = INPUT_MOUSE,
            mi = new MOUSEINPUT { mouseData = (uint)(dy * 120), dwFlags = MOUSEEVENTF_WHEEL }
        };
        SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }

    private static void SendInputEvent(uint flag)
    {
        var input = new INPUT
        {
            type = INPUT_MOUSE,
            mi = new MOUSEINPUT { dwFlags = flag }
        };
        SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }
}
