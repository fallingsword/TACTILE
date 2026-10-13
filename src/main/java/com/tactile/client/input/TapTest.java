package com.tactile.client.input;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Random;

import com.tactile.client.ClientSetup;
import com.tactile.client.TestWorld;
import com.tactile.client.live.TactileConfig;
import com.sun.jna.Pointer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * End-to-end input-resolution test (HRMOVE_INPUTPROBE=taptest). The real forward key is pressed
 * through the OS (keybd_event) for 3-100 ms at random phases relative to the game ticks. The test
 * measures how far each tap moves the player, per mode (STEPPED at a 60 Hz step rate, EXACT at 60 and 240 Hz physics).
 * With true high-resolution input, distance grows smoothly with tap length. With vanilla's 20 Hz
 * KeyBinding it is quantized to whole ticks (or zero when the tap falls between two ticks).
 *
 * <p>Injection happens only while the Minecraft window is the foreground window. Results are written
 * to run/hrm-input/taps.csv.
 */
public final class TapTest
{
    private static final int[] DURATIONS_MS = {3, 6, 10, 15, 20, 30, 40, 60, 100};
    private static final int REPEATS = 3;
    private static final Object[][] MODES = {{"STEPPED", 60}, {"EXACT", 60}, {"EXACT", 240}};

    private enum Phase { MENU, LOADING, TELEPORT, SETTLE, ARMED, WAIT_STOP, DONE }

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Win32.User32 user32 = Win32.get();
    private final Random rnd = new Random(42L);
    private final StringBuilder out = new StringBuilder("mode,rateHz,durationMs,hrActive,dz\n");
    private Phase phase = Phase.MENU;
    private int counter, modeIndex, trial;
    private int ox, oy = 100, oz;
    private double startZ;
    private volatile Pointer gameWindow;
    private volatile boolean injecting, injected;
    private boolean hrActiveDuringTap;

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent e)
    {
        if (e.phase == TickEvent.Phase.START && org.lwjgl.opengl.Display.isActive() && mc.inGameHasFocus && user32 != null)
            gameWindow = user32.GetForegroundWindow();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e)
    {
        if (e.phase != TickEvent.Phase.END) return;
        switch (phase)
        {
            case MENU:
                if (mc.theWorld == null && mc.currentScreen instanceof GuiMainMenu)
                {
                    mc.gameSettings.pauseOnLostFocus = false;
                    mc.launchIntegratedServer("hrm_baseline", "hrm_baseline", new WorldSettings(0L, WorldSettings.GameType.SURVIVAL, false, false, WorldType.FLAT));
                    phase = Phase.LOADING;
                }
                break;
            case LOADING:
                if (TestWorld.ensurePlayerReady(mc) && ++counter > 60)
                {
                    TestWorld.protectPlayer(mc.getIntegratedServer());
                    buildRunway();
                    applyMode();
                    counter = 0;
                    phase = Phase.TELEPORT;
                }
                break;
            case TELEPORT:
                if (++counter >= 5)
                {
                    teleport();
                    counter = 0;
                    phase = Phase.SETTLE;
                }
                break;
            case SETTLE:
                if (++counter >= 30 && mc.thePlayer.onGround && mc.thePlayer.motionZ == 0.0D)
                {
                    startZ = mc.thePlayer.posZ;
                    hrActiveDuringTap = false;
                    injected = false;
                    phase = Phase.ARMED;
                    startTap(DURATIONS_MS[trial % DURATIONS_MS.length]);
                }
                break;
            case ARMED:
                if (ClientSetup.engine != null && ClientSetup.engine.isActive()) hrActiveDuringTap = true;
                if (injected)
                {
                    counter = 0;
                    phase = Phase.WAIT_STOP;
                }
                break;
            case WAIT_STOP:
                if (++counter >= 30)
                {
                    int dur = DURATIONS_MS[trial % DURATIONS_MS.length];
                    // the tap as the physics received it (engine modes): start time in ticks and hold length
                    double[] tap = ClientSetup.engine != null ? ClientSetup.engine.lastForwardTap() : new double[] {Double.NaN, Double.NaN};
                    out.append(MODES[modeIndex][0]).append(',').append(MODES[modeIndex][1]).append(',').append(dur).append(',')
                        .append(hrActiveDuringTap ? 1 : 0).append(',').append(mc.thePlayer.posZ - startZ).append(',')
                        .append(tap[0]).append(',').append(tap[1]).append('\n');
                    if (++trial >= DURATIONS_MS.length * REPEATS)
                    {
                        trial = 0;
                        if (++modeIndex >= MODES.length)
                        {
                            finish();
                            return;
                        }
                        applyMode();
                    }
                    counter = 0;
                    phase = Phase.TELEPORT;
                }
                break;
            default:
                break;
        }
    }

    private void applyMode()
    {
        ClientSetup.CONFIG.applyForTest(TactileConfig.Mode.valueOf((String)MODES[modeIndex][0]), (Integer)MODES[modeIndex][1]);
        log("mode " + MODES[modeIndex][0] + " " + MODES[modeIndex][1] + " Hz");
    }

    private void buildRunway()
    {
        final MinecraftServer server = mc.getIntegratedServer();
        final WorldServer world = server.worldServerForDimension(0);
        BlockPos spawn = world.getSpawnPoint();
        ox = spawn.getX() - 200;
        oz = spawn.getZ();
        final int x0 = ox, z0 = oz;
        server.addScheduledTask(new Runnable()
        {
            public void run()
            {
                for (int x = -3; x <= 3; ++x)
                    for (int z = -3; z <= 20; ++z)
                    {
                        world.setBlockState(new BlockPos(x0 + x, 99, z0 + z), Blocks.stone.getDefaultState(), 2);
                        for (int y = 100; y <= 103; ++y) world.setBlockToAir(new BlockPos(x0 + x, y, z0 + z));
                    }
            }
        });
    }

    private void teleport()
    {
        final MinecraftServer server = mc.getIntegratedServer();
        final double x = ox + 0.5D, y = oy, z = oz + 0.5D;
        server.addScheduledTask(new Runnable()
        {
            public void run()
            {
                EntityPlayerMP mp = server.getConfigurationManager().playerEntityList.get(0);
                mp.setSprinting(false);
                mp.playerNetServerHandler.setPlayerLocation(x, y, z, 0.0F, 0.0F);
            }
        });
    }

    /** Presses the forward key for {@code ms} after a random 0-60 ms delay, on a separate thread. */
    private void startTap(final int ms)
    {
        final int vk = VkMap.toVk(mc.gameSettings.keyBindForward.getKeyCode());
        final long delayNanos = (long)(rnd.nextDouble() * 60_000_000L);
        Thread t = new Thread(new Runnable()
        {
            public void run()
            {
                try
                {
                    long until = System.nanoTime() + 3_000_000_000L;
                    while (!isGameForeground())
                    {
                        if (System.nanoTime() > until)
                        {
                            log("game window not focused; tap skipped");
                            return;
                        }
                        Thread.sleep(50);
                    }
                    busyWait(delayNanos);
                    byte scan = (byte)user32.MapVirtualKeyA(vk, 0);
                    user32.keybd_event((byte)vk, scan, 0, null);
                    busyWait(ms * 1_000_000L);
                    user32.keybd_event((byte)vk, scan, Win32.KEYEVENTF_KEYUP, null);
                }
                catch (InterruptedException ignored)
                {
                }
                finally
                {
                    injected = true;
                }
            }
        }, "tactile-taptest");
        t.setDaemon(true);
        t.start();
    }

    private boolean isGameForeground()
    {
        Pointer g = gameWindow;
        return g != null && g.equals(user32.GetForegroundWindow());
    }

    private static void busyWait(long nanos)
    {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) Thread.yield();
    }

    private void finish()
    {
        phase = Phase.DONE;
        try
        {
            File dir = new File(mc.mcDataDir, "hrm-input");
            dir.mkdirs();
            PrintWriter w = new PrintWriter(new FileWriter(new File(dir, "taps.csv")));
            w.print(out);
            w.close();
        }
        catch (Exception ex)
        {
            ex.printStackTrace();
        }
        ClientSetup.CONFIG.mode = TactileConfig.Mode.VANILLA;
        log("done; results in run/hrm-input/taps.csv");
        mc.shutdown();
    }

    private static void log(String s)
    {
        System.out.println("[tactile-taptest] " + s);
    }
}
