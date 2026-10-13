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
 * Jump timing windows (gradlew jumpWindowTest): how long is the span of moments at which pressing space
 * still makes a flat sprint jump across a gap?
 * <p>The player sprints from rest toward the end of a platform (edge at z = 21) and taps space once at
 * time p (80 ms). Success = the first landing after takeoff is on the far platform. p is swept in
 * 1 ms steps. Vanilla sees the press at the next tick start; HIGH_RES at the next substep start; EXACT at
 * its own time. The window is the total length of the successful press times.
 * <p>In vanilla the window depends on where the ticks happen to fall relative to the edge (the run-up
 * phase), so the run is repeated from start spots spread over one tick of sprint distance.
 */
public final class JumpWindowTest
{
    static final int EDGE = 21;
    static final int PHASES = 20;
    static final double DP = 0.02D; // 1 ms
    /** Space is tapped for 80 ms (a typical human tap; always spans a tick start, so vanilla sees it too). */
    static final double TAP = 1.6D;
    static final TickInput RUN = TickInput.fromKeyString("W-----R", 0.0F, 0.0F);
    static final TickInput JUMP = TickInput.fromKeyString("W---J-R", 0.0F, 0.0F);

    public static void main(String[] args)
    {
        int[] modes = {0, 3, 12, 48, -1, -3, -12, -48}; // 0 = vanilla, n > 0 = HIGH_RES 20n Hz, n < 0 = EXACT 20|n| Hz
        for (int gap : new int[] {3, 2})
        {
            Course c = new Course()
                .fill(-3, 99, -3, 3, 99, EDGE - 1, Course.Shape.FULL)
                .fill(-3, 99, EDGE + gap, 3, 99, EDGE + gap + 8, Course.Shape.FULL);
            System.out.println(String.format(Locale.ROOT, "== %d-block jump (%d-block gap), flat, sprint run-up from rest, space tapped (80 ms) at time p", gap + 1, gap));

            // Sweep range from a vanilla dry run: the tick the player walks off without jumping.
            double z0Base = 5.0D;
            int walkOff = walkOffTick(c, z0Base);
            int from = walkOff - 10, to = walkOff + 3;
            int presses = (int)Math.round((to - from) / DP);

            double[][] lo = new double[modes.length][PHASES], hi = new double[modes.length][PHASES], len = new double[modes.length][PHASES];
            for (int k = 0; k < PHASES; ++k)
            {
                double z0 = z0Base + 0.2806D * k / PHASES; // one tick of sprint distance
                for (int m = 0; m < modes.length; ++m)
                {
                    boolean[] ok = sweep(c, gap, z0, modes[m], from, presses);
                    double first = Double.NaN, last = Double.NaN;
                    int count = 0;
                    for (int i = 0; i < presses; ++i)
                    {
                        if (!ok[i]) continue;
                        double p = from + i * DP;
                        if (count == 0) first = p;
                        last = p;
                        ++count;
                    }
                    lo[m][k] = first;
                    hi[m][k] = last;
                    len[m][k] = count * DP * 50.0D;
                }
            }

            System.out.println("  per start spot: window ms (latest successful press minus vanilla's, ms)");
            StringBuilder head = new StringBuilder("  start z  ");
            for (int mode : modes) head.append(String.format(Locale.ROOT, "%17s", label(mode)));
            System.out.println(head);
            for (int k = 0; k < PHASES; ++k)
            {
                StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  %.4f ", z0Base + 0.2806D * k / PHASES));
                for (int m = 0; m < modes.length; ++m)
                    line.append(String.format(Locale.ROOT, "  %6.0f (%+5.0f)", len[m][k], (hi[m][k] - hi[0][k]) * 50.0D));
                System.out.println(line);
            }
            System.out.println("  summary over start spots: window ms mean / min / max; latest press vs vanilla ms mean; earliest press vs vanilla ms mean");
            for (int m = 0; m < modes.length; ++m)
            {
                double sum = 0, min = Double.MAX_VALUE, max = 0, dHi = 0, dLo = 0;
                for (int k = 0; k < PHASES; ++k)
                {
                    sum += len[m][k];
                    min = Math.min(min, len[m][k]);
                    max = Math.max(max, len[m][k]);
                    dHi += (hi[m][k] - hi[0][k]) * 50.0D;
                    dLo += (lo[m][k] - lo[0][k]) * 50.0D;
                }
                System.out.println(String.format(Locale.ROOT, "  %-16s %6.1f / %5.1f / %5.1f    latest %+6.1f    earliest %+6.1f",
                    label(modes[m]), sum / PHASES, min, max, dHi / PHASES, dLo / PHASES));
            }
            System.out.println();
        }
    }

    static String label(int n)
    {
        if (n == 0) return "VANILLA";
        return (n < 0 ? "EXACT " : "HIGH_RES ") + 20 * Math.abs(n);
    }

    static PlayerState rest(double z0)
    {
        PlayerState s = new PlayerState();
        s.box = Box.forPlayerAt(0.5D, 100.0D, z0);
        s.onGround = true;
        s.motionY = -0.0784000015258789D;
        return s;
    }

    static int walkOffTick(Course c, double z0)
    {
        PlayerState s = rest(z0);
        for (int t = 0; t < 400; ++t)
        {
            VanillaModel.tick(s, RUN, c);
            if (!s.onGround) return t;
        }
        throw new IllegalStateException("never walked off");
    }

    /** Success flags for presses at from + i * DP. The run-up up to tick {@code from} is shared. */
    static boolean[] sweep(Course c, int gap, double z0, int n, int from, int presses)
    {
        SubstepModel m = n == 0 ? null : new SubstepModel(Math.abs(n), SubstepModel.Contacts.GRID, n < 0);
        InputTimeline runOnly = new InputTimeline().add(0.0D, RUN);
        PlayerState s = rest(z0);
        for (long t = 0; t < from; ++t) step(s, m, runOnly, t, c);
        PlayerState start = s.copy();
        SubstepModel.Saved saved = m == null ? null : m.save();

        boolean[] ok = new boolean[presses];
        for (int i = 0; i < presses; ++i)
        {
            double p = from + i * DP;
            InputTimeline tl = new InputTimeline().add(0.0D, RUN).add(p, JUMP).add(p + TAP, RUN);
            s.set(start);
            if (m != null) m.restore(saved);
            int jumpsBefore = m == null ? 0 : m.jumpImpulses;
            boolean tookOff = false, airborne = false;
            for (long t = from; t < from + 60 && s.posY() > 98.0D; ++t)
            {
                step(s, m, tl, t, c);
                if (!tookOff) tookOff = m == null ? s.jumpTicks == 10 : m.jumpImpulses > jumpsBefore;
                if (tookOff && !s.onGround) airborne = true;
                // Back at platform height: after a mid-tick jump, GRID commits onGround at the re-phased grid
                // end, so by the next tick boundary the held space may already have started the next jump.
                if (airborne && (s.onGround || s.posY() <= 100.0D + 1.0E-9D))
                {
                    // first landing after takeoff: on the far platform, not back on the near one
                    ok[i] = Math.abs(s.posY() - 100.0D) < 1.0E-6D && s.box.maxZ > EDGE + gap && s.box.minZ > EDGE;
                    break;
                }
            }
        }
        return ok;
    }

    static void step(PlayerState s, SubstepModel m, InputTimeline tl, long t, Course c)
    {
        if (m == null) VanillaModel.tick(s, tl.at((double)t), c); // vanilla: key state at the tick start
        else if (m.exact) m.tickExact(s, tl, t, c);
        else m.tick(s, tl, t, c);
    }
}
