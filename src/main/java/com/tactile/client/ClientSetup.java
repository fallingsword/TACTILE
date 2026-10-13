package com.tactile.client;

import java.io.File;

import org.lwjgl.input.Keyboard;

import com.tactile.Tactile;
import com.tactile.asm.HookStatus;
import com.tactile.client.live.TactileConfig;
import com.tactile.client.live.LiveEngine;
import com.tactile.hook.TactileHooks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;

/** Client-only setup, so a dedicated server never loads client classes. */
public final class ClientSetup
{
    public static final TactileConfig CONFIG = new TactileConfig();
    public static LiveEngine engine;

    private static final String KEY_CATEGORY = "tactile.key.category";
    private static final KeyBinding TOGGLE = new KeyBinding("tactile.key.mode", Keyboard.KEY_F7, KEY_CATEGORY);
    private static final KeyBinding OVERLAY = new KeyBinding("tactile.key.overlay", Keyboard.KEY_F8, KEY_CATEGORY);
    private static final KeyBinding SETTINGS = new KeyBinding("tactile.key.settings", Keyboard.KEY_NONE, KEY_CATEGORY);

    private ClientSetup()
    {
    }

    public static void preInit(File configFile)
    {
        // the mod was called High-Resolution Movement (config/hrmovement.cfg) before 0.4.0
        File legacy = new File(configFile.getParentFile(), "hrmovement.cfg");
        if (!configFile.exists() && legacy.isFile() && legacy.renameTo(configFile))
            System.out.println("[tactile] migrated " + legacy.getName() + " to " + configFile.getName());
        CONFIG.load(configFile);
    }

    public static void init()
    {
        System.out.println("[tactile] hook status: " + HookStatus.detail);
        engine = new LiveEngine(CONFIG);
        TactileHooks.setHandler(engine);
        MinecraftForge.EVENT_BUS.register(engine);
        MinecraftForge.EVENT_BUS.register(new ClientSetup.Events());
        ClientRegistry.registerKeyBinding(TOGGLE);
        ClientRegistry.registerKeyBinding(OVERLAY);
        ClientRegistry.registerKeyBinding(SETTINGS);

        // Developer tools (recorder, probes) exist only in development builds; the release jar leaves them out.
        try
        {
            Class.forName("com.tactile.client.DevTools").getMethod("register").invoke(null);
        }
        catch (ClassNotFoundException e)
        {
            // release build: no developer tools
        }
        catch (Exception e)
        {
            System.out.println("[tactile] developer tools failed to start: " + e);
        }
    }

    /** One-line description of a mode for chat and the overlay. */
    public static String describe(TactileConfig.Mode mode, int rateHz)
    {
        switch (mode)
        {
            case STEPPED:
                return "STEPPED, " + CONFIG.stepRateHz + " Hz steps (keys take effect at each step)";
            case EXACT:
                return "EXACT (keys take effect at their exact time)";
            default:
                return "VANILLA (20 Hz)";
        }
    }

    public static final class Events
    {
        @SubscribeEvent
        public void onKey(InputEvent.KeyInputEvent event)
        {
            Minecraft mc = Minecraft.getMinecraft();
            if (TOGGLE.isPressed())
            {
                // cycles VANILLA -> STEPPED -> EXACT -> VANILLA
                TactileConfig.Mode[] all = TactileConfig.Mode.values();
                CONFIG.mode = all[(CONFIG.mode.ordinal() + 1) % all.length];
                CONFIG.save();
                ++CONFIG.generation;
                if (mc.thePlayer != null)
                    mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[TACTILE] " + EnumChatFormatting.RESET
                        + "Movement: " + describe(CONFIG.mode, 20 * CONFIG.stepsPerTick())));
            }
            if (OVERLAY.isPressed())
            {
                TactileConfig.Overlay[] all = TactileConfig.Overlay.values();
                CONFIG.overlay = all[(CONFIG.overlay.ordinal() + 1) % all.length];
                CONFIG.save();
            }
            if (SETTINGS.isPressed() && mc.currentScreen == null) mc.displayGuiScreen(new TactileGuiFactory.ConfigScreen(null));
        }

        @SubscribeEvent
        public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event)
        {
            if (!Tactile.MODID.equals(event.modID)) return;
            CONFIG.sync(); // saves, and bumps the generation so the engine restarts with the new settings next tick
        }

        @SubscribeEvent
        public void onOverlay(RenderGameOverlayEvent.Text event)
        {
            if (CONFIG.overlay == TactileConfig.Overlay.OFF || engine == null || Minecraft.getMinecraft().thePlayer == null) return;
            if (!event.left.isEmpty()) event.left.add(""); // separate from the F3 lines
            event.left.add(engine.statusLine());
            if (CONFIG.overlay == TactileConfig.Overlay.DETAILED) event.left.addAll(engine.diag.lines(engine));
        }
    }
}
