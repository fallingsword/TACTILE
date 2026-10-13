package com.tactile.client;

import com.tactile.client.live.TactileConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.GuiConfigEntries;
import net.minecraftforge.fml.client.config.GuiConfigEntries.IConfigEntry;
import net.minecraftforge.fml.client.config.GuiUtils;
import net.minecraftforge.fml.client.config.IConfigElement;

/**
 * Settings that only matter for another setting's value are grayed out (not clickable, label dimmed) while they do
 * not apply. The check reads the other entry's current, not yet saved value, so it updates as soon as it is changed.
 */
public final class DependentEntries
{
    private DependentEntries()
    {
    }

    /** True if the entry named {@code name} on the same page currently shows {@code value} (true if it is absent). */
    static boolean siblingIs(GuiConfigEntries list, String name, String value)
    {
        if (list.listEntries == null) return true;
        for (IConfigEntry e : list.listEntries)
            if (e != null && name.equals(e.getName())) return value.equalsIgnoreCase(String.valueOf(e.getCurrentValue()));
        return true;
    }

    static void drawGreyLabel(GuiConfigEntries.ListEntryBase entry, GuiConfig screen, String name, int y, int slotHeight)
    {
        Minecraft mc = Minecraft.getMinecraft();
        mc.fontRendererObj.drawString(EnumChatFormatting.DARK_GRAY + name, screen.entryList.labelX,
            y + slotHeight / 2 - mc.fontRendererObj.FONT_HEIGHT / 2, 16777215);
    }

    /** "Keep sub-step taps": only STEPPED uses it. */
    public static final class LatchEntry extends SwitchEntry
    {
        public LatchEntry(GuiConfig owningScreen, GuiConfigEntries owningEntryList, IConfigElement configElement)
        {
            super(owningScreen, owningEntryList, configElement);
        }

        @Override
        public boolean enabled()
        {
            return super.enabled() && siblingIs(owningEntryList, "mode", TactileConfig.Mode.STEPPED.name());
        }
    }

    /** A boolean setting shown as ON / OFF (Forge's own entry shows true / false). */
    public static class SwitchEntry extends GuiConfigEntries.ButtonEntry
    {
        private final boolean before;
        private boolean current;

        public SwitchEntry(GuiConfig owningScreen, GuiConfigEntries owningEntryList, IConfigElement configElement)
        {
            super(owningScreen, owningEntryList, configElement);
            before = Boolean.valueOf(configElement.get().toString());
            current = before;
            updateValueButtonText();
        }

        @Override
        public void drawEntry(int slotIndex, int x, int y, int listWidth, int slotHeight, int mouseX, int mouseY, boolean isSelected)
        {
            boolean on = enabled();
            drawLabel = on;
            super.drawEntry(slotIndex, x, y, listWidth, slotHeight, mouseX, mouseY, isSelected);
            if (!on) drawGreyLabel(this, owningScreen, name, y, slotHeight);
        }

        @Override
        public void updateValueButtonText()
        {
            btnValue.displayString = current ? "ON" : "OFF";
            btnValue.packedFGColour = current ? GuiUtils.getColorCode('2', true) : GuiUtils.getColorCode('4', true);
        }

        @Override
        public void valueButtonPressed(int slotIndex)
        {
            if (enabled()) current = !current;
        }

        @Override
        public boolean isDefault()
        {
            return current == Boolean.valueOf(configElement.getDefault().toString());
        }

        @Override
        public void setToDefault()
        {
            if (!enabled()) return;
            current = Boolean.valueOf(configElement.getDefault().toString());
            updateValueButtonText();
        }

        @Override
        public boolean isChanged()
        {
            return current != before;
        }

        @Override
        public void undoChanges()
        {
            if (!enabled()) return;
            current = before;
            updateValueButtonText();
        }

        @Override
        public boolean saveConfigElement()
        {
            if (enabled() && isChanged()) configElement.set(current);
            return false;
        }

        @Override
        public Boolean getCurrentValue()
        {
            return current;
        }

        @Override
        public Boolean[] getCurrentValues()
        {
            return new Boolean[] {current};
        }
    }
}
