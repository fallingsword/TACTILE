package com.tactile.harness;

import java.util.Locale;
import java.util.Random;

import com.tactile.core.Box;
import com.tactile.core.Course;
import com.tactile.core.InputTimeline;
import com.tactile.core.PlayerState;
import com.tactile.core.SubstepModel;
import com.tactile.core.TickInput;

/**
 * EXACT input timing vs per-substep sampling (gradlew exactTimingTest).
 *
 * <p>Random input timelines (key presses and releases at arbitrary times, not on any substep grid) on a
 * course with a step, a gap and platform edges. Each run is simulated with:
 * <ul>
 * <li>SUBSTEP: the current HIGH_RES mode, input sampled at substep starts (press latch);</li>
 * <li>EXACT: substeps split at every input timestamp.</li>
 * </ul>
 * Reference: SUBSTEP at 15360 Hz, which approaches exact-time input from below (65 µs grid).
 * Reported: position error at tick boundaries (blocks), and jump-count differences.
 */
public final class ExactTimingTest
{
    private static final int RUNS = 300, TICKS = 80;
    private static final boolean NO_SNEAK = System.getenv("HRMOVE_NOSNEAK") != null;

    public static void main(String[] args)
    {
        Course c = new Course()
            .fill(-20, 99, -20, 20, 99, 19, Course.Shape.FULL)
            .fill(-20, 100, 8, 20, 100, 9, Course.Shape.FULL)      // a one-block step
            .fill(-20, 99, 22, 20, 99, 40, Course.Shape.FULL);     // a 2-block gap at z 20..21
        int ref = 768;
        int[] rates = {12, 48, 1, 3};
        String[] keys = {"W", "A", "S", "D", "J", "N", "R"};

        double[][] errS = new double[rates.length][2], errE = new double[rates.length][2];
        int[] jumpS = new int[rates.length], jumpE = new int[rates.length];
        double exactSpread = 0.0D;
        Random rnd = new Random(7L);
        for (int run = 0; run < RUNS; ++run)
        {
            InputTimeline tl = randomTimeline(rnd);
            PlayerState start = new PlayerState();
            start.box = Box.forPlayerAt(0.5D + rnd.nextDouble(), 100.0D, rnd.nextDouble() * 6.0D);
            start.onGround = true;
            start.motionY = -0.0784000015258789D;

            double[][] reference = simulate(c, start, tl, ref, false, null);
            int refJumps = lastJumps;
            double[][] exactRef = null;
            for (int r = 0; r < rates.length; ++r)
            {
                double[][] s = simulate(c, start, tl, rates[r], false, null);
                accumulate(errS[r], reference, s);
                if (lastJumps != refJumps) ++jumpS[r];
                double[][] e = simulate(c, start, tl, rates[r], true, null);
                accumulate(errE[r], reference, e);
                if (lastJumps != refJumps) ++jumpE[r];
                if (exactRef == null) exactRef = e;
                else if (maxDiff(exactRef, e) > exactSpread)
                {
                    exactSpread = maxDiff(exactRef, e);
                    worst = describe(tl, exactRef, e);
                    worstTl = tl;
                    worstStart = start;
                }
            }
        }
        System.out.println(String.format(Locale.ROOT, "%d random input timelines x %d ticks; reference: SUBSTEP at %d Hz", RUNS, TICKS, 20 * ref));
        System.out.println("  mode     rate     mean err   max err   runs with a different jump count");
        for (int r = 0; r < rates.length; ++r)
        {
            System.out.println(String.format(Locale.ROOT, "  SUBSTEP  %4d Hz  %.6f   %.6f  %d/%d", 20 * rates[r], errS[r][0] / (RUNS * TICKS), errS[r][1], jumpS[r], RUNS));
            System.out.println(String.format(Locale.ROOT, "  EXACT    %4d Hz  %.6f   %.6f  %d/%d", 20 * rates[r], errE[r][0] / (RUNS * TICKS), errE[r][1], jumpE[r], RUNS));
        }
        System.out.println(String.format(Locale.ROOT, "  EXACT: largest difference between any rate and 240 Hz: %.2e blocks", exactSpread));
        String dump = System.getenv("HRMOVE_DUMP");
        if (dump != null)
        {
            System.out.println(worst);
            int at = Integer.parseInt(dump.trim()); // tick to trace substep by substep
            for (int n : rates) trace(c, worstStart, worstTl, n, at);
        }
    }

    private static String worst = "";
    private static InputTimeline worstTl;
    private static PlayerState worstStart;

    static void trace(Course c, PlayerState start, InputTimeline tl, int n, int at)
    {
        PlayerState s = start.copy();
        SubstepModel m = new SubstepModel(n, SubstepModel.Contacts.GRID, true);
        for (int t = 0; t < at - 1; ++t) m.tickExact(s, tl, t, c);
        System.out.println("trace " + 20 * n + " Hz, tick " + (at - 1) + ".." + at);
        for (int t = at - 1; t < at; ++t)
        {
            // one substep at a time through a one-substep-per-call timeline replay
            for (int k = 0; k < n; ++k)
            {
                SubstepModel probe = m;
                double tau = probe.substepTau(t, k);
                double[][] buf = {new double[4]};
                int cnt = tl.samplesInside(tau, probe.substepTau(t, k + 1), buf);
                TickInput[] ins = new TickInput[cnt + 1];
                double[] offs = new double[cnt + 1];
                ins[0] = tl.at(tau);
                for (int i = 0; i < cnt; ++i)
                {
                    offs[i + 1] = buf[0][i] - tau;
                    ins[i + 1] = tl.at(buf[0][i]);
                }
                m.substep(s, c, ins, offs, cnt + 1, k == 0);
                if (k % Math.max(1, n / 12) == n / 12 - 1 || n < 12)
                    System.out.println(String.format(Locale.ROOT, "  %.4f pos(%.6f %.6f) mot(%.6f %.6f) gnd %b spr %b grid %.4f coll %b tog %.4f",
                        tau + m.h, s.posX(), s.posZ(), s.motionX, s.motionZ, s.onGround, s.sprinting, s.gridElapsed, s.collidedHorizontally, s.sprintToggleTimer));
            }
        }
    }

    /** First divergent tick, the positions around it and the input samples before it. */
    static String describe(InputTimeline tl, double[][] a, double[][] b)
    {
        int first = 0;
        while (first < a.length && dist(a[first], b[first]) < 1e-9) ++first;
        StringBuilder sb = new StringBuilder("first divergence at tick " + first + "\n");
        for (int t = Math.max(0, first - 3); t < Math.min(a.length, first + 3); ++t)
            sb.append(String.format(Locale.ROOT, "  t%d a(%.6f %.6f %.6f) b(%.6f %.6f %.6f)%n", t, a[t][0], a[t][1], a[t][2], b[t][0], b[t][1], b[t][2]));
        double[][] buf = {new double[4]};
        int n = tl.samplesInside(first - 2.0D, first + 1.0D, buf); // out[t] is the position after tick t
        for (int i = 0; i < n; ++i)
        {
            TickInput in = tl.at(buf[0][i]);
            sb.append(String.format(Locale.ROOT, "  input at %.4f: W%b A%b S%b D%b J%b N%b R%b%n", buf[0][i], in.forward, in.left, in.back, in.right, in.jump, in.sneak, in.sprint));
        }
        return sb.toString();
    }

    private static int lastJumps;

    /** Positions at every tick boundary. */
    static double[][] simulate(Course c, PlayerState start, InputTimeline tl, int n, boolean exact, StringBuilder log)
    {
        PlayerState s = start.copy();
        SubstepModel m = new SubstepModel(n, SubstepModel.Contacts.GRID, exact);
        double[][] out = new double[TICKS][];
        for (int t = 0; t < TICKS; ++t)
        {
            if (exact) m.tickExact(s, tl, t, c);
            else m.tick(s, tl, t, c);
            out[t] = new double[] {s.posX(), s.posY(), s.posZ()};
        }
        lastJumps = m.jumpImpulses;
        return out;
    }

    static void accumulate(double[] acc, double[][] ref, double[][] x)
    {
        for (int t = 0; t < ref.length; ++t)
        {
            double d = dist(ref[t], x[t]);
            acc[0] += d;
            acc[1] = Math.max(acc[1], d);
        }
    }

    static double maxDiff(double[][] a, double[][] b)
    {
        double m = 0.0D;
        for (int t = 0; t < a.length; ++t) m = Math.max(m, dist(a[t], b[t]));
        return m;
    }

    static double dist(double[] a, double[] b)
    {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Mostly forward movement with random taps and holds of every key at arbitrary times. */
    static InputTimeline randomTimeline(Random rnd)
    {
        InputTimeline tl = new InputTimeline();
        boolean[] d = new boolean[7];
        d[0] = rnd.nextBoolean();
        tl.add(0.0D, input(d));
        double t = 0.0D;
        while (true)
        {
            t += 0.05D + rnd.nextDouble() * 2.5D; // 2.5 ms .. 128 ms between events
            if (t >= TICKS) break;
            int k = pick(rnd);
            d[k] = !d[k];
            tl.add(t, input(d));
        }
        return tl;
    }

    private static int pick(Random rnd)
    {
        double u = rnd.nextDouble();
        if (u < 0.30D) return 0; // W
        if (u < 0.55D) return 4; // jump
        if (u < 0.65D) return 6; // sprint
        if (u < 0.75D) return NO_SNEAK ? 0 : 5; // sneak
        return 1 + rnd.nextInt(3); // A, S, D
    }

    private static TickInput input(boolean[] d)
    {
        return new TickInput(d[0], d[2], d[1], d[3], d[4], d[5], d[6], 0.0F, 0.0F);
    }
}
