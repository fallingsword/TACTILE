package com.tactile.client;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Measures camera smoothness (HRMOVE_CAMERAPROBE=1). On every rendered frame it records what the
 * renderer actually uses, read inside world rendering (after any position swap):
 * <ul>
 * <li>camera position: prevPos + (pos - prevPos) * partialTicks;</li>
 * <li>view-bob phase: distanceWalked + (distanceWalked - prevDistanceWalked) * partialTicks (EntityRenderer.setupViewBobbing).</li>
 * </ul>
 * During steady straight-line sprinting both should advance at a constant rate. The reported metric
 * is the coefficient of variation of their per-frame rate, against two time bases (frame start, world
 * render). Frames are kept only while sprinting on the ground (>4 b/s, no vertical motion). Results are
 * appended to run/hrm-camera.txt on exit.
 */
public final class CameraProbe
{
    private final Minecraft mc = Minecraft.getMinecraft();
    private long frameStart;
    private double lastX, lastZ, lastY, lastPhase;
    private long lastStart, lastRender;
    private final List<double[]> samples = new ArrayList<double[]>(); // {posSpeedStart, posSpeedRender, phaseSpeedStart, phaseSpeedRender}

    public CameraProbe()
    {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable()
        {
            public void run()
            {
                write();
            }
        }, "tactile-camera-probe"));
    }

    public static boolean requested()
    {
        return "1".equals(System.getenv("HRMOVE_CAMERAPROBE"));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRenderTick(TickEvent.RenderTickEvent e)
    {
        if (e.phase == TickEvent.Phase.START) frameStart = System.nanoTime();
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent e)
    {
        Entity v = mc.getRenderViewEntity();
        if (!(v instanceof EntityPlayer)) return;
        EntityPlayer p = (EntityPlayer)v;
        float pt = e.partialTicks;
        double x = v.prevPosX + (v.posX - v.prevPosX) * pt;
        double y = v.prevPosY + (v.posY - v.prevPosY) * pt;
        double z = v.prevPosZ + (v.posZ - v.prevPosZ) * pt;
        double phase = p.distanceWalkedModified + (p.distanceWalkedModified - p.prevDistanceWalkedModified) * pt;
        long render = System.nanoTime();
        if (lastStart != 0)
        {
            double dts = (frameStart - lastStart) / 1e9, dtr = (render - lastRender) / 1e9;
            double dist = Math.sqrt((x - lastX) * (x - lastX) + (z - lastZ) * (z - lastZ));
            double dPhase = phase - lastPhase;
            if (dist / dtr > 4.0D && dist / dtr < 12.0D && Math.abs(y - lastY) < 1e-9 && dts > 0 && dtr > 0)
            {
                synchronized (this)
                {
                    samples.add(new double[] {dist / dts, dist / dtr, dPhase / dts, dPhase / dtr});
                }
            }
        }
        lastX = x;
        lastY = y;
        lastZ = z;
        lastPhase = phase;
        lastStart = frameStart;
        lastRender = render;
    }

    private static double cv(List<double[]> s, int i)
    {
        double sum = 0, sq = 0;
        for (double[] a : s) sum += a[i];
        double mean = sum / s.size();
        for (double[] a : s) sq += (a[i] - mean) * (a[i] - mean);
        return 100.0D * Math.sqrt(sq / s.size()) / Math.abs(mean);
    }

    private synchronized void write()
    {
        try
        {
            if (samples.size() < 10) return;
            String line = String.format(Locale.ROOT,
                "frames=%d | position CV: vs frame start %.1f%%, vs render %.1f%% | bob-phase CV: vs frame start %.1f%%, vs render %.1f%% (mode=%s rate=%s noInterp=%s fps=%s)%n",
                samples.size(), cv(samples, 0), cv(samples, 1), cv(samples, 2), cv(samples, 3), System.getenv("HRMOVE_MODE"),
                System.getenv("HRMOVE_RATE"), System.getenv("HRMOVE_NOINTERP"), System.getenv("HRMOVE_FPS"));
            FileWriter w = new FileWriter(new File(mc.mcDataDir, "hrm-camera.txt"), true);
            w.write(line);
            w.close();
        }
        catch (Exception ex)
        {
            ex.printStackTrace();
        }
    }
}
