package com.tactile.client.input;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

/**
 * Minimal user32 bindings through JNA 3.4.0, which ships on Minecraft 1.8.9's classpath. Every entry
 * point here is thread-safe in Win32 (GetAsyncKeyState reads the global async key state and does not
 * need the window's message thread). Use {@link #get()}; it is null when not on Windows or if loading failed.
 */
public final class Win32
{
    public interface User32 extends StdCallLibrary
    {
        short GetAsyncKeyState(int vKey);

        Pointer GetForegroundWindow();

        int MapVirtualKeyA(int uCode, int uMapType);

        void keybd_event(byte bVk, byte bScan, int dwFlags, Pointer dwExtraInfo);

        // Raw Input (RawKeyInput). Structures are laid out by hand in Memory buffers: JNA 3.4.0 predates
        // Structure.getFieldOrder, so its field order is not reliable.
        Pointer CreateWindowExW(int exStyle, WString className, WString windowName, int style, int x, int y, int width, int height,
            Pointer parent, Pointer menu, Pointer instance, Pointer param);

        boolean DestroyWindow(Pointer hwnd);

        boolean RegisterRawInputDevices(Pointer devices, int count, int size);

        int GetRawInputData(Pointer rawInput, int command, Pointer data, IntByReference size, int headerSize);

        int GetMessageW(Pointer msg, Pointer hwnd, int filterMin, int filterMax);

        Pointer DispatchMessageW(Pointer msg);

        boolean PostMessageW(Pointer hwnd, int msg, Pointer wParam, Pointer lParam);
    }

    public interface Kernel32 extends StdCallLibrary
    {
        Pointer CreateWaitableTimerExW(Pointer attrs, Pointer name, int flags, int access);

        boolean SetWaitableTimer(Pointer timer, long[] dueTime, int period, Pointer cb, Pointer arg, boolean resume);

        int WaitForSingleObject(Pointer handle, int ms);

        boolean CloseHandle(Pointer handle);

        Pointer GetCurrentThread();

        boolean SetThreadPriority(Pointer thread, int priority);
    }

    /** THREAD_PRIORITY_TIME_CRITICAL: for a thread that sleeps until an event and only timestamps and hands it on. */
    public static final int THREAD_PRIORITY_TIME_CRITICAL = 15;

    /** Raises the calling thread to time-critical priority; false if unavailable. */
    public static boolean makeCurrentThreadTimeCritical()
    {
        get();
        Kernel32 k = kernel32;
        if (k == null) return false;
        try
        {
            return k.SetThreadPriority(k.GetCurrentThread(), THREAD_PRIORITY_TIME_CRITICAL);
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    public static final int KEYEVENTF_KEYUP = 0x0002;
    private static final int CREATE_WAITABLE_TIMER_HIGH_RESOLUTION = 0x2;
    private static final int TIMER_ALL_ACCESS = 0x1F0003;

    private static volatile boolean attempted;
    private static volatile User32 user32;
    private static volatile Kernel32 kernel32;
    private static volatile String failure;

    /**
     * A sleep with sub-millisecond precision. On this machine it measured p50 0.52 ms and p99 1.06 ms
     * for a 500 us period. parkNanos snaps to the ~1 ms system tick (p90 2 ms for a 1 ms schedule).
     * Uses a Windows 10 1803+ high-resolution waitable timer and falls back to parkNanos without one.
     * Not thread-safe: one instance per thread.
     */
    public static final class PreciseSleeper
    {
        private final Kernel32 k;
        private final Pointer timer;
        private final long[] due = new long[1];

        public PreciseSleeper()
        {
            get();
            Kernel32 kk = kernel32;
            Pointer t = null;
            if (kk != null)
            {
                try
                {
                    t = kk.CreateWaitableTimerExW(null, null, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS);
                }
                catch (Throwable ignored)
                {
                }
            }
            this.k = kk;
            this.timer = t;
        }

        public boolean isHighResolution()
        {
            return timer != null;
        }

        public void sleepNanos(long nanos)
        {
            if (nanos <= 0) return;
            if (timer != null)
            {
                due[0] = -(nanos / 100L); // relative time in 100 ns units
                if (k.SetWaitableTimer(timer, due, 0, null, null, false))
                {
                    k.WaitForSingleObject(timer, 0xFFFFFFFF);
                    return;
                }
            }
            java.util.concurrent.locks.LockSupport.parkNanos(nanos);
        }

        public void close()
        {
            if (timer != null) k.CloseHandle(timer);
        }
    }

    private Win32()
    {
    }

    public static synchronized User32 get()
    {
        if (!attempted)
        {
            attempted = true;
            if (!System.getProperty("os.name", "").toLowerCase().startsWith("windows"))
            {
                failure = "not Windows";
            }
            else
            {
                try
                {
                    user32 = (User32)Native.loadLibrary("user32", User32.class);
                    kernel32 = (Kernel32)Native.loadLibrary("kernel32", Kernel32.class);
                }
                catch (Throwable t)
                {
                    failure = t.toString();
                }
            }
        }
        return user32;
    }

    public static String failureReason()
    {
        return failure;
    }

    /** True if the key is down right now (high bit of GetAsyncKeyState). */
    public static boolean isDown(User32 u, int vk)
    {
        return (u.GetAsyncKeyState(vk) & 0x8000) != 0;
    }
}
