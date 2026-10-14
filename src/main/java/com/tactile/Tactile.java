package com.tactile;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;

// .\gradlew.bat runClient launches the game. Automation (environment variables):
//   HRMOVE_BASELINE=1        record the movement corpus (BaselineRunner) and exit
//   HRMOVE_INPUTPROBE=auto   measure the input pipeline (InputProbe) and exit
//   HRMOVE_MODE/RATE/INPUT   override config for live-validation runs

@Mod(modid = Tactile.MODID, name = Tactile.NAME, version = Tactile.VERSION, clientSideOnly = true,
    guiFactory = "com.tactile.client.TactileGuiFactory")
public class Tactile
{
    public static final String MODID = "tactile";
    public static final String NAME = "TACTILE";
    public static final String VERSION = "1.0.1";

    @EventHandler
    public void preInit(FMLPreInitializationEvent event)
    {
        if (event.getSide() == Side.CLIENT) com.tactile.client.ClientSetup.preInit(event.getSuggestedConfigurationFile());
    }

    @EventHandler
    public void init(FMLInitializationEvent event)
    {
        if (event.getSide() == Side.CLIENT) com.tactile.client.ClientSetup.init();
    }
}
