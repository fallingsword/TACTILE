package com.tactile.harness;

import java.util.Locale;

import com.tactile.core.Box;
import com.tactile.core.Course;
import com.tactile.core.InputTimeline;
import com.tactile.core.PlayerState;
import com.tactile.core.SubstepModel;
import com.tactile.core.TickInput;
import com.tactile.core.VanillaModel;

/**
 * Edge-landing parity (gradlew edgeLandingTest).
 * <p>Part 1: held forward+sprint+jump toward a platform end from 50 offsets; jumps made before falling.
 * <p>Part 2: a sprint-jump from a fixed spot lands near the platform end (offsets vary the landing point).
 * Space is then pressed once, at a time relative to touchdown (-2 .. +3 ticks), and held for 80 ms
 * (a typical human tap). Does the second jump happen? Vanilla sees the press only if it is down at a
 * tick start; high-res sees it at its real time.
 */
public final class EdgeLandingTest
{
    public static void main(String[] args)
    {
        Course c = new Course().fill(-3, 99, -3, 3, 99, 20, Course.Shape.FULL); // edge at z = 21
        int[] rates = {12, 48, -12}; // negative = EXACT input timing

        // Part 1
        int[] mismatch = new int[rates.length];
        for (int i = 0; i < 50; ++i)
        {
            double z0 = 0.5D + i * 0.02D;
            int v = heldChain(c, z0, 0);
            for (int r = 0; r < rates.length; ++r) if (heldChain(c, z0, rates[r]) != v) ++mismatch[r];
        }
        System.out.println("Part 1, held W+sprint+jump toward the platform end, 50 offsets:");
        for (int r = 0; r < rates.length; ++r) System.out.println(String.format(Locale.ROOT, "  %s: jump count differs from vanilla at %d/50", label(rates[r]), mismatch[r]));

        // Part 2
        System.out.println("Part 2, landing near the edge, then one 80 ms space press at (touchdown + delta). Cells: second jump? vanilla/240/960");
        System.out.println("  edge distance at touchdown: delta (ticks) ->");
        StringBuilder head = new StringBuilder("  start z   ");
        double[] deltas = {-1.5, -0.75, -0.25, 0.0, 0.25, 0.5, 0.75, 1.0, 1.5};
        for (double d : deltas) head.append(String.format(Locale.ROOT, " %7.2f", d));
        System.out.println(head);
        int[] lost = new int[rates.length], extra = new int[rates.length];
        for (int i = 0; i < 25; ++i)
        {
            double z0 = 17.30D + i * 0.012D; // takeoff spot; landing lands across the last ~0.3 blocks before the edge
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  %.3f   ", z0));
            for (double d : deltas)
            {
                boolean v = secondJump(c, z0, d, 0);
                StringBuilder cell = new StringBuilder(v ? "Y" : "-");
                for (int r = 0; r < rates.length; ++r)
                {
                    boolean hr = secondJump(c, z0, d, rates[r]);
                    cell.append(hr ? "Y" : "-");
                    if (v && !hr) ++lost[r];
                    if (!v && hr) ++extra[r];
                }
                line.append(String.format(Locale.ROOT, " %7s", cell));
            }
            System.out.println(line);
        }
        for (int r = 0; r < rates.length; ++r)
            System.out.println(String.format(Locale.ROOT, "  %s: second jump lost where vanilla jumps: %d; jumps where vanilla does not: %d (of %d)",
                label(rates[r]), lost[r], extra[r], 25 * deltas.length));
    }

    static String label(int n)
    {
        return (n < 0 ? "EXACT " : "HIGH_RES ") + 20 * Math.abs(n) + " Hz";
    }

    static PlayerState rest(double z0)
    {
        PlayerState s = new PlayerState();
        s.box = Box.forPlayerAt(0.5D, 100.0D, z0);
        s.onGround = true;
        s.motionY = -0.0784000015258789D;
        return s;
    }

    static int heldChain(Course c, double z0, int n)
    {
        PlayerState s = rest(z0);
        SubstepModel m = n == 0 ? null : new SubstepModel(Math.abs(n), SubstepModel.Contacts.GRID, n < 0);
        TickInput in = TickInput.fromKeyString("W---J-R", 0.0F, 0.0F);
        int jumps = 0;
        for (int t = 0; t < 400 && s.posY() > 98.0D; ++t)
        {
            if (m == null)
            {
                VanillaModel.tick(s, in, c);
                if (s.jumpTicks == 10) ++jumps;
            }
            else
            {
                int before = m.jumpImpulses;
                m.tick(s, in, c);
                jumps += m.jumpImpulses - before;
            }
        }
        return jumps;
    }

    /**
     * Stand at z0 (still), sprint-jump at tick 2 holding W+sprint (jump released at tick 4), then press
     * space at (touchdown + delta) for 80 ms. Touchdown time is taken from a vanilla dry run (12 ticks
     * after takeoff on flat ground). Returns whether a second jump happened before falling.
     */
    static boolean secondJump(Course c, double z0, double delta, int n)
    {
        double takeoff = 2.0D;
        double touchdown = takeoff + 11.25D; // physical contact time on flat ground (vanilla notices at +12)
        double press = touchdown + delta, release = press + 80.0D / 50.0D;
        InputTimeline tl = new InputTimeline()
            .add(0.0D, TickInput.fromKeyString("W-----R", 0.0F, 0.0F))
            .add(takeoff, TickInput.fromKeyString("W---J-R", 0.0F, 0.0F))
            .add(takeoff + 2.0D, TickInput.fromKeyString("W-----R", 0.0F, 0.0F))
            .add(press, TickInput.fromKeyString("W---J-R", 0.0F, 0.0F))
            .add(release, TickInput.fromKeyString("W-----R", 0.0F, 0.0F));
        PlayerState s = rest(z0);
        // start with full sprint speed so the first jump is a real sprint-jump
        s.setSprinting(true);
        s.motionZ = 0.2806D * 0.546D;
        s.jumpMovementFactor = 0.026F;
        int jumps = 0;
        SubstepModel m = n == 0 ? null : new SubstepModel(Math.abs(n), SubstepModel.Contacts.GRID, n < 0);
        for (long t = 0; t < 60 && s.posY() > 98.0D; ++t)
        {
            if (m == null)
            {
                TickInput in = tl.at((double)t); // vanilla: KeyBinding state at the tick start
                VanillaModel.tick(s, in, c);
                if (s.jumpTicks == 10) ++jumps;
            }
            else
            {
                int before = m.jumpImpulses;
                if (m.exact) m.tickExact(s, tl, t, c);
                else m.tick(s, tl, t, c);
                jumps += m.jumpImpulses - before;
            }
        }
        return jumps >= 2;
    }
}
