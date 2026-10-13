package com.tactile.client;

import org.lwjgl.input.Keyboard;

import com.tactile.client.live.TactileConfig;

import net.minecraft.client.gui.GuiTextField;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.GuiConfigEntries;
import net.minecraftforge.fml.client.config.GuiSlider;
import net.minecraftforge.fml.client.config.IConfigElement;

/**
 * A rate control: a slider over the common range and a box for typing any value up to the cap. Values snap to a
 * multiple of the step. {@link Physics}: the physics rate (20–960 on the slider, up to 1920 typed, steps of 20).
 * {@link Poll}: the key poll rate (250–8000, steps of 250), grayed out unless Key reading is POLLER.
 */
public abstract class RateEntry extends GuiConfigEntries.ListEntryBase implements GuiSlider.ISlider
{
    private static final int BOX_WIDTH = 60;

    private final int min, step, sliderMin, sliderMax, cap;
    private final GuiSlider slider;
    private final GuiTextField box;
    private final int before;
    private int value;

    /** Physics steps per second (multiple of 20). */
    public static final class Physics extends RateEntry
    {
        public Physics(GuiConfig owningScreen, GuiConfigEntries owningEntryList, IConfigElement configElement)
        {
            super(owningScreen, owningEntryList, configElement, 20, 20, 20, 960, TactileConfig.MAX_CONFIG_RATE_HZ);
        }
    }

    /** Key poll rate; only used by the poller. */
    public static final class Poll extends RateEntry
    {
        public Poll(GuiConfig owningScreen, GuiConfigEntries owningEntryList, IConfigElement configElement)
        {
            super(owningScreen, owningEntryList, configElement, 250, 250, 250, TactileConfig.MAX_POLL_RATE_HZ, TactileConfig.MAX_POLL_RATE_HZ);
        }

        @Override
        public boolean enabled()
        {
            return super.enabled() && DependentEntries.siblingIs(owningEntryList, "inputSource", TactileConfig.InputSource.POLLER.name());
        }
    }

    /** STEPPED's step rate: any whole number 1-1920 (slider 20-240). */
    public static final class Step extends RateEntry
    {
        public Step(GuiConfig owningScreen, GuiConfigEntries owningEntryList, IConfigElement configElement)
        {
            super(owningScreen, owningEntryList, configElement, TactileConfig.MIN_STEP_RATE_HZ, 1, 20, 240, TactileConfig.MAX_CONFIG_RATE_HZ);
        }

        @Override
        public boolean enabled()
        {
            return super.enabled() && DependentEntries.siblingIs(owningEntryList, "mode", TactileConfig.Mode.STEPPED.name());
        }
    }

    protected RateEntry(GuiConfig owningScreen, GuiConfigEntries owningEntryList, IConfigElement configElement, int min, int step, int sliderMin, int sliderMax, int cap)
    {
        super(owningScreen, owningEntryList, configElement);
        this.min = min;
        this.step = step;
        this.sliderMin = sliderMin;
        this.sliderMax = sliderMax;
        this.cap = cap;
        before = clamp(Integer.parseInt(configElement.get().toString()));
        value = before;
        slider = new GuiSlider(0, owningEntryList.controlX, 0, owningEntryList.controlWidth - BOX_WIDTH - 4, 18, "", " Hz",
            sliderMin, sliderMax, Math.max(sliderMin, Math.min(value, sliderMax)), false, true, this);
        box = new GuiTextField(11, mc.fontRendererObj, 0, 0, BOX_WIDTH - 2, 16);
        box.setMaxStringLength(5);
        box.setText(String.valueOf(value));
    }

    private int clamp(int hz)
    {
        return Math.max(min, Math.min(cap, step * Math.round(hz / (float)step)));
    }

    /** Slider moved: snap to a multiple of the step and show it in the box. */
    @Override
    public void onChangeSliderValue(GuiSlider s)
    {
        int v = clamp((int)Math.round(s.getValue()));
        s.setValue(v);
        s.displayString = v + " Hz";
        value = v;
        box.setText(String.valueOf(v));
        isValidValue = true;
    }

    private void syncSlider()
    {
        slider.setValue(Math.max(sliderMin, Math.min(value, sliderMax)));
        slider.displayString = value + " Hz"; // the knob rests at an end of the slider for typed values outside its range
    }

    @Override
    public void drawEntry(int slotIndex, int x, int y, int listWidth, int slotHeight, int mouseX, int mouseY, boolean isSelected)
    {
        boolean on = enabled();
        drawLabel = on;
        super.drawEntry(slotIndex, x, y, listWidth, slotHeight, mouseX, mouseY, isSelected);
        if (!on) DependentEntries.drawGreyLabel(this, owningScreen, name, y, slotHeight);
        slider.xPosition = owningEntryList.controlX;
        slider.yPosition = y;
        slider.width = owningEntryList.controlWidth - BOX_WIDTH - 4;
        slider.enabled = on;
        slider.drawButton(mc, mouseX, mouseY);
        if (!slider.dragging) syncSlider();
        box.xPosition = owningEntryList.controlX + owningEntryList.controlWidth - BOX_WIDTH + 1;
        box.yPosition = y + 1;
        box.setEnabled(on);
        box.drawTextBox();
    }

    @Override
    public boolean mousePressed(int index, int x, int y, int mouseEvent, int relativeX, int relativeY)
    {
        if (enabled() && slider.mousePressed(mc, x, y))
        {
            slider.playPressSound(mc.getSoundHandler());
            box.setFocused(false);
            return true;
        }
        return super.mousePressed(index, x, y, mouseEvent, relativeX, relativeY);
    }

    @Override
    public void mouseReleased(int index, int x, int y, int mouseEvent, int relativeX, int relativeY)
    {
        super.mouseReleased(index, x, y, mouseEvent, relativeX, relativeY);
        slider.mouseReleased(x, y);
    }

    @Override
    public void mouseClicked(int x, int y, int mouseEvent)
    {
        if (enabled()) box.mouseClicked(x, y, mouseEvent);
        else box.setFocused(false);
    }

    @Override
    public void keyTyped(char c, int key)
    {
        if (!enabled() || !box.isFocused()) return;
        boolean nav = key == Keyboard.KEY_BACK || key == Keyboard.KEY_DELETE || key == Keyboard.KEY_LEFT || key == Keyboard.KEY_RIGHT
            || key == Keyboard.KEY_HOME || key == Keyboard.KEY_END;
        if (!nav && !Character.isDigit(c)) return;
        box.textboxKeyTyped(c, key);
        try
        {
            int typed = Integer.parseInt(box.getText().trim());
            isValidValue = typed >= min && typed <= cap;
            if (isValidValue)
            {
                value = clamp(typed);
                syncSlider();
            }
        }
        catch (NumberFormatException e)
        {
            isValidValue = false;
        }
    }

    @Override
    public void updateCursorCounter()
    {
        box.updateCursorCounter();
    }

    @Override
    public boolean isDefault()
    {
        return value == Integer.parseInt(configElement.getDefault().toString());
    }

    @Override
    public void setToDefault()
    {
        if (!enabled()) return;
        value = clamp(Integer.parseInt(configElement.getDefault().toString()));
        box.setText(String.valueOf(value));
        isValidValue = true;
        syncSlider();
    }

    @Override
    public boolean isChanged()
    {
        return value != before;
    }

    @Override
    public void undoChanges()
    {
        if (!enabled()) return;
        value = before;
        box.setText(String.valueOf(value));
        isValidValue = true;
        syncSlider();
    }

    @Override
    public boolean saveConfigElement()
    {
        if (enabled() && isChanged()) configElement.set(value);
        return false;
    }

    @Override
    public Object getCurrentValue()
    {
        return value;
    }

    @Override
    public Object[] getCurrentValues()
    {
        return new Object[] {value};
    }
}
