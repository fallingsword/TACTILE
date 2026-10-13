package com.tactile.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.tactile.client.live.TactileConfig;
import com.tactile.core.TickInput;

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
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Camera continuity where control changes hands (HRMOVE_TRANSPROBE=1 with HRMOVE_INPUT=SCRIPTED). The player
 * sprints in EXACT mode across a strip of water, which hands control to vanilla ("liquid nearby") and back. Every
 * frame records the position actually drawn (read during world rendering). Reported ([tactile-trans]): the largest
 * backward step of the camera (against the direction of travel) and the largest forward step relative to the
 * median, during the run and its hand-overs. HRMOVE_NOBLEND=1 gives the result without the hand-over blend.
 */
public final class TransitionProbe
{
    private static final int RUN_TICKS = 120;

    private final Minecraft mc = Minecraft.getMinecraft();
    private int phase, counter, ox, oz;
    private boolean recording;
    private final List<double[]> frames = new ArrayList<double[]>(); // {z, engineActive}

    public static boolean requested()
    {
        return "1".equals(System.getenv("HRMOVE_TRANSPROBE"));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTick(TickEvent.ClientTickEvent e)
    {
        if (e.phase != TickEvent.Phase.START) return;
        switch (phase)
        {
            case 0:
                if (mc.theWorld == null && mc.currentScreen instanceof GuiMainMenu)
                {
                    mc.gameSettings.pauseOnLostFocus = false;
                    mc.launchIntegratedServer("hrm_baseline", "hrm_baseline", new WorldSettings(0L, WorldSettings.GameType.SURVIVAL, false, false, WorldType.FLAT));
                    phase = 1;
                }
                break;
            case 1:
                if (TestWorld.ensurePlayerReady(mc) && ++counter > 60)
                {
                    ClientSetup.CONFIG.mode = TactileConfig.Mode.EXACT;
                    ++ClientSetup.CONFIG.generation;
                    build();
                    counter = 0;
                    phase = 2;
                }
                break;
            case 2:
                if (!TestWorld.ensurePlayerReady(mc)) break;
                if (++counter >= 60 && mc.thePlayer.onGround && ClientSetup.engine.isActive())
                {
                    List<TickInput> script = new ArrayList<TickInput>();
                    for (int i = 0; i < RUN_TICKS; ++i) script.add(TickInput.fromKeyString("W-----R", 0.0F, 0.0F));
                    ClientSetup.engine.loadScript(script, 1);
                    counter = 0;
                    recording = true;
                    phase = 3;
                }
                break;
            case 3:
                // vanilla reads the key bindings while it has control (the engine reads the script)
                net.minecraft.client.settings.KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), counter < RUN_TICKS);
                net.minecraft.client.settings.KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), counter < RUN_TICKS);
                if (++counter >= RUN_TICKS + 5)
                {
                    recording = false;
                    System.out.println(String.format("[tactile-trans] final player z %.3f", mc.thePlayer.posZ - oz));
                    report();
                    mc.shutdown();
                    phase = 9;
                }
                break;
            default:
                break;
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent e)
    {
        if (!recording) return;
        EntityPlayerSP p = mc.thePlayer;
        double z = p.lastTickPosZ + (p.posZ - p.lastTickPosZ) * e.partialTicks; // what the camera uses this frame
        double active = ClientSetup.engine.isActive() ? 1 : 0;
        if (!frames.isEmpty() && frames.get(frames.size() - 1)[1] != active)
            System.out.println(String.format("[tactile-trans] hand-over to %s at z %.3f (drawn), player z %.3f", active == 1 ? "engine" : "vanilla", z - oz, p.posZ - oz));
        frames.add(new double[] {z, active, System.nanoTime() / 1e9});
    }

    private void build()
    {
        final MinecraftServer server = mc.getIntegratedServer();
        TestWorld.protectPlayer(server);
        final WorldServer world = server.worldServerForDimension(0);
        BlockPos spawn = world.getSpawnPoint();
        ox = spawn.getX() - 25;
        oz = spawn.getZ() - 10;
        final int x0 = ox, z0 = oz;
        server.addScheduledTask(new Runnable()
        {
            public void run()
            {
                for (int x = -8; x <= 8; ++x) // wide clearing: earlier runs may have left flowing water around
                    for (int z = -6; z <= 64; ++z)
                    {
                        boolean floor = x >= -2 && x <= 2 && z >= -3 && z <= 60;
                        world.setBlockState(new BlockPos(x0 + x, 99, z0 + z), floor ? Blocks.stone.getDefaultState() : Blocks.air.getDefaultState(), 2);
                        for (int y = 100; y <= 103; ++y) world.setBlockToAir(new BlockPos(x0 + x, y, z0 + z));
                    }
                // a strip of soul sand in the floor: vanilla controls the player on it (and slows it), the engine after it
                for (int x = -2; x <= 2; ++x)
                    for (int z = 22; z <= 23; ++z) world.setBlockState(new BlockPos(x0 + x, 99, z0 + z), Blocks.soul_sand.getDefaultState(), 2);
                EntityPlayerMP mp = server.getConfigurationManager().playerEntityList.get(0);
                mp.setSprinting(false);
                mp.playerNetServerHandler.setPlayerLocation(x0 + 0.5D, 100.0D, z0 + 0.5D, 0.0F, 0.0F);
                server.getConfigurationManager().serverUpdateMountedMovingPlayer(mp);
            }
        });
    }

    private void report()
    {
        int n = frames.size();
        double[] steps = new double[Math.max(0, n - 1)];
        double maxBack = 0.0D, totalBack = 0.0D;
        int handovers = 0, backFrames = 0;
        for (int i = 1; i < n; ++i)
        {
            double d = frames.get(i)[0] - frames.get(i - 1)[0];
            steps[i - 1] = d;
            if (d < -1e-6D)
            {
                maxBack = Math.max(maxBack, -d);
                totalBack += -d;
                ++backFrames;
            }
            if (frames.get(i)[1] != frames.get(i - 1)[1]) ++handovers;
        }
        // camera speed per frame (blocks/s), so a long frame is not mistaken for a jump; worst near each hand-over
        double maxSpeed = 0.0D, maxSpeedNearHandover = 0.0D;
        List<Double> speeds = new ArrayList<Double>();
        for (int i = 1; i < n; ++i)
        {
            double dt = frames.get(i)[2] - frames.get(i - 1)[2];
            if (dt <= 0.0D) continue;
            double v = (frames.get(i)[0] - frames.get(i - 1)[0]) / dt;
            speeds.add(v);
            maxSpeed = Math.max(maxSpeed, v);
            boolean near = false;
            for (int j = Math.max(1, i - 30); j <= Math.min(n - 1, i + 30); ++j) if (frames.get(j)[1] != frames.get(j - 1)[1]) near = true;
            if (near) maxSpeedNearHandover = Math.max(maxSpeedNearHandover, v);
        }
        for (int i = 1; i < n; ++i)
        {
            if (frames.get(i)[1] == frames.get(i - 1)[1]) continue;
            for (int j = Math.max(1, i - 6); j <= Math.min(n - 1, i + 14); ++j)
                System.out.println(String.format("[tactile-trans]   frame %+d engine %d z %.4f step %.4f dt %.2f ms", j - i, (int)frames.get(j)[1],
                    frames.get(j)[0] - oz, frames.get(j)[0] - frames.get(j - 1)[0], (frames.get(j)[2] - frames.get(j - 1)[2]) * 1e3));
        }
        int worst = -1;
        double worstV = 0.0D;
        for (int i = 1; i < n; ++i)
        {
            double dt = frames.get(i)[2] - frames.get(i - 1)[2];
            double v = dt > 0.0D ? (frames.get(i)[0] - frames.get(i - 1)[0]) / dt : 0.0D;
            if (v > worstV)
            {
                worstV = v;
                worst = i;
            }
        }
        for (int j = Math.max(1, worst - 4); worst > 0 && j <= Math.min(n - 1, worst + 4); ++j)
            System.out.println(String.format("[tactile-trans]   fastest %+d engine %d z %.4f step %.4f dt %.2f ms", j - worst, (int)frames.get(j)[1],
                frames.get(j)[0] - oz, frames.get(j)[0] - frames.get(j - 1)[0], (frames.get(j)[2] - frames.get(j - 1)[2]) * 1e3));
        java.util.Collections.sort(speeds);
        System.out.println(String.format("[tactile-trans] camera speed: median %.2f b/s, max %.2f, max within 30 frames of a hand-over %.2f",
            speeds.isEmpty() ? 0.0D : speeds.get(speeds.size() / 2), maxSpeed, maxSpeedNearHandover));
        double[] sorted = steps.clone();
        Arrays.sort(sorted);
        double median = sorted.length == 0 ? 0.0D : sorted[sorted.length / 2], maxStep = sorted.length == 0 ? 0.0D : sorted[sorted.length - 1];
        System.out.println(String.format("[tactile-trans] blend %s: frames %d, hand-overs %d, backward frames %d (total %.4f), max backward step %.4f, max step %.4f (median %.4f, ratio %.1f)",
            "1".equals(System.getenv("HRMOVE_NOBLEND")) ? "off" : "on", n, handovers, backFrames, totalBack, maxBack, maxStep, median, median > 0 ? maxStep / median : 0.0D));
    }
}
