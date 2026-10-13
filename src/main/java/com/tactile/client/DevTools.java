package com.tactile.client;

import com.tactile.client.input.InputProbe;

import net.minecraftforge.common.MinecraftForge;

/**
 * Developer tools: the baseline recorder and the in-game probes, each switched on by an HRMOVE_* environment variable
 * (see docs/USAGE.md). Not part of the release jar (build.gradle excludes this class and the tools): ClientSetup
 * reaches it by name only, so a release build simply has none of them.
 */
public final class DevTools
{
    private DevTools()
    {
    }

    /** Called by ClientSetup.init through reflection. */
    public static void register()
    {
        if (BaselineRunner.requested())
        {
            MinecraftForge.EVENT_BUS.register(new BaselineRunner());
            System.out.println("[tactile] baseline recording requested via HRMOVE_BASELINE=1");
        }
        if (CameraProbe.requested()) MinecraftForge.EVENT_BUS.register(new CameraProbe());
        if (UiProbe.requested()) MinecraftForge.EVENT_BUS.register(new UiProbe());
        if (PlaceProbe.requested()) MinecraftForge.EVENT_BUS.register(new PlaceProbe());
        if (BridgeProbe.requested()) MinecraftForge.EVENT_BUS.register(new BridgeProbe());
        if (TransitionProbe.requested()) MinecraftForge.EVENT_BUS.register(new TransitionProbe());
        if (FlightProbe.requested()) MinecraftForge.EVENT_BUS.register(new FlightProbe());
        if (LatencyProbe.requested()) MinecraftForge.EVENT_BUS.register(new LatencyProbe());
        if (PillarProbe.requested()) MinecraftForge.EVENT_BUS.register(new PillarProbe());
        if (ViewProbe.requested()) MinecraftForge.EVENT_BUS.register(new ViewProbe());
        if (HitProbe.requested()) MinecraftForge.EVENT_BUS.register(new HitProbe());
        if (FaultProbe.requested()) MinecraftForge.EVENT_BUS.register(new FaultProbe());
        String probe = InputProbe.mode();
        if (probe.equals("taptest"))
        {
            MinecraftForge.EVENT_BUS.register(new com.tactile.client.input.TapTest());
            System.out.println("[tactile] tap test enabled");
        }
        else if (probe.equals("auto") || probe.equals("manual"))
        {
            MinecraftForge.EVENT_BUS.register(new InputProbe(probe.equals("auto")));
            System.out.println("[tactile] input probe enabled (" + probe + ")");
        }
    }
}
