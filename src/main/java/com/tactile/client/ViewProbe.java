package com.tactile.client;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;
import java.util.Random;

import com.sun.jna.Pointer;
import com.tactile.client.input.VkMap;
import com.tactile.client.input.Win32;
import com.tactile.client.live.TactileConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * What the camera shows, frame by frame (HRMOVE_VIEWPROBE=1; the game window must stay focused). Keys are pressed
 * through the OS (keybd_event), so every mode goes through its real input path; nothing is injected unless the game
 * window is the foreground window.
 * <ul>
 * <li>Part J, jump latency: standing still, space is pressed at a random moment and held 300 ms.</li>
 * <li>Part S, standing on slime: sneak held 1 s, then released, then 3 s of standing still.</li>
 * </ul>
 * Every frame writes the drawn feet height (the interpolation the camera uses), eye height, view-bobbing terms, and
 * for engine modes the engine clock and the engine's jump time, to run/hrm-view/frames.csv. Analyzed by
 * {@code gradlew viewAnalysis}. Modes: VANILLA, STEPPED 20/35/60 (step rates; physics 960), EXACT 20/960.
 */
public final class ViewProbe
{
    /** HRMOVE_VIEWPROBE=latency: EXACT 20 (twice) / 960 only, 50 jumps each, the engine restarted before every third. */
    /** HRMOVE_VIEWPROBE=latency20: EXACT 20 only, 25 jumps (same schedule as latency, so trial 20 falls at the same time). */
    private static final boolean LATENCY960 = "latency960".equalsIgnoreCase(System.getenv("HRMOVE_VIEWPROBE"));
    private static final boolean LATENCY20 = LATENCY960 || "latency20".equalsIgnoreCase(System.getenv("HRMOVE_VIEWPROBE"));
    private static final boolean LATENCY = LATENCY20 || "latency".equalsIgnoreCase(System.getenv("HRMOVE_VIEWPROBE"));
    private static final Object[][] MODES = LATENCY960 ? new Object[][] {{"EXACT", 960}} : LATENCY20 ? new Object[][] {{"EXACT", 20}}
        : LATENCY ? new Object[][] {{"EXACT", 20}, {"EXACT", 20}, {"EXACT", 960}}
        : new Object[][] {{"VANILLA", 960}, {"STEPPED", 20}, {"STEPPED", 35}, {"STEPPED", 60}, {"EXACT", 20}, {"EXACT", 960}};
    private static final int JUMPS = LATENCY20 ? 25 : LATENCY ? 50 : 8, STANDS = LATENCY ? 0 : 2;
    private final StringBuilder presses = new StringBuilder("mode,rate,trial,restarted,pressNanos,receivedNanos,collectedNanos,eventTau,enteredTau,jumpTau,"
        + "publishNanos,prevDrainNanos,foregroundDoneNanos,readDoneNanos,timeCritical,gcCount,gcMillis\n");
    private long gcCount0, gcMillis0;
    private boolean restarted;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Win32.User32 user32 = Win32.get();
    private final Random rnd = new Random(11L);
    private final StringBuilder out = new StringBuilder("mode,rate,part,trial,ms,frameTau,jumpTau,y,eye,bobYaw,bobPitch,walked,onGround\n");
    private int phase, counter, modeIndex, trial, ox, oz;
    private String part = "J";
    private volatile Pointer gameWindow;
    private volatile long pressNanos;    // OS press of the current trial (0 = not yet)
    private volatile boolean injecting, recording;

    public static boolean requested()
    {
        String v = System.getenv("HRMOVE_VIEWPROBE");
        return "1".equals(v) || "latency".equalsIgnoreCase(v) || "latency20".equalsIgnoreCase(v) || "latency960".equalsIgnoreCase(v);
    }

    private String mode()
    {
        return (String)MODES[modeIndex][0];
    }

    private int rate()
    {
        return (Integer)MODES[modeIndex][1];
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e)
    {
        if (e.phase != TickEvent.Phase.END) return;
        if (mc.thePlayer != null && Display() && mc.currentScreen == null && user32 != null) gameWindow = user32.GetForegroundWindow();
        switch (phase)
        {
            case 0:
                if (mc.theWorld == null && mc.currentScreen instanceof GuiMainMenu)
                {
                    mc.gameSettings.pauseOnLostFocus = false;
                    mc.gameSettings.limitFramerate = 260;
                    mc.gameSettings.enableVsync = false;
                    mc.launchIntegratedServer("hrm_baseline", "hrm_baseline", new WorldSettings(0L, WorldSettings.GameType.SURVIVAL, false, false, WorldType.FLAT));
                    phase = 1;
                }
                break;
            case 1:
                if (TestWorld.ensurePlayerReady(mc) && ++counter > 60)
                {
                    TestWorld.protectPlayer(mc.getIntegratedServer());
                    build();
                    startMode();
                }
                break;
            case 2: // settle (on the right surface, engine state as the mode requires), then arm a trial
                if (!TestWorld.ensurePlayerReady(mc)) break;
                boolean engine = !mode().equals("VANILLA");
                if (++counter >= 30 && mc.thePlayer.onGround && engine == ClientSetup.engine.isActive() && !injecting)
                {
                    if (LATENCY && part.equals("J") && trial % 3 == 0 && !restarted)
                    {
                        restarted = true; // restart the engine, then settle again before this trial
                        ++ClientSetup.CONFIG.generation;
                        counter = 0;
                        break;
                    }
                    counter = 0;
                    pressNanos = 0L;
                    long[] gc = gc();
                    gcCount0 = gc[0];
                    gcMillis0 = gc[1];
                    recording = true;
                    startInjection();
                    phase = 3;
                }
                break;
            case 3: // the injector runs; frames record; finish the trial after it
                if (!injecting && ++counter >= (part.equals("J") ? 30 : 10))
                {
                    recording = false;
                    counter = 0;
                    if (part.equals("J") && engine()) logPress();
                    restarted = false;
                    nextTrial();
                }
                break;
            default:
                break;
        }
    }

    private boolean Display()
    {
        return org.lwjgl.opengl.Display.isActive() && mc.inGameHasFocus;
    }

    private void nextTrial()
    {
        ++trial;
        if (part.equals("J") && trial >= JUMPS && STANDS == 0)
        {
            part = "S";
            trial = STANDS;
        }
        if (part.equals("J") && trial >= JUMPS)
        {
            part = "S";
            trial = 0;
            teleport(true);
        }
        else if (part.equals("S") && trial >= STANDS)
        {
            if (++modeIndex >= MODES.length)
            {
                flush();
                log("done; frames in run/hrm-view/frames.csv");
                mc.shutdown();
                phase = 9;
                return;
            }
            startMode();
            return;
        }
        phase = 2;
    }

    private void startMode()
    {
        ClientSetup.CONFIG.applyForTest(TactileConfig.Mode.valueOf(mode()), rate());
        part = "J";
        trial = 0;
        teleport(false);
        log("mode " + mode() + " " + rate() + " Hz");
        flush();
    }

    /** Stone pad (part J) or slime pad (part S), near spawn. */
    private void build()
    {
        final MinecraftServer server = mc.getIntegratedServer();
        final WorldServer world = server.worldServerForDimension(0);
        BlockPos spawn = world.getSpawnPoint();
        ox = spawn.getX() + 30;
        oz = spawn.getZ() + 30;
        final int x0 = ox, z0 = oz;
        server.addScheduledTask(new Runnable()
        {
            public void run()
            {
                for (int x = -2; x <= 8; ++x)
                    for (int z = -2; z <= 2; ++z)
                    {
                        world.setBlockState(new BlockPos(x0 + x, 99, z0 + z), x >= 4 ? Blocks.slime_block.getDefaultState() : Blocks.stone.getDefaultState(), 2);
                        for (int y = 100; y <= 104; ++y) world.setBlockToAir(new BlockPos(x0 + x, y, z0 + z));
                    }
            }
        });
    }

    private void teleport(boolean slime)
    {
        final MinecraftServer server = mc.getIntegratedServer();
        final double x = ox + (slime ? 6.5D : 0.5D), z = oz + 0.5D;
        server.addScheduledTask(new Runnable()
        {
            public void run()
            {
                EntityPlayerMP mp = server.getConfigurationManager().playerEntityList.get(0);
                mp.setSprinting(false);
                mp.playerNetServerHandler.setPlayerLocation(x, 100.0D, z, 0.0F, 0.0F);
                server.getConfigurationManager().serverUpdateMountedMovingPlayer(mp);
            }
        });
        counter = 0;
        phase = 2;
    }

    /** OS key presses on a separate thread, only while the game window is the foreground window. */
    private void startInjection()
    {
        injecting = true;
        final boolean jump = part.equals("J");
        final int vk = VkMap.toVk(jump ? mc.gameSettings.keyBindJump.getKeyCode() : mc.gameSettings.keyBindSneak.getKeyCode());
        final long delay = (long)(100_000_000L + rnd.nextDouble() * 60_000_000L);
        Thread t = new Thread(new Runnable()
        {
            public void run()
            {
                try
                {
                    long until = System.nanoTime() + 5_000_000_000L;
                    while (!isGameForeground())
                    {
                        if (System.nanoTime() > until)
                        {
                            log("game window not focused; trial skipped");
                            return;
                        }
                        Thread.sleep(50);
                    }
                    busyWait(delay);
                    if (!isGameForeground()) return;
                    byte scan = (byte)user32.MapVirtualKeyA(vk, 0);
                    long t0 = System.nanoTime();
                    user32.keybd_event((byte)vk, scan, 0, null);
                    if (jump) pressNanos = t0 + (System.nanoTime() - t0) / 2; // part S records from the release
                    busyWait(jump ? 300_000_000L : 1_000_000_000L);
                    user32.keybd_event((byte)vk, scan, Win32.KEYEVENTF_KEYUP, null);
                    if (jump) Thread.sleep(700); // let the jump finish while frames record
                    else
                    {
                        pressNanos = System.nanoTime(); // part S: frames from the sneak release on
                        Thread.sleep(3000);
                    }
                }
                catch (InterruptedException ignored)
                {
                }
                finally
                {
                    injecting = false;
                }
            }
        }, "tactile-view-injector");
        t.setDaemon(true);
        t.start();
    }

    private boolean isGameForeground()
    {
        Pointer g = gameWindow;
        return g != null && user32 != null && g.equals(user32.GetForegroundWindow());
    }

    private static void busyWait(long nanos)
    {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) Thread.yield();
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent e)
    {
        long p = pressNanos;
        if (!recording || p == 0L) return;
        long now = System.nanoTime();
        EntityPlayerSP pl = mc.thePlayer;
        float pt = e.partialTicks;
        double y = pl.prevPosY + (pl.posY - pl.prevPosY) * pt; // what EntityRenderer.orientCamera uses
        double bobYaw = pl.prevCameraYaw + (pl.cameraYaw - pl.prevCameraYaw) * pt;
        double bobPitch = pl.prevCameraPitch + (pl.cameraPitch - pl.prevCameraPitch) * pt;
        double walked = pl.prevDistanceWalkedModified + (pl.distanceWalkedModified - pl.prevDistanceWalkedModified) * pt;
        boolean engine = ClientSetup.engine.isActive();
        out.append(String.format(Locale.ROOT, "%s,%d,%s,%d,%.4f,%.6f,%.6f,%.6f,%.4f,%.6f,%.6f,%.6f,%d%n", mode(), rate(), part, trial, (now - p) / 1e6,
            engine ? ClientSetup.engine.tauNow() : Double.NaN, engine ? ClientSetup.engine.lastJumpTau() : Double.NaN, y - 100.0D,
            pl.getEyeHeight(), bobYaw, bobPitch, walked, pl.onGround ? 1 : 0));
    }

    private boolean engine()
    {
        return !mode().equals("VANILLA");
    }

    private void logPress()
    {
        double[] ev = ClientSetup.engine.lastJumpKeyEvent();
        presses.append(String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%s,%s,%s,%s,%.6f,%s,%s%n", mode(), rate(), trial, restarted ? 1 : 0, pressNanos,
            ev == null ? "NaN" : String.format(Locale.ROOT, "%.0f", ev[0]), ev == null ? "NaN" : String.format(Locale.ROOT, "%.0f", ev[1]),
            ev == null ? "NaN" : String.format(Locale.ROOT, "%.6f", ev[2]), ev == null ? "NaN" : String.format(Locale.ROOT, "%.6f", ev[3]),
            ClientSetup.engine.lastJumpTau(), ev == null || ev.length < 6 ? "NaN" : String.format(Locale.ROOT, "%.0f", ev[4]),
            ev == null || ev.length < 6 ? "NaN" : String.format(Locale.ROOT, "%.0f", ev[5])));
        presses.setLength(presses.length() - System.getProperty("line.separator").length()); // extend the row
        long[] gc = gc();
        presses.append(String.format(Locale.ROOT, ",%s,%s,%s,%d,%d%n", ev == null || ev.length < 9 ? "NaN" : String.format(Locale.ROOT, "%.0f", ev[6]),
            ev == null || ev.length < 9 ? "NaN" : String.format(Locale.ROOT, "%.0f", ev[7]), ev == null || ev.length < 9 ? "NaN" : String.format(Locale.ROOT, "%.0f", ev[8]),
            gc[0] - gcCount0, gc[1] - gcMillis0));
    }

    /** {collections, milliseconds} summed over all garbage collectors. */
    private static long[] gc()
    {
        long n = 0, ms = 0;
        for (java.lang.management.GarbageCollectorMXBean b : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans())
        {
            n += Math.max(0L, b.getCollectionCount());
            ms += Math.max(0L, b.getCollectionTime());
        }
        return new long[] {n, ms};
    }

    private void flush()
    {
        try
        {
            File dir = new File(mc.mcDataDir, "hrm-view");
            dir.mkdirs();
            boolean append = modeIndex > 0 || trial > 0;
            FileWriter w = new FileWriter(new File(dir, "frames.csv"), append);
            w.write(out.toString());
            w.close();
            out.setLength(0);
            w = new FileWriter(new File(dir, "presses.csv"), append);
            w.write(presses.toString());
            w.close();
            presses.setLength(0);
        }
        catch (IOException ex)
        {
            ex.printStackTrace();
        }
    }

    private static void log(String s)
    {
        System.out.println("[tactile-view] " + s);
    }
}
