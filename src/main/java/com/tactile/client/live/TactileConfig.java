package com.tactile.client.live;

import java.io.File;

import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

/**
 * User settings (config/tactile.cfg), editable in game (Mods → TACTILE → Config, or
 * Options → Mod Options), with environment overrides for automated runs. Every change applies from the next
 * tick: the engine notices {@link #generation} changing and restarts itself with the new settings.
 */
public final class TactileConfig
{
    /**
     * VANILLA: unmodified (bit-exact). EXACT: substep physics, each substep split at every input timestamp, so every key
     * change acts at the instant it was observed and results do not depend on the physics rate. STEPPED (0.8.1; replaces
     * HIGH_RES): the same physics, but key changes take effect only at step boundaries (multiples of 1 / stepRateHz on
     * the engine clock), any rate from 1 to 1920 Hz. Declaration order is F7's cycle order.
     */
    public enum Mode { VANILLA, STEPPED, EXACT }

    public static final int MIN_STEP_RATE_HZ = 1, DEFAULT_STEP_RATE_HZ = 20;

    public enum InputSource
    {
        /** Key events where available (Windows), else the background poller, else per-frame sampling. */
        AUTO,
        /** Raw Input key events (Windows only): no polling, timestamped as Windows delivers them. */
        EVENTS,
        /** Background GetAsyncKeyState poller (Windows only); called ASYNC before 0.7.2. */
        POLLER,
        /** Per-frame Keyboard state after a fresh message pump. */
        FRAME
    }

    /** F8 overlay: OFF, SIMPLE (mode and status), DETAILED (plus input and performance diagnostics). */
    public enum Overlay { OFF, SIMPLE, DETAILED }

    public static final int MAX_STEPS_PER_TICK = 96;
    /**
     * User-facing cap. Measured (RateCapExperiment, 2026-10-10): no error growth up to 15360 Hz. Above
     * ~2 kHz a substep is shorter than the 0.5 ms poller period, so more rate buys no input resolution,
     * only cost. Environment overrides may exceed the cap for measurement.
     */
    public static final int MAX_CONFIG_RATE_HZ = 1920;
    public static final int DEFAULT_RATE_HZ = 960;
    /** 8 kHz keyboards. Measured (PollerBench): 7999 Hz achieved, but only by busy-waiting one core. */
    public static final int MAX_POLL_RATE_HZ = 8000;

    public static final String CAT_MOVEMENT = "general";
    public static final String CAT_INPUT = "input";
    public static final String CAT_DISPLAY = "display";

    /** The main switch (settings: "TACTILE movement"). OFF = vanilla movement whatever the selected mode. */
    public boolean enabled = true;
    /** The mode chosen in the advanced settings (used while enabled). */
    public Mode selectedMode = Mode.EXACT;
    /** The mode that runs: the selected mode while enabled, else VANILLA (probes and environment overrides set it directly). */
    public Mode mode = Mode.EXACT;
    /** GRID (default) = vanilla per-tick collision rules with substep motion and input; DISCRETE only for validation (HRMOVE_CONTACTS). */
    public com.tactile.core.SubstepModel.Contacts contacts = com.tactile.core.SubstepModel.Contacts.GRID;
    public int physicsRateHz = DEFAULT_RATE_HZ;
    /** STEPPED: key changes take effect at multiples of 1 / stepRateHz seconds (any rate, 1-1920). */
    public int stepRateHz = DEFAULT_STEP_RATE_HZ;
    public InputSource inputSource = InputSource.AUTO;
    public int pollRateHz = 2000;
    public boolean pressLatch = true;
    public Overlay overlay = Overlay.OFF;
    public boolean chatMessages = false;
    /** Incremented whenever settings that the running engine depends on change. */
    public volatile int generation;

    private Configuration cfg;

    public void load(File file)
    {
        cfg = new Configuration(file);
        cfg.load();
        // 0.2.0 stored the overlay as a boolean
        ConfigCategory general = cfg.getCategory(CAT_MOVEMENT);
        boolean legacyOverlay = general.containsKey("diagnosticsOverlay") && general.get("diagnosticsOverlay").getBoolean();
        if (general.containsKey("diagnosticsOverlay")) general.remove("diagnosticsOverlay");
        if (legacyOverlay && !cfg.getCategory(CAT_DISPLAY).containsKey("overlay")) overlayProp().set(Overlay.DETAILED.name());
        sync();
        applyEnvironment();
    }

    /** Reads every property (defining it, with its comment and GUI metadata, on first use) and saves if needed. */
    public void sync()
    {
        cfg.getCategory(CAT_MOVEMENT).setLanguageKey("tactile.config.movement").setComment("How your own player's movement is simulated.");
        cfg.getCategory(CAT_INPUT).setLanguageKey("tactile.config.input").setComment("How movement keys are read.");
        cfg.getCategory(CAT_DISPLAY).setLanguageKey("tactile.config.display").setComment("On-screen information.");

        enabled = enabledProp().getBoolean();
        boolean legacyHighRes = "HIGH_RES".equalsIgnoreCase(modeProp().getString());
        if (legacyHighRes)
        {
            // 0.8.1: HIGH_RES became STEPPED. Its input resolution was the physics rate: keep it as the step rate.
            if (!cfg.getCategory(CAT_MOVEMENT).containsKey("stepRateHz")) stepRateProp().set(Math.max(20, rateProp().getInt()));
            modeProp().set(Mode.STEPPED.name());
        }
        selectedMode = Mode.valueOf(modeProp().getString());
        mode = enabled ? selectedMode : Mode.VANILLA;
        int rate = rateProp().getInt();
        physicsRateHz = Math.max(20, Math.min(MAX_CONFIG_RATE_HZ, 20 * Math.round(rate / 20.0F)));
        if (physicsRateHz != rate) rateProp().set(physicsRateHz);
        stepRateHz = Math.max(MIN_STEP_RATE_HZ, Math.min(MAX_CONFIG_RATE_HZ, stepRateProp().getInt()));

        Property source = cfg.get(CAT_INPUT, "inputSource", "AUTO",
            "AUTO (recommended): key events where available (Windows), else the background poller, else once per frame.\n"
            + "EVENTS: Windows delivers each key change as it happens; no polling and almost no CPU. Timing is limited only by\n"
            + "your keyboard. Only movement keys are looked at, and only while the game has focus.\n"
            + "POLLER: checks the keys at a fixed rate (the poll rate below).\nFRAME: once per rendered frame.",
            new String[] {"AUTO", "EVENTS", "POLLER", "FRAME"}).setLanguageKey("tactile.config.inputSource");
        if ("ASYNC".equalsIgnoreCase(source.getString())) source.set("POLLER"); // renamed in 0.7.2
        inputSource = InputSource.valueOf(source.getString());
        pollRateHz = cfg.get(CAT_INPUT, "pollRateHz", 2000,
            "Only used when Key reading is POLLER (or AUTO falls back to the poller because key events are unavailable).\n"
            + "Recommended: 2000 (default). Key changes are timestamped to within 1 / rate (2000 Hz = 0.5 ms).\n"
            + "Up to 2000 Hz the poller sleeps between polls: negligible CPU (measured ~5 % of one core including the JVM).\n"
            + "Above 2000 Hz (up to " + MAX_POLL_RATE_HZ + ", for 8 kHz keyboards) Windows cannot sleep that briefly, so the poller\n"
            + "busy-waits: measured 100 % of one CPU core at both 4000 and 8000 Hz, for at most 0.4 ms finer timing.",
            250, MAX_POLL_RATE_HZ).setLanguageKey("tactile.config.pollRateHz").getInt();
        pressLatch = cfg.get(CAT_INPUT, "pressLatch", true,
            "STEPPED only: a tap that starts and ends between two steps still counts, for one step. EXACT never needs it (taps act for exactly as long as they are held).")
            .setLanguageKey("tactile.config.pressLatch").getBoolean();

        overlay = Overlay.valueOf(overlayProp().getString());
        chatMessages = cfg.get(CAT_DISPLAY, "chatMessages", false, "Show a chat message when the movement mode changes or movement falls back to vanilla.")
            .setLanguageKey("tactile.config.chatMessages").getBoolean();

        if (cfg.hasChanged()) cfg.save();
        ++generation;
    }

    private Property enabledProp()
    {
        return cfg.get(CAT_MOVEMENT, "enabled", true,
            "ON: your movement follows your keys at the exact moment you press them (EXACT, or the mode chosen in Advanced settings).\n"
            + "OFF: unmodified vanilla movement.\n"
            + "F7 cycles VANILLA / STEPPED / EXACT in game.").setLanguageKey("tactile.config.enabled");
    }

    private Property modeProp()
    {
        return cfg.get(CAT_MOVEMENT, "mode", "EXACT",
            "EXACT (recommended): every key press and release takes effect at the exact moment it happened; the physics rate does not change movement.\n"
            + "STEPPED: the same physics, but key changes take effect only in steps of the step rate below (for example 25 or 35 Hz\n"
            + "to line up a particular jump). A tap shorter than a step still counts for one step.\n"
            + "VANILLA: unmodified vanilla movement (20 Hz).\n"
            + "F7 cycles the modes in game.",
            new String[] {"EXACT", "STEPPED", "VANILLA"}).setLanguageKey("tactile.config.mode");
    }

    private Property stepRateProp()
    {
        return cfg.get(CAT_MOVEMENT, "stepRateHz", DEFAULT_STEP_RATE_HZ,
            "STEPPED only: how often key changes take effect, in steps per second (any whole number; slider 20-240, type 1-"
            + MAX_CONFIG_RATE_HZ + ").\nA key change waits for the next step: up to 1 / rate (20 Hz = 50 ms, like vanilla's tick; 35 Hz = 28.6 ms).\n"
            + "Steps count from a fixed origin, so a rhythm of presses lines up the same way every time.",
            MIN_STEP_RATE_HZ, MAX_CONFIG_RATE_HZ).setLanguageKey("tactile.config.stepRateHz");
    }

    private Property rateProp()
    {
        return cfg.get(CAT_MOVEMENT, "physicsRateHz", DEFAULT_RATE_HZ,
            "Internal evaluation rate (multiple of 20; slider up to 960, type up to " + MAX_CONFIG_RATE_HZ + "). It does not change how you move.\n"
            + "Motion inside a tick is integrated in closed form, and contacts (ground, walls, landings) are committed on vanilla's\n"
            + "20 Hz tick grid, so positions are identical at every rate (measured: within 1e-11 blocks from 20 to 960 Hz).\n"
            + "The rate only sets how often the engine re-evaluates the collision box and the drawn path inside a tick,\n"
            + "at a few microseconds of CPU per step (measured 2-8 us). Key timing is set by the mode (EXACT, or STEPPED's step rate), not by this.\n"
            + "Default 960; lower it only to save CPU.",
            20, MAX_CONFIG_RATE_HZ).setLanguageKey("tactile.config.physicsRateHz");
    }

    private Property overlayProp()
    {
        return cfg.get(CAT_DISPLAY, "overlay", "OFF",
            "F8 overlay (F8 cycles it in game).\nSIMPLE: mode, rate and status.\nDETAILED: plus input and performance diagnostics.",
            new String[] {"OFF", "SIMPLE", "DETAILED"}).setLanguageKey("tactile.config.overlay");
    }

    public Configuration configuration()
    {
        return cfg;
    }

    /** Writes the values changed in game (F7, F8) back to the file. F7's VANILLA is the main switch turned off. */
    public void save()
    {
        if (cfg == null) return;
        enabled = mode != Mode.VANILLA;
        enabledProp().set(enabled);
        if (enabled)
        {
            selectedMode = mode;
            modeProp().set(mode.name());
        }
        rateProp().set(physicsRateHz);
        stepRateProp().set(stepRateHz);
        overlayProp().set(overlay.name());
        cfg.save();
    }

    private void applyEnvironment()
    {
        String m = System.getenv("HRMOVE_MODE");
        boolean legacy = m != null && m.trim().equalsIgnoreCase("HIGH_RES");
        if (m != null && !m.trim().isEmpty()) mode = legacy ? Mode.STEPPED : Mode.valueOf(m.trim().toUpperCase());
        else if ("1".equals(System.getenv("HRMOVE_BASELINE"))) mode = Mode.VANILLA; // a baseline recording is vanilla unless stated
        String r = System.getenv("HRMOVE_RATE");
        if (r != null && !r.trim().isEmpty()) physicsRateHz = Integer.parseInt(r.trim());
        if (legacy) stepRateHz = physicsRateHz; // HRMOVE_MODE=HIGH_RES: input resolution = physics rate, as before
        String step = System.getenv("HRMOVE_STEP");
        if (step != null && !step.trim().isEmpty()) stepRateHz = Integer.parseInt(step.trim());
        String c = System.getenv("HRMOVE_CONTACTS");
        if (c != null && !c.trim().isEmpty()) contacts = com.tactile.core.SubstepModel.Contacts.valueOf(c.trim().toUpperCase());
        String i = System.getenv("HRMOVE_INPUT");
        if (i != null && !i.trim().isEmpty() && !i.trim().equalsIgnoreCase("SCRIPTED"))
            inputSource = i.trim().equalsIgnoreCase("ASYNC") ? InputSource.POLLER : InputSource.valueOf(i.trim().toUpperCase());
    }

    /**
     * Test probes: run {@code m} at {@code rateHz} (the step rate for STEPPED, physics at the default; the physics rate
     * otherwise) from the next tick. Not saved.
     */
    public void applyForTest(Mode m, int rateHz)
    {
        mode = m;
        if (m == Mode.STEPPED)
        {
            stepRateHz = rateHz;
            physicsRateHz = DEFAULT_RATE_HZ;
        }
        else physicsRateHz = rateHz;
        ++generation;
    }

    public int stepsPerTick()
    {
        return Math.max(1, Math.min(MAX_STEPS_PER_TICK, Math.round(physicsRateHz / 20.0F)));
    }
}
