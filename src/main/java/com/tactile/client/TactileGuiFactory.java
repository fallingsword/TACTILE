package com.tactile.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.tactile.Tactile;
import com.tactile.client.live.TactileConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.client.IModGuiFactory;
import net.minecraftforge.fml.client.config.DummyConfigElement;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.IConfigElement;

/** Config screen: Mods → TACTILE → Config, Options → Mod Options in game, or the settings key. */
public final class TactileGuiFactory implements IModGuiFactory
{
    @Override
    public void initialize(Minecraft mc)
    {
    }

    @Override
    public Class<? extends GuiScreen> mainConfigGuiClass()
    {
        return ConfigScreen.class;
    }

    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories()
    {
        return null;
    }

    @Override
    public RuntimeOptionGuiHandler getHandlerFor(RuntimeOptionCategoryElement element)
    {
        return null;
    }

    public static final class ConfigScreen extends GuiConfig
    {
        public ConfigScreen(GuiScreen parent)
        {
            super(parent, elements(), Tactile.MODID, false, false, I18n.format("tactile.config.title"));
        }

        /**
         * First page: only what most players need (the main switch, overlay, chat notices). Everything else is on the
         * Advanced page, where settings that do not apply to the current choice are grayed out.
         */
        private static List<IConfigElement> elements()
        {
            Configuration cfg = ClientSetup.CONFIG.configuration();
            // properties are looked up directly: Configuration.get(cat, key, int) would reset their range to the full int range
            ConfigCategory movement = cfg.getCategory(TactileConfig.CAT_MOVEMENT);
            ConfigCategory display = cfg.getCategory(TactileConfig.CAT_DISPLAY);
            movement.get("enabled").setConfigEntryClass(DependentEntries.SwitchEntry.class);
            display.get("chatMessages").setConfigEntryClass(DependentEntries.SwitchEntry.class);
            List<IConfigElement> l = new ArrayList<IConfigElement>();
            l.add(new ConfigElement(movement.get("enabled")));
            l.add(new ConfigElement(display.get("overlay")));
            l.add(new ConfigElement(display.get("chatMessages")));
            l.add(new DummyConfigElement.DummyCategoryElement("advanced", "tactile.config.advanced", advancedElements()));
            return l;
        }

        /** The Advanced page: mode, physics rate, key reading, poll rate (POLLER only), step rate and tap latch (STEPPED only). */
        static List<IConfigElement> advancedElements()
        {
            Configuration cfg = ClientSetup.CONFIG.configuration();
            ConfigCategory movement = cfg.getCategory(TactileConfig.CAT_MOVEMENT);
            ConfigCategory input = cfg.getCategory(TactileConfig.CAT_INPUT);
            movement.get("physicsRateHz").setConfigEntryClass(RateEntry.Physics.class);
            movement.get("stepRateHz").setConfigEntryClass(RateEntry.Step.class);
            input.get("pollRateHz").setConfigEntryClass(RateEntry.Poll.class);
            input.get("pressLatch").setConfigEntryClass(DependentEntries.LatchEntry.class);
            List<IConfigElement> advanced = new ArrayList<IConfigElement>();
            advanced.add(new ConfigElement(movement.get("mode")));
            advanced.add(new ConfigElement(movement.get("stepRateHz")));
            advanced.add(new ConfigElement(movement.get("physicsRateHz")));
            advanced.add(new ConfigElement(input.get("inputSource")));
            advanced.add(new ConfigElement(input.get("pollRateHz")));
            advanced.add(new ConfigElement(input.get("pressLatch")));
            return advanced;
        }
    }
}
