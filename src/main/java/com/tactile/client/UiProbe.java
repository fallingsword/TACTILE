package com.tactile.client;

import com.tactile.Tactile;
import com.tactile.client.live.TactileConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * UI check (HRMOVE_UIPROBE=1). It opens the settings screens (main page; Advanced page with its defaults, then with
 * STEPPED and POLLER selected so the dependent settings are enabled), then enters the test world and switches modes
 * the way the settings screen does (property change plus a config-changed event), including the main switch OFF, and
 * checks what F7 saves. A screenshot is saved at each step to run/screenshots/tactile-ui-*.png. The probe edits the
 * real config and restores every property it touched at the end. It needs no input and does not need focus.
 */
public final class UiProbe
{
    private final Minecraft mc = Minecraft.getMinecraft();
    private int step, wait;
    private String shot;
    private String origEnabled, origMode, origOverlay, origSource, origChat;

    /** Lines in the chat history (GuiNewChat keeps them in a private list). */
    private int chatLines()
    {
        try
        {
            java.lang.reflect.Field f = net.minecraft.client.gui.GuiNewChat.class.getDeclaredField("chatLines");
            f.setAccessible(true);
            return ((java.util.List<?>)f.get(mc.ingameGUI.getChatGUI())).size();
        }
        catch (ReflectiveOperationException ex)
        {
            return -1000;
        }
    }

    public static boolean requested()
    {
        return "1".equals(System.getenv("HRMOVE_UIPROBE"));
    }

    private static ConfigCategory cat(String name)
    {
        return ClientSetup.CONFIG.configuration().getCategory(name);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e)
    {
        if (e.phase != TickEvent.Phase.END || shot != null || ++wait < 40) return;
        wait = 0;
        switch (step++)
        {
            case 0:
                if (!(mc.currentScreen instanceof GuiMainMenu))
                {
                    --step;
                    return;
                }
                origEnabled = cat(TactileConfig.CAT_MOVEMENT).get("enabled").getString();
                origMode = cat(TactileConfig.CAT_MOVEMENT).get("mode").getString();
                origOverlay = cat(TactileConfig.CAT_DISPLAY).get("overlay").getString();
                origSource = cat(TactileConfig.CAT_INPUT).get("inputSource").getString();
                log("original: enabled " + origEnabled + ", mode " + origMode + ", overlay " + origOverlay + ", key reading " + origSource);
                mc.displayGuiScreen(new TactileGuiFactory.ConfigScreen(mc.currentScreen));
                break;
            case 1:
                shot = "tactile-ui-main";
                break;
            case 2:
                setProps("true", "EXACT", "AUTO");
                openAdvanced();
                break;
            case 3:
                shot = "tactile-ui-advanced";
                break;
            case 4:
                setProps("true", "STEPPED", "POLLER");
                openAdvanced();
                break;
            case 5:
                shot = "tactile-ui-advanced-stepped-poller";
                break;
            case 6:
                setProps(origEnabled, origMode, origSource);
                mc.displayGuiScreen(null);
                mc.gameSettings.pauseOnLostFocus = false;
                mc.launchIntegratedServer("hrm_baseline", "hrm_baseline", new WorldSettings(0L, WorldSettings.GameType.SURVIVAL, false, false, WorldType.FLAT));
                break;
            case 7:
                if (!TestWorld.ensurePlayerReady(mc))
                {
                    --step;
                    return;
                }
                TestWorld.protectPlayer(mc.getIntegratedServer());
                setViaConfigScreen("true", "EXACT", "DETAILED");
                break;
            case 8:
                shot = "tactile-ui-exact-detailed";
                break;
            case 9:
                setViaConfigScreen("true", "STEPPED", "SIMPLE");
                break;
            case 10:
                shot = "tactile-ui-stepped-simple";
                break;
            case 11:
                setViaConfigScreen("false", "EXACT", "SIMPLE"); // main switch OFF
                break;
            case 12:
                shot = "tactile-ui-off-simple";
                break;
            case 13:
                // F7: VANILLA turns the switch off and keeps the selected mode; another mode turns it on and selects it
                ClientSetup.CONFIG.mode = TactileConfig.Mode.VANILLA;
                ClientSetup.CONFIG.save();
                log("F7 to VANILLA saves enabled " + cat(TactileConfig.CAT_MOVEMENT).get("enabled").getString() + ", mode "
                    + cat(TactileConfig.CAT_MOVEMENT).get("mode").getString());
                ClientSetup.CONFIG.mode = TactileConfig.Mode.STEPPED;
                ClientSetup.CONFIG.save();
                log("F7 to STEPPED saves enabled " + cat(TactileConfig.CAT_MOVEMENT).get("enabled").getString() + ", mode "
                    + cat(TactileConfig.CAT_MOVEMENT).get("mode").getString());
                // F7 through the real key handler: it may print to chat only while chat messages are on
                origChat = cat(TactileConfig.CAT_DISPLAY).get("chatMessages").getString();
                for (String chat : new String[] {"false", "true"})
                {
                    cat(TactileConfig.CAT_DISPLAY).get("chatMessages").set(chat);
                    ClientSetup.CONFIG.sync();
                    int before = chatLines();
                    net.minecraft.client.settings.KeyBinding.onTick(org.lwjgl.input.Keyboard.KEY_F7);
                    new ClientSetup.Events().onKey(null);
                    log("F7 with chat messages " + chat + ": chat lines added " + (chatLines() - before) + ", mode now "
                        + ClientSetup.CONFIG.mode);
                }
                cat(TactileConfig.CAT_DISPLAY).get("chatMessages").set(origChat);
                ClientSetup.CONFIG.sync();
                break;
            case 14:
                log("done: running " + ClientSetup.engine.runningMode());
                setProps(origEnabled, origMode, origSource);
                setViaConfigScreen(origEnabled, origMode, origOverlay);
                mc.shutdown();
                break;
            default:
                break;
        }
    }

    private void openAdvanced()
    {
        mc.displayGuiScreen(new GuiConfig(new TactileGuiFactory.ConfigScreen(null), TactileGuiFactory.ConfigScreen.advancedElements(),
            Tactile.MODID, false, false, "TACTILE: Advanced settings"));
    }

    private static void setProps(String enabled, String mode, String source)
    {
        cat(TactileConfig.CAT_MOVEMENT).get("enabled").set(enabled);
        cat(TactileConfig.CAT_MOVEMENT).get("mode").set(mode);
        cat(TactileConfig.CAT_INPUT).get("inputSource").set(source);
    }

    /** What the settings screen's Done button does: set properties, then post the config-changed event. */
    private void setViaConfigScreen(String enabled, String mode, String overlay)
    {
        cat(TactileConfig.CAT_MOVEMENT).get("enabled").set(enabled);
        cat(TactileConfig.CAT_MOVEMENT).get("mode").set(mode);
        cat(TactileConfig.CAT_DISPLAY).get("overlay").set(overlay);
        MinecraftForge.EVENT_BUS.post(new ConfigChangedEvent.OnConfigChangedEvent(Tactile.MODID, null, true, false));
        log("set enabled " + enabled + ", mode " + mode + ", overlay " + overlay + " -> running mode " + ClientSetup.CONFIG.mode);
    }

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent e)
    {
        if (e.phase != TickEvent.Phase.END || shot == null) return;
        log(ScreenShotHelper.saveScreenshot(mc.mcDataDir, shot + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer()).getUnformattedText()
            + " (running " + (ClientSetup.engine != null ? ClientSetup.engine.runningMode() : null) + ")");
        shot = null;
    }

    private static void log(String s)
    {
        System.out.println("[tactile-ui] " + s);
    }
}
