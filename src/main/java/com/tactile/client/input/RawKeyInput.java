package com.tactile.client.input;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;

/**
 * Event-driven key source (Windows Raw Input). A hidden message-only window on its own thread is registered for
 * keyboard Raw Input, and the thread blocks in GetMessage until Windows delivers a key event. It is timestamped on
 * wake-up, so there is no polling interval and no CPU use between key events. Timing is then limited by the keyboard
 * itself (scan, debounce, USB report rate) and the thread's wake-up.
 *
 * <p>A message-only window is never the foreground window, so it only receives Raw Input when registered with
 * RIDEV_INPUTSINK (measured: without it, no events arrive even while the game is focused). Privacy is therefore kept
 * by the source itself:
 * <ul>
 * <li>It is registered only while the game has focus and unregistered as soon as the game reports losing it, so
 *     Windows stops delivering anything while you use other windows, menus or chat.</li>
 * <li>Each delivered event is read only if the game window is the foreground window at that moment (this covers the
 *     instant between switching away and the game noticing). Otherwise it is counted and never read.</li>
 * <li>Keys other than the movement keys are discarded on arrival; nothing about them is stored.</li>
 * <li>RIDEV_NOLEGACY is not used, so the game and every other window still receive their normal key messages.</li>
 * </ul>
 * <p>Threading contract: see {@link KeySource}.
 */
public final class RawKeyInput implements Runnable, KeySource
{
    private static final int WM_INPUT = 0x00FF;
    private static final int WM_APP_WAKE = 0x8000, WM_APP_FOCUS = 0x8001;
    private static final int RID_INPUT = 0x10000003;
    private static final int RIM_TYPEKEYBOARD = 1;
    private static final int RIDEV_REMOVE = 0x00000001;
    private static final int RIDEV_INPUTSINK = 0x00000100;
    private static final int RI_KEY_BREAK = 1, RI_KEY_E0 = 2;
    private static final Pointer HWND_MESSAGE = Pointer.createConstant(-3L);
    private static final int PS = Pointer.SIZE;
    private static final int HEADER = 8 + 2 * PS; // RAWINPUTHEADER: dwType, dwSize, hDevice, wParam

    private static final int CAPACITY = 1 << 12;
    private final long[] times = new long[CAPACITY];
    private final int[] packed = new int[CAPACITY];
    private final AtomicLong head = new AtomicLong();
    private long tail;
    private long tailSnapshot;
    private volatile long consumerTail;
    private volatile long dropped;

    private final Win32.User32 user32;
    private final int[] vks;
    private final boolean[] state;
    private volatile boolean running;
    private volatile boolean focused;
    /** The game's window, captured from the foreground when the game reports gaining focus. */
    private volatile Pointer gameWindow;
    private volatile Pointer hwnd;
    private volatile String failure;
    private CountDownLatch ready;
    private Thread thread;

    /** Event-thread state: whether the Raw Input registration is currently active. */
    private boolean registered;
    private final Memory device = new Memory(8 + PS); // RAWINPUTDEVICE: usUsagePage, usUsage, dwFlags, hwndTarget

    /** Diagnostics: transitions published; events delivered while not focused (counted, never read); registrations. */
    private volatile long published, unfocusedEvents, registrations;

    /**
     * @param vks virtual-key codes of the movement keys (index = key index in events); mouse buttons are not keyboard
     *            input and cannot be served (see {@link #canServe})
     */
    public RawKeyInput(Win32.User32 user32, int[] vks)
    {
        this.user32 = user32;
        this.vks = vks.clone();
        this.state = new boolean[vks.length];
    }

    /** True if every key is a keyboard key (Raw Input keyboard events cannot report mouse buttons). */
    public static boolean canServe(int[] vks)
    {
        for (int vk : vks) if (vk <= 0x06 || vk == 0x10 || vk == 0x11 || vk == 0x12) return false; // mouse buttons, generic modifiers
        return true;
    }

    /** Game thread. Gaining focus registers the source, losing it unregisters it. */
    public void setFocused(boolean f)
    {
        if (f == focused) return;
        if (f) gameWindow = user32.GetForegroundWindow(); // the game is active, so this is its window
        focused = f;
        Pointer h = hwnd;
        if (h != null) user32.PostMessageW(h, WM_APP_FOCUS, null, null);
    }

    /** Starts the thread and waits (up to 1 s) for its window; {@link #failure()} is non-null if it failed. */
    public synchronized void start()
    {
        if (running) return;
        running = true;
        ready = new CountDownLatch(1);
        thread = new Thread(this, "tactile-key-events");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
        try
        {
            if (!ready.await(1, TimeUnit.SECONDS)) failure = "start timed out";
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        if (failure != null) stop();
    }

    public synchronized void stop()
    {
        running = false;
        Pointer h = hwnd;
        if (h != null) user32.PostMessageW(h, WM_APP_WAKE, null, null);
        if (thread != null)
        {
            try
            {
                thread.join(500);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    public String failure()
    {
        return failure;
    }

    private volatile boolean timeCritical;

    /** Whether the event thread runs at time-critical OS priority. */
    public boolean isTimeCritical()
    {
        return timeCritical;
    }

    @Override
    public void run()
    {
        // Measured at normal Java MAX_PRIORITY: in ~3 % of presses at 20 Hz the thread was descheduled for ~10 ms between
        // waking and handing the event over. The thread sleeps until a key event, so time-critical costs nothing.
        timeCritical = !"1".equals(System.getenv("HRMOVE_KEYTHREAD_NORMAL")) && Win32.makeCurrentThreadTimeCritical();
        Pointer h;
        try
        {
            h = user32.CreateWindowExW(0, new WString("STATIC"), new WString("tactile-keys"), 0, 0, 0, 0, 0, HWND_MESSAGE, null, null, null);
            if (h == null)
            {
                failure = "CreateWindowEx failed";
                return;
            }
            hwnd = h;
            // Registration test: Raw Input must be available, even if the game is not focused right now.
            if (!register(true) || !register(false))
            {
                failure = "RegisterRawInputDevices failed";
                user32.DestroyWindow(h);
                hwnd = null;
                return;
            }
            syncRegistration();
        }
        catch (Throwable t)
        {
            failure = t.toString();
            return;
        }
        finally
        {
            ready.countDown();
        }

        Memory msg = new Memory(48 + PS);       // MSG
        Memory data = new Memory(HEADER + 16);  // RAWINPUTHEADER + RAWKEYBOARD
        IntByReference size = new IntByReference();
        try
        {
            while (running && user32.GetMessageW(msg, null, 0, 0) > 0)
            {
                long t = System.nanoTime();
                int message = msg.getInt(PS);
                if (message == WM_INPUT) onInput(msg.getPointer(3 * PS), t, data, size);
                else if (message == WM_APP_FOCUS) syncRegistration();
                user32.DispatchMessageW(msg); // WM_INPUT must reach DefWindowProc for cleanup
            }
        }
        finally
        {
            if (registered) register(false);
            user32.DestroyWindow(h);
            hwnd = null;
        }
    }

    /** Event thread: registered exactly while focused. On gaining focus, the current key state is published. */
    private void syncRegistration()
    {
        boolean f = focused;
        if (f == registered) return;
        if (!register(f)) return;
        long now = System.nanoTime();
        for (int i = 0; i < vks.length; ++i)
        {
            state[i] = f && Win32.isDown(user32, vks[i]); // all up while unfocused; the game re-reads real state on regain
            publish(now, i, state[i], f);
        }
    }

    private boolean register(boolean on)
    {
        device.setShort(0, (short)0x01); // generic desktop
        device.setShort(2, (short)0x06); // keyboard
        device.setInt(4, on ? RIDEV_INPUTSINK : RIDEV_REMOVE);
        device.setPointer(8, on ? hwnd : null);
        boolean ok = user32.RegisterRawInputDevices(device, 1, (int)device.size());
        if (ok)
        {
            registered = on;
            if (on) registrations = registrations + 1;
        }
        return ok;
    }

    private void onInput(Pointer rawInput, long t, Memory data, IntByReference size)
    {
        Pointer game = gameWindow;
        boolean inFront = focused && game != null && game.equals(user32.GetForegroundWindow());
        long afterForeground = System.nanoTime();
        if (!inFront)
        {
            // Another window, a menu or chat has the keyboard: count only, never read the event.
            unfocusedEvents = unfocusedEvents + 1;
            return;
        }
        // Measured: GetRawInputData occasionally takes ~10 ms (once in ~25 presses at one fixed moment of a run; no GC,
        // time-critical priority). The wake time is right; announce it so the engine does not simulate past it meanwhile.
        pendingNanos = t;
        try
        {
            if (FAULT_TEST) throw new IllegalStateException("HRMOVE_FAULTTEST: forced error in the key-event thread");
            readAndPublish(rawInput, t, data, size, afterForeground);
        }
        finally
        {
            pendingNanos = 0L;
        }
    }

    private volatile long pendingNanos;

    public long pendingNanos()
    {
        return pendingNanos;
    }

    public boolean isAlive()
    {
        Thread th = thread;
        return th != null && th.isAlive();
    }

    /** Test only (HRMOVE_FAULTTEST=keys): the first key event read throws, ending the thread. */
    private static final boolean FAULT_TEST = "keys".equals(System.getenv("HRMOVE_FAULTTEST"));

    /** Test only: every event read takes this long (simulates the measured slow GetRawInputData). */
    private static final long TEST_STALL_NANOS = System.getenv("HRMOVE_KEYSTALL_MS") == null ? 0L
        : (long)(Double.parseDouble(System.getenv("HRMOVE_KEYSTALL_MS").trim()) * 1e6);

    private void readAndPublish(Pointer rawInput, long t, Memory data, IntByReference size, long afterForeground)
    {
        if (TEST_STALL_NANOS > 0L) java.util.concurrent.locks.LockSupport.parkNanos(TEST_STALL_NANOS);
        size.setValue((int)data.size());
        int read = user32.GetRawInputData(rawInput, RID_INPUT, data, size, HEADER);
        long afterRead = System.nanoTime();
        if (read <= 0) return;
        if (data.getInt(0) != RIM_TYPEKEYBOARD) return;
        int make = data.getShort(HEADER) & 0xFFFF;
        int flags = data.getShort(HEADER + 2) & 0xFFFF;
        int vk = sideSpecific(data.getShort(HEADER + 6) & 0xFFFF, make, flags);
        boolean down = (flags & RI_KEY_BREAK) == 0;
        for (int i = 0; i < vks.length; ++i)
        {
            if (vks[i] != vk) continue;
            if (state[i] != down) // key auto-repeat delivers repeated presses
            {
                state[i] = down;
                if (i < stageNanos.length)
                {
                    stageNanos[i][0] = afterForeground;
                    stageNanos[i][1] = afterRead;
                }
                publish(t, i, down, true);
            }
            return;
        }
        // not a movement key: discarded
    }

    /** Raw Input reports generic modifier VKs; the bindings use the side-specific ones. */
    private static int sideSpecific(int vk, int make, int flags)
    {
        boolean right = (flags & RI_KEY_E0) != 0;
        switch (vk)
        {
            case 0x10: return make == 0x36 ? 0xA1 : 0xA0; // VK_SHIFT: right shift has its own make code
            case 0x11: return right ? 0xA3 : 0xA2;        // VK_CONTROL
            case 0x12: return right ? 0xA5 : 0xA4;        // VK_MENU
            default: return vk;
        }
    }

    private void publish(long nanos, int key, boolean down, boolean f)
    {
        long h = head.get();
        if (h - tailSnapshot >= CAPACITY)
        {
            tailSnapshot = consumerTail;
            if (h - tailSnapshot >= CAPACITY)
            {
                dropped = dropped + 1;
                return;
            }
        }
        int slot = (int)(h & (CAPACITY - 1));
        times[slot] = nanos;
        packed[slot] = (key << 2) | (down ? 2 : 0) | (f ? 1 : 0);
        head.lazySet(h + 1);
        published = published + 1;
        if (key < publishNanos.length) publishNanos[key] = System.nanoTime();
    }

    /** Diagnostics: when the latest event of each key index was handed to the buffer (nanoTime). */
    private final long[] publishNanos = new long[16];
    /** Diagnostics: for the latest event of each key index, when the foreground check and the event read finished. */
    private final long[][] stageNanos = new long[16][2];

    public long[] stageNanos(int key)
    {
        return key < stageNanos.length ? stageNanos[key].clone() : new long[2];
    }

    public long publishNanos(int key)
    {
        return key < publishNanos.length ? publishNanos[key] : 0L;
    }

    public void drain(KeySource.Sink sink)
    {
        long h = head.get();
        while (tail < h)
        {
            int slot = (int)(tail & (CAPACITY - 1));
            int p = packed[slot];
            sink.onKey(times[slot], p >>> 2, (p & 2) != 0, (p & 1) != 0);
            ++tail;
        }
        consumerTail = tail;
    }

    public long dropped()
    {
        return dropped;
    }

    public long published()
    {
        return published;
    }

    public long unfocusedEvents()
    {
        return unfocusedEvents;
    }

    public long registrations()
    {
        return registrations;
    }

    /** CPU time used by the event thread so far (ns), or -1 if unavailable. */
    public long cpuNanos()
    {
        Thread th = thread;
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (th == null || !bean.isThreadCpuTimeSupported()) return -1L;
        return bean.getThreadCpuTime(th.getId());
    }
}
