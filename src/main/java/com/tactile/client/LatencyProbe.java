package com.tactile.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

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
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Jump latency as drawn (HRMOVE_LATENCYPROBE=1 with HRMOVE_INPUT=SCRIPTED). Standing still in EXACT mode, a jump
 * press is injected into the live engine at a random moment between ticks, exactly as a key-poller event would
 * enter. Every frame records the drawn height. Reported per physics rate ([tactile-latency]): the time from the
 * press to the first frame drawn higher than before, and the height drawn 25, 50 and 100 ms after the press.
 */
public final class LatencyProbe
{
    private static final int[] RATES = {20, 60, 240, 960};
    private static final int TRIALS = 6;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Random rnd = new Random(5L);
    private int phase, counter, rateIndex, trial, waitFrames;
    private long pressNanos;
    private double baseY;
    private boolean recording, pressed;
    private final List<double[]> frames = new ArrayList<double[]>(); // {ms since press, height}
    private final StringBuilder report = new StringBuilder();
    private double sumFirst, sum25, sum50, sum100;

    public static boolean requested()
    {
        return "1".equals(System.getenv("HRMOVE_LATENCYPROBE"));
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e)
    {
        if (e.phase != TickEvent.Phase.END) return;
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
                    build();
                    startRate();
                }
                break;
            case 2: // settle, then arm a press at a random frame within the next tick
                if (!TestWorld.ensurePlayerReady(mc)) break;
                if (counter % 100 == 99)
                    System.out.println(String.format("[tactile-latency] settling: y %.4f onGround %b motionY %.4f active %b (%s)", mc.thePlayer.posY,
                        mc.thePlayer.onGround, mc.thePlayer.motionY, ClientSetup.engine.isActive(), ClientSetup.engine.debugState()));
                if (++counter >= 30 && mc.thePlayer.onGround && mc.thePlayer.motionY <= 0.0D && ClientSetup.engine.isActive())
                {
                    baseY = mc.thePlayer.posY;
                    frames.clear();
                    waitFrames = rnd.nextInt(12);
                    pressed = false;
                    recording = true;
                    counter = 0;
                    phase = 3;
                }
                break;
            case 3: // measuring (frames do the work); release after 10 ticks, then let the jump finish
                if (++counter == 10) ClientSetup.engine.injectKeys(TickInput.fromKeyString("-------", 0.0F, 0.0F));
                if (counter >= 30)
                {
                    recording = false;
                    analyze();
                    counter = 0;
                    if (++trial >= TRIALS)
                    {
                        report.append(String.format("[tactile-latency] EXACT %4d Hz: first rise %.1f ms, height after 25 ms %.4f, 50 ms %.4f, 100 ms %.4f (mean of %d)%n",
                            RATES[rateIndex], sumFirst / TRIALS, sum25 / TRIALS, sum50 / TRIALS, sum100 / TRIALS, TRIALS));
                        if (++rateIndex >= RATES.length)
                        {
                            System.out.print(report);
                            mc.shutdown();
                            phase = 9;
                            return;
                        }
                        startRate();
                        return;
                    }
                    phase = 2;
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
        long now = System.nanoTime();
        if (!pressed)
        {
            if (waitFrames-- > 0) return;
            pressed = true;
            pressNanos = now;
            ClientSetup.engine.injectKeys(TickInput.fromKeyString("----J--", 0.0F, 0.0F));
            return;
        }
        EntityPlayerSP p = mc.thePlayer;
        double y = p.lastTickPosY + (p.posY - p.lastTickPosY) * e.partialTicks; // what the camera uses this frame
        frames.add(new double[] {(now - pressNanos) / 1e6, y - baseY});
    }

    private void analyze()
    {
        double first = Double.NaN, h25 = 0, h50 = 0, h100 = 0;
        for (double[] f : frames)
        {
            if (Double.isNaN(first) && f[1] > 1e-3) first = f[0];
            if (f[0] <= 25.0D) h25 = f[1];
            if (f[0] <= 50.0D) h50 = f[1];
            if (f[0] <= 100.0D) h100 = f[1];
        }
        sumFirst += first;
        sum25 += h25;
        sum50 += h50;
        sum100 += h100;
    }

    private void startRate()
    {
        ClientSetup.CONFIG.mode = TactileConfig.Mode.EXACT;
        ClientSetup.CONFIG.physicsRateHz = RATES[rateIndex];
        ++ClientSetup.CONFIG.generation;
        trial = 0;
        sumFirst = sum25 = sum50 = sum100 = 0;
        counter = 0;
        phase = 2;
    }

    private void build()
    {
        final MinecraftServer server = mc.getIntegratedServer();
        TestWorld.protectPlayer(server);
        final WorldServer world = server.worldServerForDimension(0);
        BlockPos spawn = world.getSpawnPoint();
        final int x0 = spawn.getX() + 30, z0 = spawn.getZ() - 30;
        server.addScheduledTask(new Runnable()
        {
            public void run()
            {
                for (int x = -3; x <= 3; ++x)
                    for (int z = -3; z <= 3; ++z)
                    {
                        world.setBlockState(new BlockPos(x0 + x, 99, z0 + z), Blocks.stone.getDefaultState(), 2);
                        for (int y = 100; y <= 104; ++y) world.setBlockToAir(new BlockPos(x0 + x, y, z0 + z));
                    }
                EntityPlayerMP mp = server.getConfigurationManager().playerEntityList.get(0);
                mp.playerNetServerHandler.setPlayerLocation(x0 + 0.5D, 100.0D, z0 + 0.5D, 0.0F, 0.0F);
                server.getConfigurationManager().serverUpdateMountedMovingPlayer(mp);
            }
        });
    }
}
