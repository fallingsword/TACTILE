package com.tactile.client.input;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.Random;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import com.sun.jna.Pointer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.Timer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/**
 * Phase 3a input-pipeline measurement. No game state is changed except, in auto mode, the FPS limit
 * and vsync during the run. It records, with System.nanoTime, every transition of the tracked keys as
 * seen by each source:
 * <ul>
 * <li>{@code inject}: auto mode only; the moment a synthetic F13 press/release was handed to the OS (keybd_event)</li>
 * <li>{@code async}: background GetAsyncKeyState poller (Windows, 2 kHz target)</li>
 * <li>{@code raw}: event-driven Raw Input source ({@link RawKeyInput})</li>
 * <li>{@code frameA}: Keyboard.isKeyDown at the start of each render frame (state from the previous Display.update)</li>
 * <li>{@code frameB}: the same after an extra Display.processMessages() pump on the game thread</li>
 * <li>{@code lwjgl}: vanilla's own event loop (Forge KeyInputEvent), plus LWJGL's event timestamp</li>
 * <li>{@code binding}: KeyBinding.isKeyDown at the end of each client tick (what vanilla physics used)</li>
 * </ul>
 * plus a frame/tick log carrying the Timer state used to anchor game time. Results go to run/hrm-input/.
 *
 * <p>Modes (environment variable HRMOVE_INPUTPROBE): {@code auto} = launch the test world, inject
 * pulses at three FPS settings, write logs, exit. {@code manual} = log during normal play; logs are
 * written every 30 s and at exit.
 */
public final class InputProbe
{
    private static final int VK_F13 = 0x7C;
    private static final int LWJGL_F13 = Keyboard.KEY_F13;
    private static final String[] KEY_NAMES = {"forward", "back", "left", "right", "jump", "sneak", "sprint", "F13"};

    private final Minecraft mc = Minecraft.getMinecraft();
    private final boolean auto;
    private final File outDir;
    private final Win32.User32 user32 = Win32.get();
    private final KeyBinding[] bindings;
    private final int[] codes = new int[KEY_NAMES.length];
    private AsyncKeyPoller poller;
    private RawKeyInput raw;
    private final long startNanos = System.nanoTime();
    private double cpuSecs = 1.0D;
    private long pollerCpu = -1L, rawCpu = -1L;

    private final StringBuilder events = new StringBuilder();
    private final StringBuilder frames = new StringBuilder();
    private final boolean[] frameA = new boolean[KEY_NAMES.length];
    private final boolean[] frameB = new boolean[KEY_NAMES.length];
    private final boolean[] binding = new boolean[KEY_NAMES.length];
    private long frameIndex, tickIndex;
    private long lastFlush = System.nanoTime();

    private final Field timerField = ReflectionHelper.findField(Minecraft.class, "timer", "field_71428_T");
    private final Field lastHRTimeField = ReflectionHelper.findField(Timer.class, "lastHRTime", "field_74276_f");

    // auto-mode state
    private enum Phase { WAIT_MENU, LOADING, RUNNING, DONE }
    private Phase phase = Phase.WAIT_MENU;
    private int loadTicks;
    private volatile boolean injectorDone;
    private volatile Pointer gameWindow;
    private volatile int requestedFps = -1;
    private volatile boolean focusedFlag;
    private Thread injector;

    public InputProbe(boolean auto)
    {
        this.auto = auto;
        this.outDir = new File(mc.mcDataDir, "hrm-input");
        GameSettings gs = mc.gameSettings;
        bindings = new KeyBinding[] {gs.keyBindForward, gs.keyBindBack, gs.keyBindLeft, gs.keyBindRight,
            gs.keyBindJump, gs.keyBindSneak, gs.keyBindSprint};
        refreshCodes();
        outDir.mkdirs();
        new File(outDir, "events.csv").delete();
        new File(outDir, "frames.csv").delete();
        events.append("source,key,down,nanos,extra,focused\n");
        frames.append("type,index,nanos,partial,lastHRTimeSec,fpsLimit,vsync,focused\n");

        if (user32 != null)
        {
            int[] vks = new int[codes.length];
            for (int i = 0; i < codes.length; ++i) vks[i] = VkMap.toVk(codes[i]);
            poller = new AsyncKeyPoller(user32, vks, 500_000L, 0L);
            poller.start();
            log("async poller started (2 kHz target); " + VkMap.status());
            raw = new RawKeyInput(user32, vks);
            raw.start();
            log(raw.failure() == null ? "raw input source started" : "raw input source failed: " + raw.failure());
            if (raw.failure() != null) raw = null;
        }
        else
        {
            log("async poller unavailable: " + Win32.failureReason());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable()
        {
            public void run()
            {
                flush(true);
            }
        }, "tactile-probe-flush"));
    }

    public static String mode()
    {
        String m = System.getenv("HRMOVE_INPUTPROBE");
        return m == null ? "" : m.trim().toLowerCase();
    }

    private void refreshCodes()
    {
        for (int i = 0; i < bindings.length; ++i) codes[i] = bindings[i].getKeyCode();
        codes[7] = LWJGL_F13;
    }

    private boolean focused()
    {
        return Display.isActive() && mc.inGameHasFocus && mc.currentScreen == null;
    }

    private static boolean lwjglDown(int code)
    {
        if (code < 0) return Mouse.isButtonDown(code + 100);
        return code > 0 && code < 256 && Keyboard.isKeyDown(code);
    }

    // ------------------------------------------------------------------ sources

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event)
    {
        if (event.phase != TickEvent.Phase.START) return;
        long now = System.nanoTime();
        boolean f = focused();
        focusedFlag = f;
        if (poller != null) poller.setFocused(f);
        if (raw != null) raw.setFocused(f);
        if (f && user32 != null) gameWindow = user32.GetForegroundWindow();
        if (openMenu)
        {
            openMenu = false;
            mc.displayGuiScreen(new net.minecraft.client.gui.GuiIngameMenu());
        }
        if (closeMenu)
        {
            closeMenu = false;
            mc.displayGuiScreen(null);
        }
        if (requestedFps > 0)
        {
            mc.gameSettings.limitFramerate = requestedFps;
            mc.gameSettings.enableVsync = false;
            Display.setVSyncEnabled(false);
            requestedFps = -1;
        }

        for (int i = 0; i < codes.length; ++i)
        {
            boolean d = lwjglDown(codes[i]);
            if (d != frameA[i]) record("frameA", i, d, now, 0L, f);
            frameA[i] = d;
        }
        Display.processMessages(); // fresh pump; mouse deltas accumulate while grabbed (verified)
        long now2 = System.nanoTime();
        for (int i = 0; i < codes.length; ++i)
        {
            boolean d = lwjglDown(codes[i]);
            if (d != frameB[i]) record("frameB", i, d, now2, now2 - now, f);
            frameB[i] = d;
        }

        double lastHR = 0.0D;
        try
        {
            lastHR = lastHRTimeField.getDouble(timerField.get(mc));
        }
        catch (Exception ignored)
        {
        }
        frames.append("F,").append(frameIndex++).append(',').append(now).append(',').append(event.renderTickTime).append(',')
            .append(lastHR).append(',').append(mc.gameSettings.limitFramerate).append(',').append(mc.gameSettings.enableVsync ? 1 : 0)
            .append(',').append(f ? 1 : 0).append('\n');
        drainPoller();
    }

    private int keyInputEvents;

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event)
    {
        int key = Keyboard.getEventKey();
        if (++keyInputEvents <= 20)
        {
            synchronized (this)
            {
                events.append("debug,keyinput key=" + key + " char=" + (int)Keyboard.getEventCharacter() + " down=" + Keyboard.getEventKeyState()
                    + ",0," + System.nanoTime() + ",0,0\n");
            }
        }
        for (int i = 0; i < codes.length; ++i)
        {
            if (codes[i] == key && key != 0)
                record("lwjgl", i, Keyboard.getEventKeyState(), System.nanoTime(), Keyboard.getEventNanoseconds(), focused());
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event)
    {
        long now = System.nanoTime();
        if (event.phase == TickEvent.Phase.START)
        {
            frames.append("T,").append(tickIndex++).append(',').append(now).append(",0,0,0,0,").append(focused() ? 1 : 0).append('\n');
            if (auto) autoStep();
            return;
        }
        refreshCodes();
        boolean f = focused();
        for (int i = 0; i < bindings.length; ++i)
        {
            boolean d = bindings[i].isKeyDown();
            if (d != binding[i]) record("binding", i, d, now, 0L, f);
            binding[i] = d;
        }
        drainPoller();
        if (now - lastFlush > 30_000_000_000L) flush(false);
    }

    private void drainPoller()
    {
        if (raw != null) raw.drain(new KeySource.Sink()
        {
            public void onKey(long nanos, int keyIndex, boolean down, boolean f)
            {
                record("raw", keyIndex, down, nanos, 0L, f);
            }
        });
        if (poller == null) return;
        poller.drain(new KeySource.Sink()
        {
            public void onKey(long nanos, int keyIndex, boolean down, boolean f)
            {
                record("async", keyIndex, down, nanos, 0L, f);
            }
        });
    }

    private long unfocusedDropped;

    private synchronized void record(String source, int key, boolean down, long nanos, long extra, boolean f)
    {
        if (!f && !source.equals("inject"))
        {
            // Keystrokes while the game is not focused belong to other applications: count, never log.
            ++unfocusedDropped;
            return;
        }
        events.append(source).append(',').append(KEY_NAMES[key]).append(',').append(down ? 1 : 0).append(',')
            .append(nanos).append(',').append(extra).append(',').append(f ? 1 : 0).append('\n');
    }

    private synchronized void marker(String text)
    {
        events.append("marker,").append(text).append(",0,").append(System.nanoTime()).append(",0,0\n");
    }

    private synchronized void flush(boolean finalFlush)
    {
        lastFlush = System.nanoTime();
        try
        {
            append(new File(outDir, "events.csv"), events);
            append(new File(outDir, "frames.csv"), frames);
            if (finalFlush && poller != null)
            {
                PrintWriter w = new PrintWriter(new FileWriter(new File(outDir, "poll_hist.csv")));
                w.println("bucketStartUs,count");
                long[] hist = poller.intervalHistogram();
                for (int i = 0; i < hist.length; ++i) if (hist[i] > 0) w.println((i * AsyncKeyPoller.BUCKET_NS / 1000) + "," + hist[i]);
                w.println("# polls=" + poller.polls() + " maxIntervalUs=" + poller.maxIntervalNs() / 1000 + " dropped=" + poller.dropped()
                    + " periodUs=" + poller.periodNanos() / 1000 + " highResTimer=" + poller.usesHighResolutionTimer() + " keyInputEvents=" + keyInputEvents
                    + " unfocusedDropped=" + unfocusedDropped);
                if (poller.cpuNanos() >= 0) // threads still running: take the reading (later flushes reuse it)
                {
                    cpuSecs = (System.nanoTime() - startNanos) / 1e9;
                    pollerCpu = poller.cpuNanos();
                    rawCpu = raw == null ? -1L : raw.cpuNanos();
                }
                w.println(String.format(java.util.Locale.ROOT, "# cpu over %.0f s: poller %.1f ms (%.2f %% of a core), raw %s; raw published=%d unfocusedEvents=%d registrations=%d dropped=%d",
                    cpuSecs, pollerCpu / 1e6, pollerCpu / 1e7 / cpuSecs,
                    rawCpu < 0 ? "n/a" : String.format(java.util.Locale.ROOT, "%.1f ms (%.3f %% of a core)", rawCpu / 1e6, rawCpu / 1e7 / cpuSecs),
                    raw == null ? 0 : raw.published(), raw == null ? 0 : raw.unfocusedEvents(), raw == null ? 0 : raw.registrations(), raw == null ? 0 : raw.dropped()));
                w.close();
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    private static void append(File f, StringBuilder sb) throws IOException
    {
        FileWriter w = new FileWriter(f, true);
        try
        {
            w.write(sb.toString());
        }
        finally
        {
            w.close();
        }
        sb.setLength(0);
    }

    // ------------------------------------------------------------------ auto mode

    private void autoStep()
    {
        switch (phase)
        {
            case WAIT_MENU:
                if (mc.theWorld == null && mc.currentScreen instanceof GuiMainMenu)
                {
                    mc.gameSettings.pauseOnLostFocus = false;
                    mc.launchIntegratedServer("hrm_baseline", "hrm_baseline", new WorldSettings(0L, WorldSettings.GameType.SURVIVAL, false, false, WorldType.FLAT));
                    phase = Phase.LOADING;
                }
                break;
            case LOADING:
                if (com.tactile.client.TestWorld.ensurePlayerReady(mc) && ++loadTicks > 60)
                {
                    com.tactile.client.TestWorld.protectPlayer(mc.getIntegratedServer());
                    if (user32 == null)
                    {
                        log("cannot inject without user32; stopping");
                        finish();
                        return;
                    }
                    startInjector();
                    phase = Phase.RUNNING;
                }
                break;
            case RUNNING:
                if (injectorDone) finish();
                break;
            default:
                break;
        }
    }

    private void finish()
    {
        phase = Phase.DONE;
        drainPoller();
        flush(true); // CPU figures are read while both threads still run
        if (poller != null) poller.stop();
        if (raw != null) raw.stop();
        drainPoller();
        flush(true);
        log("done; logs in " + outDir.getAbsolutePath());
        mc.shutdown();
    }

    private void startInjector()
    {
        injector = new Thread(new Runnable()
        {
            public void run()
            {
                try
                {
                    runInjection();
                }
                catch (InterruptedException ignored)
                {
                }
                finally
                {
                    injectorDone = true;
                }
            }
        }, "tactile-key-injector");
        injector.setDaemon(true);
        injector.start();
    }

    /** Pulse schedule: for each FPS setting, 210 pulses of 1-66 ms, with 40-100 ms gaps. */
    private void runInjection() throws InterruptedException
    {
        int[] fpsSettings = {60, 144, 260}; // 260 = unlimited in 1.8.9
        int[] durationsMs = {1, 2, 4, 8, 16, 33, 66};
        Random rnd = new Random(1234L);
        byte scan = (byte)user32.MapVirtualKeyA(VK_F13, 0);

        // Injection is only safe while the game window is in the foreground (keys would otherwise go
        // to whatever the user is working in). Wait for focus; never take it.
        long waitStart = System.nanoTime();
        boolean announced = false;
        while (!isGameForeground() || !focusedFlag)
        {
            if (!announced)
            {
                log("waiting for the Minecraft window to be focused (click into it and leave it alone for ~2 minutes)");
                announced = true;
            }
            if (System.nanoTime() - waitStart > 180_000_000_000L)
            {
                marker("aborted: game window never focused");
                log("aborted: the game window was not focused within 3 minutes");
                return;
            }
            Thread.sleep(250);
        }
        for (int fps : fpsSettings)
        {
            requestedFps = fps;
            Thread.sleep(2000); // let the setting apply and the frame rate settle
            marker("phase fps=" + fps);
            for (int p = 0; p < 210; ++p)
            {
                int dur = durationsMs[p % durationsMs.length];
                Thread.sleep(40 + rnd.nextInt(60));
                if (!isGameForeground())
                {
                    marker("skip-not-foreground");
                    continue;
                }
                inject(scan, false);
                busyWaitMs(dur);
                inject(scan, true);
            }
            Thread.sleep(300);
        }
        marker("injection done");
        privacyCheck(scan);
        Thread.sleep(500);
    }

    /**
     * With the pause menu open the game counts as unfocused, so the key-event source must be unregistered: 20 pulses are
     * injected (the game window is still the foreground window, so they go to the game's own menu) and the source must
     * receive none of them, not even as counted-but-unread events.
     */
    private void privacyCheck(byte scan) throws InterruptedException
    {
        if (raw == null) return;
        openMenu = true;
        Thread.sleep(1000);
        long published = raw.published(), unfocused = raw.unfocusedEvents();
        int sent = 0;
        for (int p = 0; p < 20; ++p)
        {
            Thread.sleep(60);
            if (!isGameForeground()) continue;
            inject(scan, false);
            busyWaitMs(10);
            inject(scan, true);
            ++sent;
        }
        Thread.sleep(300);
        String result = String.format("privacy check (pause menu open): %d pulses injected; key-event source received %d, delivered-but-unread %d",
            sent, raw.published() - published, raw.unfocusedEvents() - unfocused);
        marker(result);
        log(result);
        closeMenu = true;
        Thread.sleep(500);
    }

    private volatile boolean openMenu, closeMenu;

    private boolean isGameForeground()
    {
        Pointer game = gameWindow;
        return game != null && game.equals(user32.GetForegroundWindow());
    }

    private void inject(byte scan, boolean release)
    {
        long t0 = System.nanoTime();
        user32.keybd_event((byte)VK_F13, scan, release ? Win32.KEYEVENTF_KEYUP : 0, null);
        long t1 = System.nanoTime();
        record("inject", 7, !release, t0 + (t1 - t0) / 2, t1 - t0, true);
    }

    private static void busyWaitMs(int ms)
    {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (System.nanoTime() < end) Thread.yield();
    }

    private static void log(String s)
    {
        System.out.println("[tactile-input] " + s);
    }
}
