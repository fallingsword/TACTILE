package com.tactile.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import com.tactile.core.Box;
import com.tactile.core.Course;
import com.tactile.core.InputTimeline;
import com.tactile.core.PlayerState;
import com.tactile.core.SubstepModel;
import com.tactile.core.TickInput;
import com.tactile.core.VanillaModel;

/**
 * Windows from rest (gradlew inputPhaseTest): since 1.2.0 a movement-key change while standing still starts a collision
 * window (SubstepModel.restPhase). Does that make the coyote time at an edge consistent, without letting jumps fire sooner?
 * <p>Compares VANILLA (each input acts at the next tick start), EXACT 1.1 (restPhase off) and EXACT 1.2, EXACT at 960 Hz.
 * "Overhang" is how far the player's box is past the edge (box fully off the block) at the latest jump that still works;
 * "coyote" is the time from the box leaving the block to that jump.
 * <ol>
 * <li>Standing start: sprint from rest toward an edge, the run started at a sub-tick offset d. Spread over d.</li>
 * <li>Momentum: jump in place, press sprint-forward in mid-air at a sub-tick offset d, land, run to the edge.</li>
 * <li>Jump rhythm: the earliest second jump after a landing, with and without a key tap around the touchdown.</li>
 * <li>Edge tap: the largest overhang a 1 ms tap of A before the edge can buy.</li>
 * </ol>
 */
public final class InputPhaseTest
{
    static final int VAN = 0, EXACT = 1, NOW = 2, METHODS = 3;
    static final String[] NAMES = {"VANILLA", "EXACT 1.1", "EXACT 1.2"};
    static final double EDGE = 21.0D;
    static final double DP = 0.02D; // 1 ms
    static final double FOREVER = 1.0E9D;

    /** Keys held over time intervals. */
    static final class Script
    {
        final List<Object[]> holds = new ArrayList<Object[]>();

        Script hold(char key, double from, double to)
        {
            holds.add(new Object[] {key, from, to});
            return this;
        }

        Script plus(char key, double from, double to)
        {
            Script s = new Script();
            s.holds.addAll(holds);
            return s.hold(key, from, to);
        }

        TickInput at(double t)
        {
            char[] k = "-------".toCharArray();
            String slots = "WASDJNR";
            for (Object[] h : holds)
                if ((Double)h[1] <= t && t < (Double)h[2]) k[slots.indexOf((Character)h[0])] = (Character)h[0];
            return TickInput.fromKeyString(new String(k), 0.0F, 0.0F);
        }

        InputTimeline timeline()
        {
            TreeSet<Double> ts = new TreeSet<Double>();
            ts.add(0.0D);
            for (Object[] h : holds)
            {
                ts.add((Double)h[1]);
                if ((Double)h[2] < FOREVER) ts.add((Double)h[2]);
            }
            InputTimeline tl = new InputTimeline();
            for (double t : ts) tl.add(t, at(t));
            return tl;
        }
    }

    static PlayerState rest(double z0)
    {
        PlayerState s = new PlayerState();
        s.box = Box.forPlayerAt(0.5D, 100.0D, z0);
        s.onGround = true;
        s.motionY = -0.0784000015258789D;
        return s;
    }

    static SubstepModel model(int method)
    {
        if (method == VAN) return null;
        SubstepModel m = new SubstepModel(48, SubstepModel.Contacts.GRID, true);
        m.restPhase = method == NOW;
        return m;
    }

    static void step(PlayerState s, SubstepModel m, InputTimeline tl, long t, Course c)
    {
        if (m == null) VanillaModel.tick(s, tl.at((double)t), c);
        else m.tickExact(s, tl, t, c);
    }

    /** Time of a jump impulse during tick t, or NaN. */
    static double stepDetect(PlayerState s, SubstepModel m, InputTimeline tl, long t, Course c)
    {
        int before = m == null ? 0 : m.jumpImpulses;
        step(s, m, tl, t, c);
        if (m == null) return s.jumpTicks == 10.0D ? (double)t : Double.NaN;
        return m.jumpImpulses > before ? t + m.lastJumpPhase : Double.NaN;
    }

    /** Result of the latest-jump search for one script: overhang (blocks) and coyote (ms); NaN if not found. */
    static final class Edge
    {
        double overhang = Double.NaN, coyote = Double.NaN, cross = Double.NaN;
    }

    /**
     * The latest jump press (1 ms steps, held 80 ms) that still makes a jump impulse within one tick of the press, and where
     * the player is at that impulse, from the run without the extra press.
     */
    static Edge latestJump(Course c, double z0, Script base, int method)
    {
        Edge e = new Edge();
        // the run without the extra press: z at every tick start, and when the box leaves the block
        InputTimeline tl0 = base.timeline();
        SubstepModel m0 = model(method);
        PlayerState s0 = rest(z0);
        int n = 80;
        double[] z = new double[n + 1];
        z[0] = s0.posZ();
        for (int t = 0; t < n; ++t)
        {
            step(s0, m0, tl0, t, c);
            z[t + 1] = s0.posZ();
        }
        double off = EDGE + 0.3D;
        for (int k = 1; k <= n; ++k)
            if (z[k] >= off)
            {
                e.cross = k - 1 + (off - z[k - 1]) / (z[k] - z[k - 1]);
                break;
            }
        if (Double.isNaN(e.cross)) return e;

        long from = (long)Math.floor(e.cross - 1.5D) - 1;
        SubstepModel m = model(method);
        PlayerState s = rest(z0);
        for (long t = 0; t < from; ++t) step(s, m, tl0, t, c);
        PlayerState start = s.copy();
        SubstepModel.Saved saved = m == null ? null : m.save();

        for (double p = e.cross + 1.5D; p >= e.cross - 1.5D; p -= DP)
        {
            InputTimeline tl = base.plus('J', p, p + 1.6D).timeline();
            s.set(start);
            if (m != null) m.restore(saved);
            double ti = Double.NaN;
            for (long t = from; t <= (long)Math.ceil(p) + 1 && s.posY() > 99.0D; ++t)
            {
                double d = stepDetect(s, m, tl, t, c);
                if (!Double.isNaN(d) && d >= p - 1.0E-6D && d <= p + 1.0D + 1.0E-6D)
                {
                    ti = d;
                    break;
                }
            }
            if (Double.isNaN(ti)) continue;
            int k = Math.min(n - 1, (int)Math.floor(ti));
            double zt = z[k] + (z[k + 1] - z[k]) * (ti - k);
            e.overhang = zt - off;
            e.coyote = (ti - e.cross) * 50.0D;
            return e;
        }
        return e;
    }

    public static void main(String[] args)
    {
        Course c = new Course().fill(-3, 99, -3, 3, 99, (int)EDGE - 1, Course.Shape.FULL); // void past the edge
        Course flat = new Course().fill(-3, 99, -3, 3, 99, 200, Course.Shape.FULL);

        standingStart(c);
        momentum(c);
        rhythm(flat);
        edgeTap(c);
    }

    static void report(String title, double[][][] ov, double[][][] cy, double[] spots)
    {
        System.out.println(title);
        System.out.println("  per start spot: overhang at the latest working jump, cm, min..max over the offsets (coyote ms min..max)");
        StringBuilder head = new StringBuilder("  start z ");
        for (String nm : NAMES) head.append(String.format(Locale.ROOT, "%34s", nm));
        System.out.println(head);
        for (int k = 0; k < spots.length; ++k)
        {
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  %7.4f", spots[k]));
            for (int m = 0; m < METHODS; ++m)
            {
                double[] r = range(ov[m][k]), q = range(cy[m][k]);
                line.append(String.format(Locale.ROOT, "  %6.2f..%6.2f (%5.1f..%5.1f)", 100 * r[0], 100 * r[1], q[0], q[1]));
            }
            System.out.println(line);
        }
        System.out.println("  summary: spread over the offsets at one start spot (max - min), cm: mean / largest; jumps that flip > 5 cm between 1-ms-adjacent offsets");
        for (int m = 0; m < METHODS; ++m)
        {
            double sum = 0, big = 0;
            int flips = 0, missing = 0;
            for (int k = 0; k < spots.length; ++k)
            {
                double[] r = range(ov[m][k]);
                sum += r[1] - r[0];
                big = Math.max(big, r[1] - r[0]);
                for (int i = 0; i < ov[m][k].length; ++i)
                {
                    if (Double.isNaN(ov[m][k][i])) ++missing;
                    if (i > 0 && Math.abs(ov[m][k][i] - ov[m][k][i - 1]) > 0.05D) ++flips;
                }
            }
            System.out.println(String.format(Locale.ROOT, "  %-12s %6.2f / %6.2f    flips %d%s", NAMES[m], 100 * sum / spots.length, 100 * big, flips,
                missing > 0 ? "   (no jump found: " + missing + ")" : ""));
        }
        System.out.println();
    }

    static double[] range(double[] a)
    {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double v : a)
        {
            if (Double.isNaN(v)) continue;
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return new double[] {lo, hi};
    }

    /** 1. Sprint from rest, started at tick 2 + d (d in 1 ms steps over a tick), from 10 start spots over one tick of sprint. */
    static void standingStart(Course c)
    {
        int spotsN = 10, offN = 50;
        double[] spots = new double[spotsN];
        double[][][] ov = new double[METHODS][spotsN][offN], cy = new double[METHODS][spotsN][offN];
        for (int k = 0; k < spotsN; ++k)
        {
            spots[k] = 14.0D + 0.2806D * k / spotsN;
            for (int i = 0; i < offN; ++i)
            {
                double d = 2.0D + i * DP;
                Script sc = new Script().hold('W', d, FOREVER).hold('R', d, FOREVER);
                for (int m = 0; m < METHODS; ++m)
                {
                    Edge e = latestJump(c, spots[k], sc, m);
                    ov[m][k][i] = e.overhang;
                    cy[m][k][i] = e.coyote;
                }
            }
        }
        report("== 1. Standing start: sprint-forward pressed at tick 2 + d, d = 0..49 ms; edge ahead, nothing past it", ov, cy, spots);
    }

    /** 2. Jump in place at tick 2, sprint-forward pressed at 5 + d in mid-air (d = 1..49 ms), land, run to the edge. */
    static void momentum(Course c)
    {
        int spotsN = 10, offN = 49;
        double[] spots = new double[spotsN];
        double[][][] ov = new double[METHODS][spotsN][offN], cy = new double[METHODS][spotsN][offN];
        for (int k = 0; k < spotsN; ++k)
        {
            spots[k] = 14.0D + 0.2806D * k / spotsN;
            for (int i = 0; i < offN; ++i)
            {
                double d = 5.0D + (i + 1) * DP;
                Script sc = new Script().hold('J', 2.0D, 3.6D).hold('W', d, FOREVER).hold('R', d, FOREVER);
                for (int m = 0; m < METHODS; ++m)
                {
                    Edge e = latestJump(c, spots[k], sc, m);
                    ov[m][k][i] = e.overhang;
                    cy[m][k][i] = e.coyote;
                }
            }
        }
        report("== 2. Momentum: jump in place at tick 2, sprint-forward pressed in mid-air at tick 5 + d, d = 1..49 ms; land, run to the edge", ov, cy, spots);
    }

    /**
     * 3. Earliest second jump after a landing (sprinting on flat ground, first jump at tick 2), for a fresh press and for space
     * held throughout; optionally with a 1 ms tap of A at q around the touchdown. Never earlier than EXACT without the tap.
     */
    static void rhythm(Course flat)
    {
        System.out.println("== 3. Jump rhythm: earliest second jump (ticks after the first), sprinting on flat ground");
        for (boolean held : new boolean[] {false, true})
        {
            double[] best = new double[METHODS];
            for (int m = 0; m < METHODS; ++m)
            {
                Script base = new Script().hold('W', 0.0D, FOREVER).hold('R', 0.0D, FOREVER);
                best[m] = earliestSecond(flat, base, m, held);
            }
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  %-28s", held ? "space held (bunny hop):" : "fresh press after landing:"));
            for (int m = 0; m < METHODS; ++m) line.append(String.format(Locale.ROOT, "  %s %.3f", NAMES[m], best[m] - 2.0D));
            System.out.println(line);
            // with a 1 ms tap of A at q, q from tick 10 to 16 in 1 ms steps
            for (int m = NOW; m <= NOW; ++m)
            {
                double minWithTap = Double.MAX_VALUE, worstGain = 0.0D;
                for (double q = 10.0D; q <= 16.0D; q += DP)
                {
                    Script base = new Script().hold('W', 0.0D, FOREVER).hold('R', 0.0D, FOREVER).hold('A', q, q + DP);
                    double e = earliestSecond(flat, base, m, held);
                    minWithTap = Math.min(minWithTap, e);
                    worstGain = Math.max(worstGain, earliestSecond(flat, base, EXACT, held) - e);
                }
                System.out.println(String.format(Locale.ROOT, "      %s with a 1 ms A tap anywhere in ticks 10-16: earliest %.3f; largest gain over EXACT with the same tap: %.3f ms",
                    NAMES[m], minWithTap - 2.0D, 50.0D * worstGain));
            }
        }
        System.out.println();
    }

    /** Time of the second jump impulse: held = space held from tick 2 on; otherwise released at 3.6 and pressed again at p (1 ms steps). */
    static double earliestSecond(Course c, Script base, int method, boolean held)
    {
        if (held)
        {
            Script sc = base.plus('J', 2.0D, FOREVER);
            return nthJump(c, sc, method, 2, 30);
        }
        double best = Double.MAX_VALUE;
        Script first = base.plus('J', 2.0D, 3.6D);
        for (double p = 10.0D; p <= 18.0D; p += DP)
        {
            double t = nthJump(c, first.plus('J', p, p + 1.6D), method, 2, (int)Math.ceil(p) + 2);
            if (!Double.isNaN(t)) best = Math.min(best, t);
        }
        return best;
    }

    static double nthJump(Course c, Script sc, int method, int nth, int ticks)
    {
        InputTimeline tl = sc.timeline();
        SubstepModel m = model(method);
        PlayerState s = rest(5.0D);
        int count = 0;
        for (long t = 0; t < ticks; ++t)
        {
            double d = stepDetect(s, m, tl, t, c);
            if (!Double.isNaN(d) && ++count == nth) return d;
        }
        return Double.NaN;
    }

    /** 4. Standing start at tick 2; the best a 1 ms tap of A at any moment in the last 60 ms before the edge can do. */
    static void edgeTap(Course c)
    {
        System.out.println("== 4. Edge tap: sprint from rest at tick 2; overhang (cm) at the latest working jump, without a tap and with the best 1 ms tap of A");
        System.out.println("  start z    VANILLA  EXACT 1.1  EXACT 1.2 |  with the best tap: EXACT 1.1  EXACT 1.2");
        int spotsN = 10;
        double vanMax = 0, exactTapGain = 0, nowTapGain = 0;
        for (int k = 0; k < spotsN; ++k)
        {
            double z0 = 14.0D + 0.2806D * k / spotsN;
            Script sc = new Script().hold('W', 2.0D, FOREVER).hold('R', 2.0D, FOREVER);
            double[] none = new double[METHODS];
            for (int m = 0; m < METHODS; ++m) none[m] = latestJump(c, z0, sc, m).overhang;
            double cross = latestJump(c, z0, sc, EXACT).cross;
            double[] tap = {Double.NaN, -1, -1};
            for (int m = 1; m < METHODS; ++m)
                for (double q = cross - 1.2D; q <= cross + 0.1D; q += DP)
                {
                    double o = latestJump(c, z0, sc.plus('A', q, q + DP), m).overhang;
                    if (!Double.isNaN(o)) tap[m] = Math.max(tap[m], o);
                }
            vanMax = Math.max(vanMax, none[VAN]);
            exactTapGain = Math.max(exactTapGain, tap[EXACT] - none[EXACT]);
            nowTapGain = Math.max(nowTapGain, tap[NOW] - none[NOW]);
            System.out.println(String.format(Locale.ROOT, "  %7.4f  %8.2f  %9.2f  %9.2f |                     %9.2f  %9.2f", z0, 100 * none[VAN], 100 * none[EXACT],
                100 * none[NOW], 100 * tap[EXACT], 100 * tap[NOW]));
        }
        System.out.println(String.format(Locale.ROOT, "  largest vanilla overhang over these spots: %.2f cm; a tap's largest gain: EXACT 1.1 %.2f cm, EXACT 1.2 %.2f cm",
            100 * vanMax, 100 * exactTapGain, 100 * nowTapGain));
        System.out.println();
    }
}
