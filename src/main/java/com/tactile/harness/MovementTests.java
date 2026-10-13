package com.tactile.harness;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.tactile.core.Box;
import com.tactile.core.Course;
import com.tactile.core.InputTimeline;
import com.tactile.core.PlayerState;
import com.tactile.core.Scenario;
import com.tactile.core.SubstepModel;
import com.tactile.core.TickInput;
import com.tactile.core.Trace;
import com.tactile.core.VanillaModel;

/**
 * Offline test runner (plain Java, no Minecraft runtime). Usage: gradlew movementTests.
 *
 * <p>Gates (failures exit non-zero):
 * <ol>
 * <li>A: VanillaModel vs traces recorded from the real 1.8.9 client (one-step and free-run).</li>
 * <li>B: SubstepModel at N = 1 with one sample per tick is bit-identical to VanillaModel.</li>
 * <li>B2: the timeline path with one sample per tick equals the held-input path, at every rate.</li>
 * <li>C: with no contacts, N substeps reproduce the vanilla tick map at tick boundaries.</li>
 * <li>D: exactly one jump impulse per tap at every rate.</li>
 * </ol>
 * Reports (measurement only): divergence from vanilla, physics convergence, input-timing sweep,
 * jump characteristics, and cost per rate.
 */
public final class MovementTests
{
    /** Substeps per tick evaluated in the reports (40 Hz ... 960 Hz). */
    private static final int[] RATES = {2, 3, 6, 12, 24, 48};
    /** Reference resolution for convergence and timing (3840 Hz). */
    private static final int REF_N = 192;

    private static int failures;
    /** Contact mode used by model(); the gates and reports run once per mode. */
    private static SubstepModel.Contacts mode = SubstepModel.Contacts.DISCRETE;

    private static SubstepModel model(int n)
    {
        return new SubstepModel(n, mode);
    }

    public static void main(String[] args) throws Exception
    {
        File dir = new File(args.length > 0 ? args[0] : "run/hrm-baseline");
        List<Trace> traces = loadTraces(dir);

        String dump = System.getenv("HRMOVE_DUMP"); // e.g. "sneak_edge:12" = scenario:substepsPerTick
        if (dump != null && !dump.isEmpty())
        {
            String[] p = dump.split(":");
            for (Trace t : traces) if (t.scenario.equals(p[0])) dumpComparison(t, Integer.parseInt(p[1]));
            return;
        }

        gateA(traces);
        gateB(traces);
        boolean quick = "1".equals(System.getenv("HRMOVE_QUICK"));
        for (SubstepModel.Contacts m : SubstepModel.Contacts.values())
        {
            mode = m;
            System.out.println("\n################ contact mode " + m);
            List<Trace> modeTraces = traces;
            if (m == SubstepModel.Contacts.DISCRETE)
            {
                // DISCRETE (the 20 Hz identity reference) does not model water, lava or ladders
                modeTraces = new ArrayList<Trace>();
                for (Trace t : traces) if (!Scenario.isMedium(t.scenario)) modeTraces.add(t);
            }
            gateB2(modeTraces);
            gateC();
            gateD();
            gateE();
            gateF();
            divergenceReport(modeTraces);
            convergenceReport(modeTraces);
            jumpReport();
            if (!quick) costReport(modeTraces);
        }
        mode = SubstepModel.Contacts.GRID;
        gateG();
        gateS();
        exactVanillaReport(traces);
        if (!quick) timingSweep();
        rateSpreadSummary(traces);

        System.out.println();
        System.out.println(failures == 0 ? "ALL GATES PASSED" : (failures + " GATE CHECK(S) FAILED"));
        if (failures != 0) System.exit(1);
    }

    // ================================================================== gates

    private static void gateA(List<Trace> traces)
    {
        header("Gate A: VanillaModel vs recorded 1.8.9 client traces");
        if (traces.isEmpty())
        {
            check(false, "no traces found; run the client with HRMOVE_BASELINE=1 first");
            return;
        }
        System.out.println(String.format(Locale.ROOT, "%-18s %5s %9s %11s %9s  %s", "scenario", "ticks", "1-step ok", "free maxE", "free@tick", "notes"));
        for (Trace t : traces)
        {
            Course course = courseFor(t);
            int exact = 0, rows = 0;
            String firstMismatch = null;
            for (int i = 0; i < t.rows.size(); ++i)
            {
                Trace.Row r = t.rows.get(i);
                if (r.contaminated) continue;
                PlayerState s = r.pre.copy();
                applyPrevInput(s, t, i);
                VanillaModel.tick(s, r.input, course);
                ++rows;
                if (stateError(s, r.post) == 0.0D && flagsMatch(s, r.post)) ++exact;
                else if (firstMismatch == null) firstMismatch = "first mismatch at tick " + r.tick + ": " + describeDiff(s, r.post);
            }

            PlayerState s = t.rows.get(0).pre.copy();
            applyPrevInput(s, t, 0);
            double freeMax = 0.0D;
            int freeFirstDiverge = -1;
            int ext = 0;
            for (Trace.Row r : t.rows)
            {
                if (r.externalChange)
                {
                    // A server packet between ticks is not movement physics: adopt it.
                    ++ext;
                    PlayerState keep = s;
                    s = r.pre.copy();
                    s.prevJump = keep.prevJump;
                    s.prevSneak = keep.prevSneak;
                    s.prevForwardHigh = keep.prevForwardHigh;
                }
                VanillaModel.tick(s, r.input, course);
                double e = stateError(s, r.post);
                if ((e != 0.0D || !flagsMatch(s, r.post)) && freeFirstDiverge < 0) freeFirstDiverge = r.tick;
                freeMax = Math.max(freeMax, e);
            }

            String notes = (ext > 0 ? "externalChanges=" + ext + " " : "") + (firstMismatch != null ? firstMismatch : "");
            System.out.println(String.format(Locale.ROOT, "%-18s %5d %4d/%-4d %11.3g %9s  %s", t.scenario, t.rows.size(), exact, rows,
                freeMax, freeFirstDiverge < 0 ? "-" : String.valueOf(freeFirstDiverge), notes));
            check(exact == rows, t.scenario + ": every uncontaminated tick reproduced bit-exactly");
        }
    }

    private static void gateB(List<Trace> traces)
    {
        header("Gate B: SubstepModel(N=1, one sample per tick) == VanillaModel, bitwise, on all recorded inputs");
        int ticks = 0, exact = 0;
        for (Trace t : traces)
        {
            if (Scenario.isMedium(t.scenario)) continue; // DISCRETE does not model water, lava or ladders
            Course course = courseFor(t);
            PlayerState a = t.rows.get(0).pre.copy();
            applyPrevInput(a, t, 0);
            PlayerState b = a.copy();
            SubstepModel m = new SubstepModel(1, SubstepModel.Contacts.DISCRETE);
            for (Trace.Row r : t.rows)
            {
                VanillaModel.tick(a, r.input, course);
                m.tick(b, r.input, course);
                ++ticks;
                if (stateError(a, b) == 0.0D && flagsMatch(a, b) && a.jumpTicks == b.jumpTicks) ++exact;
            }
        }
        System.out.println("  " + exact + "/" + ticks + " ticks bit-identical");
        check(exact == ticks, "h=1 identity");

        // GRID contacts at N = 1 must also be vanilla exactly (vanilla's collider on the full tick displacement).
        int gTicks = 0, gExact = 0;
        for (Trace t : traces)
        {
            Course course = courseFor(t);
            PlayerState a = t.rows.get(0).pre.copy();
            applyPrevInput(a, t, 0);
            PlayerState b = a.copy();
            SubstepModel m = new SubstepModel(1, SubstepModel.Contacts.GRID);
            for (Trace.Row r : t.rows)
            {
                VanillaModel.tick(a, r.input, course);
                m.tick(b, r.input, course);
                ++gTicks;
                if (stateError(a, b) == 0.0D && flagsMatch(a, b)) ++gExact;
            }
        }
        System.out.println("  GRID at N=1: " + gExact + "/" + gTicks + " ticks bit-identical");
        check(gExact == gTicks, "GRID h=1 identity");
    }

    private static void gateB2(List<Trace> traces)
    {
        header("Gate B2: timeline path (one sample at each tick start) == held-input path, bitwise");
        for (int n : RATES)
        {
            int ticks = 0, exact = 0;
            for (Trace t : traces)
            {
                Course course = courseFor(t);
                InputTimeline tl = new InputTimeline();
                for (int i = 0; i < t.rows.size(); ++i) tl.add(i, t.rows.get(i).input);
                PlayerState a = t.rows.get(0).pre.copy();
                applyPrevInput(a, t, 0);
                PlayerState b = a.copy();
                SubstepModel ma = model(n), mb = model(n);
                for (int i = 0; i < t.rows.size(); ++i)
                {
                    ma.tick(a, t.rows.get(i).input, course);
                    mb.tick(b, tl, i, course);
                    ++ticks;
                    if (stateError(a, b) == 0.0D && flagsMatch(a, b)) ++exact;
                }
            }
            check(exact == ticks, (20 * n) + " Hz timeline/held equivalence (" + exact + "/" + ticks + ")");
        }
        System.out.println("  checked at " + rateList());
    }

    private static void gateC()
    {
        header("Gate C: contact-free flow consistency at tick boundaries (relative error)");
        Course empty = new Course();
        String[] keys = {"W---J-R", "-A-----", "W-S----", "-------"};
        for (int n : RATES)
        {
            double worst = 0.0D;
            for (String k : keys)
            {
                // The start state must already agree with the keys (sprinting only with forward + sprint).
                // Otherwise sprint stops mid-tick: correct per-substep behavior, but not constant state.
                PlayerState a = airborne(0.31D, 0.2D, -0.17D, k.charAt(0) == 'W' && k.charAt(2) != 'S' && k.charAt(6) == 'R');
                PlayerState b = a.copy();
                SubstepModel m = model(n);
                for (int i = 0; i < 25; ++i)
                {
                    TickInput in = TickInput.fromKeyString(k, 17.0F + 3.0F * i, 0.0F);
                    VanillaModel.tick(a, in, empty);
                    m.tick(b, in, empty);
                    worst = Math.max(worst, relError(a, b));
                }
            }
            System.out.println(String.format(Locale.ROOT, "  %3d Hz: max relative error %.3g", 20 * n, worst));
            check(worst < 1.0E-9D, (20 * n) + " Hz reproduces the vanilla tick map in free flight");
        }
        if (mode != SubstepModel.Contacts.GRID) return;
        // The same in water and in lava (contact-free: a large still volume), with and without the swim push.
        for (Course.Shape liquid : new Course.Shape[] {Course.Shape.WATER, Course.Shape.LAVA})
        {
            Course pool = new Course().fill(-12, 480, -12, 12, 520, 12, liquid); // around airborne()'s y = 500
            for (int n : RATES)
            {
                double worst = 0.0D;
                for (String k : new String[] {"W---J--", "-A-----", "----J--", "-------"})
                {
                    PlayerState a = airborne(0.11D, -0.05D, 0.07D, false);
                    PlayerState b = a.copy();
                    SubstepModel m = model(n);
                    for (int i = 0; i < 25; ++i)
                    {
                        TickInput in = TickInput.fromKeyString(k, 17.0F + 3.0F * i, 0.0F);
                        VanillaModel.tick(a, in, pool);
                        m.tick(b, in, pool);
                        worst = Math.max(worst, relError(a, b));
                    }
                }
                System.out.println(String.format(Locale.ROOT, "  %-5s %3d Hz: max relative error %.3g", liquid, 20 * n, worst));
                check(worst < 1.0E-9D, liquid + " " + (20 * n) + " Hz reproduces the vanilla tick map");
            }
        }
        // Creative flight in open air, with ascend / descend held (no presses mid-tick: the keys are held from the start).
        Course air = new Course();
        for (int n : RATES)
        {
            double worst = 0.0D;
            for (String k : new String[] {"W---J--", "-A---N-", "W------", "-------"})
            {
                PlayerState a = airborne(0.11D, -0.05D, 0.07D, false);
                a.flying = true;
                PlayerState b = a.copy();
                SubstepModel m = model(n);
                for (int i = 0; i < 25; ++i)
                {
                    TickInput in = TickInput.fromKeyString(k, 17.0F + 3.0F * i, 0.0F);
                    VanillaModel.tick(a, in, air);
                    m.tick(b, in, air);
                    worst = Math.max(worst, relError(a, b));
                }
            }
            System.out.println(String.format(Locale.ROOT, "  FLY   %3d Hz: max relative error %.3g", 20 * n, worst));
            check(worst < 1.0E-9D, "flight " + (20 * n) + " Hz reproduces the vanilla tick map");
        }
    }

    private static void gateD()
    {
        header("Gate D: discrete semantics (jump impulses)");
        for (Scenario sc : Scenario.corpus())
        {
            if (!sc.name.equals("jump_in_place") && !sc.name.equals("sprint_jump_chain") && !sc.name.equals("head_hitter")) continue;
            Course course = sc.course(0, 100, 0);
            PlayerState v = startState(sc, 0, 100, 0);
            int vanillaJumps = 0;
            for (TickInput in : sc.script())
            {
                VanillaModel.tick(v, in, course);
                if (v.jumpTicks == 10) ++vanillaJumps; // decremented before the check, so 10 = fresh impulse
            }
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "  %-18s vanilla=%d", sc.name, vanillaJumps));
            for (int n : RATES)
            {
                PlayerState s = startState(sc, 0, 100, 0);
                SubstepModel m = model(n);
                for (TickInput in : sc.script()) m.tick(s, in, course);
                sb.append(String.format(Locale.ROOT, "  %dHz=%d", 20 * n, m.jumpImpulses));
                if (sc.name.equals("jump_in_place")) check(m.jumpImpulses == vanillaJumps, sc.name + " @" + 20 * n + "Hz: one impulse per tap");
                else check(Math.abs(m.jumpImpulses - vanillaJumps) <= 1, sc.name + " @" + 20 * n + "Hz: held-jump count within +/-1 (landing resolved mid-tick)");
            }
            System.out.println(sb);
        }
    }

    /**
     * Gate E: a jump tap shorter than one substep that falls strictly between two substep starts must
     * still produce exactly one impulse (press latch). The same input through plain sample-and-hold
     * (InputTimeline.at) is reported to show what is lost without the latch.
     */
    private static void gateE()
    {
        header("Gate E: sub-substep jump tap (press latch)");
        Course floor = new Course().fill(-5, 99, -5, 5, 99, 400, Course.Shape.FULL);
        StringBuilder sb = new StringBuilder("  impulses with latch / plain sample-and-hold:");
        for (int n : RATES)
        {
            double h = 1.0D / n;
            double tapStart = 20.0D + 0.3D * h, tapEnd = 20.0D + 0.7D * h; // inside (20, 20 + h)
            InputTimeline tl = new InputTimeline()
                .add(0.0D, TickInput.fromKeyString("W-----R", 0.0F, 0.0F))
                .add(tapStart, TickInput.fromKeyString("W---J-R", 0.0F, 0.0F))
                .add(tapEnd, TickInput.fromKeyString("W-----R", 0.0F, 0.0F));
            PlayerState a = restState(), b = restState();
            SubstepModel latched = model(n), plain = model(n);
            for (long t = 0; t < 30; ++t)
            {
                latched.tick(a, tl, t, floor);
                for (int k = 0; k < n; ++k) plain.substep(b, floor, tl.at(plain.substepTau(t, k)), k == 0);
            }
            sb.append(String.format(Locale.ROOT, "  %dHz=%d/%d", 20 * n, latched.jumpImpulses, plain.jumpImpulses));
            check(latched.jumpImpulses == 1, (20 * n) + " Hz: a tap shorter than one substep produces exactly one jump");
        }
        System.out.println(sb);
    }

    /**
     * Gate F: a control input acts from the moment it is detected, not from the next tick. From rest, forward
     * is pressed at tau = 2.30 (mid-tick); then, separately, jump is pressed at tau = 5.30 while walking.
     * Checked at tau = 2.75 and 5.75, before the next tick boundary: the player must already have moved
     * (or risen), and by the amount the model predicts for an input starting at its real time. The press
     * time is placed on a substep start so that only the physics is measured, not sample timing.
     */
    private static void gateF()
    {
        header("Gate F: mid-tick input acts from its detection time (not the next tick)");
        if (mode == SubstepModel.Contacts.DISCRETE)
        {
            System.out.println("  (DISCRETE mode is the 20 Hz-identity reference; gate applies to the live GRID mode)");
            return;
        }
        Course floor = new Course().fill(-5, 99, -5, 5, 99, 50, Course.Shape.FULL);
        StringBuilder sb = new StringBuilder();
        for (int n : new int[] {20, 48})
        {
            double press = 2.25D, jump = 5.25D; // substep starts at both tested rates
            InputTimeline tl = new InputTimeline()
                .add(0.0D, TickInput.fromKeyString("-------", 0.0F, 0.0F))
                .add(press, TickInput.fromKeyString("W------", 0.0F, 0.0F))
                .add(jump, TickInput.fromKeyString("W---J--", 0.0F, 0.0F));
            PlayerState s = restState();
            SubstepModel m = model(n);
            double zAt275 = Double.NaN, yAt575 = Double.NaN, zAt3 = Double.NaN;
            for (long t = 0; t < 8; ++t)
            {
                for (int k = 0; k < n; ++k)
                {
                    double tau = m.substepTau(t, k);
                    double prevTau = (double)(t * n + k - 1) / n;
                    m.substep(s, floor, tl.atLatched(prevTau, tau), k == 0);
                    double end = (double)(t * n + k + 1) / n;
                    if (Math.abs(end - 2.75D) < 1e-9) zAt275 = s.posZ() - 0.5D;
                    if (Math.abs(end - 3.0D) < 1e-9) zAt3 = s.posZ() - 0.5D;
                    if (Math.abs(end - 5.75D) < 1e-9) yAt575 = s.posY() - 100.0D;
                }
            }
            // Expected: displacement after 0.5 ticks of forward acceleration from rest, D = a(T + f(T - S)/(1 - f)),
            // S = (1 - f^T)/(1 - f) (the exact flow with v = 0, ground parameters, input scaled by 0.98).
            double f = (double)(0.6F * 0.91F);
            double a = (double)(0.1F * (0.16277136F / ((float)f * (float)f * (float)f)) * 0.98F);
            double T = 0.5D, S = (1.0D - Math.pow(f, T)) / (1.0D - f);
            double expectedZ = a * (T + f * (T - S) / (1.0D - f));
            boolean moved = zAt275 > 0.005D && Math.abs(zAt275 - expectedZ) < 1e-4D;
            boolean rose = yAt575 > 0.15D; // 0.5 ticks after a 0.42/tick impulse
            sb.append(String.format(Locale.ROOT, "  %dHz: z(2.75)=%.5f (expected %.5f), y(5.75)=%.4f;", 20 * n, zAt275, expectedZ, yAt575));
            check(moved, (20 * n) + " Hz: forward pressed at 2.30 moves the player before tick 3, by the predicted amount");
            check(rose, (20 * n) + " Hz: jump pressed at 5.30 lifts the player before tick 6");
        }
        System.out.println(sb);
    }

    /**
     * Gate G (EXACT input timing): substeps split at input timestamps.
     * <ol>
     * <li>Forward pressed at tau = 2.3137, on no substep grid. At tick 3 the displacement must equal the exact
     *     flow for the 0.6863 ticks since the press, at 20, 60, 240 and 960 Hz.</li>
     * <li>200 random input timelines on a course with a step, a gap and edges: the tick-boundary positions at
     *     20, 60 and 960 Hz must equal those at 240 Hz (the physics rate no longer changes the result).</li>
     * </ol>
     */
    private static void gateG()
    {
        header("Gate G: EXACT input timing (substeps split at input timestamps)");
        Course floor = new Course().fill(-5, 99, -5, 5, 99, 50, Course.Shape.FULL);
        double press = 2.3137D;
        double f = (double)(0.6F * 0.91F);
        double a = (double)(0.1F * (0.16277136F / ((float)f * (float)f * (float)f)) * 0.98F);
        double T = 3.0D - press, S = (1.0D - Math.pow(f, T)) / (1.0D - f);
        double expectedZ = a * (T + f * (T - S) / (1.0D - f));
        StringBuilder sb = new StringBuilder("  forward at 2.3137, z(3) - z0:");
        for (int n : new int[] {1, 3, 12, 48})
        {
            InputTimeline tl = new InputTimeline()
                .add(0.0D, TickInput.fromKeyString("-------", 0.0F, 0.0F))
                .add(press, TickInput.fromKeyString("W------", 0.0F, 0.0F));
            PlayerState s = restState();
            SubstepModel m = new SubstepModel(n, SubstepModel.Contacts.GRID, true);
            for (long t = 0; t < 3; ++t) m.tickExact(s, tl, t, floor);
            double z = s.posZ() - 0.5D;
            sb.append(String.format(Locale.ROOT, " %dHz %.9f", 20 * n, z));
            check(Math.abs(z - expectedZ) < 1e-9, (20 * n) + " Hz: off-grid press moves exactly the flow from its timestamp");
        }
        System.out.println(sb + String.format(Locale.ROOT, " (expected %.9f)", expectedZ));

        Course c = new Course()
            .fill(-20, 99, -20, 20, 99, 19, Course.Shape.FULL)
            .fill(-20, 100, 8, 20, 100, 9, Course.Shape.FULL)
            .fill(-20, 99, 22, 20, 99, 40, Course.Shape.FULL);
        java.util.Random rnd = new java.util.Random(11L);
        double worst = 0.0D;
        for (int run = 0; run < 200; ++run)
        {
            InputTimeline tl = ExactTimingTest.randomTimeline(rnd);
            PlayerState start = restState();
            start.box = com.tactile.core.Box.forPlayerAt(0.5D + rnd.nextDouble(), 100.0D, rnd.nextDouble() * 6.0D);
            double[][] ref = ExactTimingTest.simulate(c, start, tl, 12, true, null);
            for (int n : new int[] {1, 3, 48}) worst = Math.max(worst, ExactTimingTest.maxDiff(ref, ExactTimingTest.simulate(c, start, tl, n, true, null)));
        }
        System.out.println(String.format(Locale.ROOT, "  200 random timelines, 20/60/960 Hz vs 240 Hz: max difference %.2e blocks", worst));
        check(worst < 1e-9, "EXACT results do not depend on the physics rate");
    }

    /**
     * Gate S: STEPPED (EXACT physics, key changes only at step boundaries). On 200 random off-grid timelines:
     * <ol>
     * <li>input timing: 20 Hz steps without the tap latch read the keys where vanilla does (the state at each tick
     *     start). Checked against EXACT fed exactly that per-tick key state: bit-exact. (STEPPED's physics is EXACT's;
     *     EXACT vs vanilla is gates C/G and the reports, including the accepted mid-air sprint difference.)</li>
     * <li>implementation: the STEPPED driver equals EXACT fed the timeline converted to step boundaries (35 Hz, latch):
     *     bit-exact.</li>
     * <li>a step rate that does not divide the tick (35 Hz) gives the same result at 60, 240 and 960 Hz physics. This
     *     found a rate dependence in EXACT itself (a mid-tick jump into a wall while sprinting: sprint ended at the next
     *     piece start instead of before the jump), fixed in Phase 21.</li>
     * </ol>
     */
    private static void gateS()
    {
        header("Gate S: STEPPED (keys take effect at step boundaries, EXACT physics)");
        Course c = new Course()
            .fill(-20, 99, -20, 20, 99, 19, Course.Shape.FULL)
            .fill(-20, 100, 8, 20, 100, 9, Course.Shape.FULL)
            .fill(-20, 99, 22, 20, 99, 40, Course.Shape.FULL);
        java.util.Random rnd = new java.util.Random(13L);
        double worstTiming = 0.0D, worstImpl = 0.0D, worstRate = 0.0D;
        int rateRuns = 0;
        double step35 = 20.0D / 35.0D;
        for (int run = 0; run < 200; ++run)
        {
            InputTimeline tl = ExactTimingTest.randomTimeline(rnd);
            PlayerState start = restState();
            start.box = com.tactile.core.Box.forPlayerAt(0.5D + rnd.nextDouble(), 100.0D, rnd.nextDouble() * 6.0D);
            InputTimeline perTick = materialise(tl, 1.0D, false), per35 = materialise(tl, step35, true);
            PlayerState a = start.copy(), b = start.copy(), c35 = start.copy(), d35 = start.copy();
            SubstepModel ma = new SubstepModel(12, SubstepModel.Contacts.GRID, true), mb = new SubstepModel(12, SubstepModel.Contacts.GRID, true);
            SubstepModel mc = new SubstepModel(12, SubstepModel.Contacts.GRID, true), md = new SubstepModel(12, SubstepModel.Contacts.GRID, true);
            PlayerState[] r = {start.copy(), start.copy()};
            SubstepModel[] mr = {new SubstepModel(3, SubstepModel.Contacts.GRID, true), new SubstepModel(48, SubstepModel.Contacts.GRID, true)};
            double runRate = 0.0D;
            for (long t = 0; t < 60 && a.posY() > 90.0D; ++t)
            {
                ma.tickStepped(a, tl, t, c, 1.0D, false);
                mb.tickExact(b, perTick, t, c);
                mc.tickStepped(c35, tl, t, c, step35, true);
                md.tickExact(d35, per35, t, c);
                for (int i = 0; i < 2; ++i) mr[i].tickStepped(r[i], tl, t, c, step35, true);
                worstTiming = Math.max(worstTiming, stateError(a, b));
                worstImpl = Math.max(worstImpl, stateError(c35, d35));
                runRate = Math.max(runRate, Math.max(stateError(r[0], c35), stateError(r[1], c35)));
            }
            worstRate = Math.max(worstRate, runRate);
            if (runRate > 1e-9) ++rateRuns;
        }
        System.out.println(String.format(Locale.ROOT, "  20 Hz steps (no latch) vs EXACT fed vanilla's per-tick key state: max difference %.2e", worstTiming));
        System.out.println(String.format(Locale.ROOT, "  STEPPED 35 Hz vs EXACT on the stepped timeline: max difference %.2e", worstImpl));
        System.out.println(String.format(Locale.ROOT, "  35 Hz steps at 60/960 Hz vs 240 Hz physics: %d/200 runs differ, max %.2e blocks", rateRuns, worstRate));
        check(worstTiming == 0.0D, "STEPPED at 20 Hz reads the keys exactly where vanilla does");
        // run 165 of this seed is a mid-tick jump into a wall while sprinting: rate-dependent before Phase 21's fix
        check(worstRate < 1e-9, "STEPPED results (and EXACT's, including a mid-tick jump into a wall) do not depend on the physics rate");
        check(worstImpl < 1e-9, "the STEPPED driver equals EXACT on the stepped timeline (floating-point rounding only)");
    }

    /** The timeline as STEPPED applies it: samples only at step boundaries (multiples of stepTicks), up to 80 ticks. */
    private static InputTimeline materialise(InputTimeline tl, double stepTicks, boolean latch)
    {
        InputTimeline out = new InputTimeline();
        String last = null;
        for (double k = 0.0D; k * stepTicks <= 80.0D; k += 1.0D)
        {
            double b = k * stepTicks;
            if (b < tl.firstTau()) continue;
            TickInput in = tl.stepped(b, stepTicks, latch);
            String key = in.keyString() + "|" + in.yaw;
            if (!key.equals(last))
            {
                out.add(b, in);
                last = key;
            }
        }
        return out;
    }

    /** EXACT vs HIGH_RES (both GRID, 240 Hz): divergence from vanilla on the recorded corpus, tick-sampled input. */
    private static void exactVanillaReport(List<Trace> traces)
    {
        header("Report 6: divergence from vanilla, HIGH_RES vs EXACT at 240 Hz (max tick-boundary distance, blocks)");
        for (Trace t : traces)
        {
            Course course = courseFor(t);
            PlayerState ref = initial(t), a = ref.copy(), b = ref.copy();
            SubstepModel ma = new SubstepModel(12, SubstepModel.Contacts.GRID), mb = new SubstepModel(12, SubstepModel.Contacts.GRID, true);
            double da = 0.0D, db = 0.0D;
            for (Trace.Row r : t.rows)
            {
                VanillaModel.tick(ref, r.input, course);
                ma.tick(a, r.input, course);
                mb.tick(b, r.input, course);
                da = Math.max(da, posError(ref, a));
                db = Math.max(db, posError(ref, b));
            }
            System.out.println(String.format(Locale.ROOT, "  %-18s HIGH_RES %.4f  EXACT %.4f", t.scenario, da, db));
        }
    }

    // ================================================================== reports

    private static void divergenceReport(List<Trace> traces)
    {
        header("Report 1: divergence from vanilla 20 Hz (max position distance at tick boundaries, blocks; tick-sampled input)");
        StringBuilder head = new StringBuilder(String.format(Locale.ROOT, "  %-18s", "scenario"));
        for (int n : RATES) head.append(String.format(Locale.ROOT, " %8s", (20 * n) + "Hz"));
        System.out.println(head);
        for (Trace t : traces)
        {
            Course course = courseFor(t);
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  %-18s", t.scenario));
            for (int n : RATES)
            {
                PlayerState ref = initial(t), s = ref.copy();
                SubstepModel m = model(n);
                double max = 0.0D;
                for (Trace.Row r : t.rows)
                {
                    VanillaModel.tick(ref, r.input, course);
                    m.tick(s, r.input, course);
                    max = Math.max(max, posError(ref, s));
                }
                line.append(String.format(Locale.ROOT, " %8.4f", max));
            }
            System.out.println(line);
        }
    }

    /**
     * Physics convergence: the same tick-sampled inputs at increasing N, compared with a 3840 Hz
     * reference. Free flight is exact at every N (gate C), so what remains is contact resolution.
     * Scenarios whose re-jump timing depends on landing time can amplify small differences.
     */
    private static void convergenceReport(List<Trace> traces)
    {
        header("Report 2: physics convergence vs " + (20 * REF_N) + " Hz reference (max position distance at tick boundaries, blocks)");
        StringBuilder head = new StringBuilder(String.format(Locale.ROOT, "  %-18s", "scenario"));
        for (int n : RATES) head.append(String.format(Locale.ROOT, " %8s", (20 * n) + "Hz"));
        System.out.println(head);
        double[] worstNonChaotic = new double[RATES.length];
        for (Trace t : traces)
        {
            Course course = courseFor(t);
            List<PlayerState> refStates = new ArrayList<PlayerState>();
            PlayerState ref = initial(t);
            SubstepModel mr = model(REF_N);
            for (Trace.Row r : t.rows)
            {
                mr.tick(ref, r.input, course);
                refStates.add(ref.copy());
            }
            boolean chaotic = t.scenario.equals("sprint_jump_chain") || t.scenario.equals("turning_jumps");
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  %-18s", t.scenario + (chaotic ? "*" : "")));
            for (int i = 0; i < RATES.length; ++i)
            {
                PlayerState s = initial(t);
                SubstepModel m = model(RATES[i]);
                double max = 0.0D;
                for (int j = 0; j < t.rows.size(); ++j)
                {
                    m.tick(s, t.rows.get(j).input, course);
                    max = Math.max(max, posError(refStates.get(j), s));
                }
                if (!chaotic) worstNonChaotic[i] = Math.max(worstNonChaotic[i], max);
                line.append(String.format(Locale.ROOT, " %8.4f", max));
            }
            System.out.println(line);
        }
        StringBuilder w = new StringBuilder(String.format(Locale.ROOT, "  %-18s", "worst (excl. *)"));
        for (double d : worstNonChaotic) w.append(String.format(Locale.ROOT, " %8.4f", d));
        System.out.println(w);
        System.out.println("  * held-jump chains: a shifted landing shifts every later jump, so differences accumulate over the run");
    }

    /** One sampler configuration for the timing sweep. */
    private static class Sampler
    {
        final String label;
        final double[] ratesHz; // successive quantization stages; e.g. keyboard report then poll thread

        Sampler(String label, double... ratesHz)
        {
            this.label = label;
            this.ratesHz = ratesHz;
        }

        /**
         * Normalized time at which an event at tau is first visible to the simulation. {@code phase} in
         * [0,1) shifts each stage's sampling grid relative to the tick grid, because real frames and
         * keyboard reports are not aligned with physics substeps.
         */
        double observe(double tau, double phase)
        {
            double t = tau;
            for (double hz : ratesHz)
            {
                double q = 20.0D / hz;
                double offset = phase * q;
                t = Math.ceil((t - offset) / q - 1.0E-9D) * q + offset;
            }
            return t;
        }
    }

    private static final class JumpRun
    {
        double takeoffTau = Double.NaN, takeoffZ, landZ = Double.NaN;
    }

    /**
     * Input-timing sweep. After a 20-tick sprint run-up, a jump press happens at 200 sub-tick offsets
     * within tick 20. Each configuration observes it through its sampler. Physics then applies it at the
     * next substep start (sample-and-hold). Outcomes are compared with a 3840 Hz run that sees the
     * event at its exact time. Sampler grids get a different phase for each event, since frames and
     * keyboard reports are not aligned with the tick grid. The vanilla KeyBinding row stays on the
     * tick grid by construction.
     */
    private static void timingSweep()
    {
        header("Report 3: input-timing sweep: jump press at 200 offsets within one tick (sprinting, flat floor)");
        Course floor = new Course().fill(-5, 99, -5, 5, 99, 400, Course.Shape.FULL);
        Sampler[] samplers = {
            new Sampler("frame 60 FPS", 60),
            new Sampler("frame 144 FPS", 144),
            new Sampler("frame 240 FPS", 240),
            new Sampler("kbd 125Hz + 1kHz poll", 125, 1000),
            new Sampler("kbd 1kHz + 1kHz poll", 1000, 1000),
            new Sampler("exact (ideal)")};
        Sampler keyBinding = new Sampler("KeyBinding 20Hz", 20)
        {
            @Override
            double observe(double tau, double phase)
            {
                return super.observe(tau, 0.0D); // KeyBinding changes exactly at runTick
            }
        };
        int offsets = 200;

        JumpRun[] ref = new JumpRun[offsets];
        double[] eventTau = new double[offsets];
        for (int i = 0; i < offsets; ++i)
        {
            eventTau[i] = 20.0D + (i + 0.5D) / offsets;
            ref[i] = jumpRun(floor, REF_N, eventTau[i]);
        }

        // vanilla: KeyBinding state changes at the next tick, physics at 20 Hz
        double[] vanilla = sweepStats(floor, 1, keyBinding, eventTau, ref);

        System.out.println("  takeoff delay after the real press, mean / max (ms):");
        printSweepHeader();
        System.out.println(String.format(Locale.ROOT, "  %-24s %s", "vanilla (KeyBinding, 20Hz)", cell(vanilla[0], vanilla[1])));
        List<double[][]> all = new ArrayList<double[][]>();
        for (Sampler sm : samplers)
        {
            double[][] row = new double[RATES.length][];
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  %-24s", sm.label));
            for (int k = 0; k < RATES.length; ++k)
            {
                row[k] = sweepStats(floor, RATES[k], sm, eventTau, ref);
                line.append(cell(row[k][0], row[k][1]));
            }
            all.add(row);
            System.out.println(line);
        }

        System.out.println("  takeoff position error vs exact-time reference, mean / max (milliblocks):");
        printSweepHeader();
        System.out.println(String.format(Locale.ROOT, "  %-24s %s", "vanilla (KeyBinding, 20Hz)", cell(vanilla[2] * 1000, vanilla[3] * 1000)));
        for (int s = 0; s < samplers.length; ++s)
        {
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  %-24s", samplers[s].label));
            for (int k = 0; k < RATES.length; ++k) line.append(cell(all.get(s)[k][2] * 1000, all.get(s)[k][3] * 1000));
            System.out.println(line);
        }

        System.out.println("  landing position error vs exact-time reference, mean / max (milliblocks):");
        printSweepHeader();
        System.out.println(String.format(Locale.ROOT, "  %-24s %s", "vanilla (KeyBinding, 20Hz)", cell(vanilla[4] * 1000, vanilla[5] * 1000)));
        for (int s = 0; s < samplers.length; ++s)
        {
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  %-24s", samplers[s].label));
            for (int k = 0; k < RATES.length; ++k) line.append(cell(all.get(s)[k][4] * 1000, all.get(s)[k][5] * 1000));
            System.out.println(line);
        }
    }

    private static void printSweepHeader()
    {
        StringBuilder head = new StringBuilder(String.format(Locale.ROOT, "  %-24s", "sampler \\ physics"));
        for (int n : RATES) head.append(String.format(Locale.ROOT, " %15s", (20 * n) + "Hz"));
        System.out.println(head);
    }

    private static String cell(double mean, double max)
    {
        return String.format(Locale.ROOT, " %6.2f / %6.2f", mean, max);
    }

    /** Returns {meanDelayMs, maxDelayMs, meanTakeoffErr, maxTakeoffErr, meanLandErr, maxLandErr}. */
    private static double[] sweepStats(Course floor, int n, Sampler sampler, double[] eventTau, JumpRun[] ref)
    {
        double[] r = new double[6];
        for (int i = 0; i < eventTau.length; ++i)
        {
            double phase = (i * 0.6180339887498949D) % 1.0D; // low-discrepancy spread of sampler phases
            JumpRun run = jumpRun(floor, n, sampler.observe(eventTau[i], phase));
            double delayMs = (run.takeoffTau - eventTau[i]) * 50.0D;
            double eTake = Math.abs(run.takeoffZ - ref[i].takeoffZ);
            double eLand = Math.abs(run.landZ - ref[i].landZ);
            r[0] += delayMs;
            r[1] = Math.max(r[1], delayMs);
            r[2] += eTake;
            r[3] = Math.max(r[3], eTake);
            r[4] += eLand;
            r[5] = Math.max(r[5], eLand);
        }
        r[0] /= eventTau.length;
        r[2] /= eventTau.length;
        r[4] /= eventTau.length;
        return r;
    }

    /** Sprint from rest; jump pressed at observedTau and released two ticks later; record takeoff and landing. */
    private static JumpRun jumpRun(Course floor, int n, double observedTau)
    {
        InputTimeline tl = new InputTimeline()
            .add(0.0D, TickInput.fromKeyString("W-----R", 0.0F, 0.0F))
            .add(observedTau, TickInput.fromKeyString("W---J-R", 0.0F, 0.0F))
            .add(observedTau + 2.0D, TickInput.fromKeyString("W-----R", 0.0F, 0.0F));
        PlayerState s = restState();
        SubstepModel m = model(n);
        JumpRun run = new JumpRun();
        for (long tick = 0; tick < 40 && Double.isNaN(run.landZ); ++tick)
        {
            for (int k = 0; k < n; ++k)
            {
                double tau = m.substepTau(tick, k);
                double prevTau = (double)(tick * n + k - 1) / (double)n;
                boolean wasGround = s.onGround;
                double z0 = s.posZ();
                m.substep(s, floor, tl.atLatched(prevTau, tau), k == 0);
                if (Double.isNaN(run.takeoffTau) && wasGround && !s.onGround)
                {
                    run.takeoffTau = tau;
                    run.takeoffZ = z0;
                }
                else if (!Double.isNaN(run.takeoffTau) && Double.isNaN(run.landZ) && s.onGround)
                {
                    run.landZ = s.posZ();
                }
            }
        }
        return run;
    }

    private static void jumpReport()
    {
        header("Report 4: jump characteristics (flat floor). Hang time = ticks the displayed height spends above 1.0 / 1.2 blocks");
        System.out.println(String.format(Locale.ROOT, "  %-10s %8s %10s %8s %8s %14s", "rate", "apex", "landTau", "t>1.0", "t>1.2", "sprintJump dz"));
        Course floor = new Course().fill(-5, 99, -5, 5, 99, 200, Course.Shape.FULL);

        // vanilla: tick resolution; the client draws straight lines between tick positions
        PlayerState v = restState();
        double apex = 0.0D, land = -1.0D;
        List<double[]> path = new ArrayList<double[]>();
        path.add(new double[] {0.0D, 0.0D});
        for (int i = 0; i < 30; ++i)
        {
            VanillaModel.tick(v, TickInput.fromKeyString(i == 0 ? "----J--" : "-------", 0.0F, 0.0F), floor);
            apex = Math.max(apex, v.posY() - 100.0D);
            path.add(new double[] {i + 1.0D, v.posY() - 100.0D});
            if (i > 0 && v.onGround && land < 0) land = i + 1;
        }
        System.out.println(String.format(Locale.ROOT, "  %-10s %8.4f %10.3f %8.3f %8.3f %14.4f", "vanilla", apex, land,
            timeAbove(path, 1.0D), timeAbove(path, 1.2D), sprintJumpDistance(floor, 1)));

        for (int n : RATES)
        {
            PlayerState s = restState();
            SubstepModel m = model(n);
            apex = 0.0D;
            land = -1.0D;
            boolean airborne = false; // GRID commits onGround at grid ends, so "on ground" right after takeoff is not a landing
            path.clear();
            path.add(new double[] {0.0D, 0.0D});
            for (long i = 0; i < 30; ++i)
            {
                TickInput in = TickInput.fromKeyString(i == 0 ? "----J--" : "-------", 0.0F, 0.0F);
                for (int k = 0; k < n; ++k)
                {
                    m.substep(s, floor, in, k == 0);
                    apex = Math.max(apex, s.posY() - 100.0D);
                    path.add(new double[] {i + (k + 1.0D) / n, s.posY() - 100.0D});
                    if (!s.onGround) airborne = true;
                    else if (airborne && land < 0) land = i + (k + 1.0D) / n;
                }
            }
            System.out.println(String.format(Locale.ROOT, "  %-10s %8.4f %10.3f %8.3f %8.3f %14.4f", (20 * n) + "Hz", apex, land,
                timeAbove(path, 1.0D), timeAbove(path, 1.2D), sprintJumpDistance(floor, n)));
        }
    }

    /** Time a piecewise-linear height path {t, y} spends above height c. */
    private static double timeAbove(List<double[]> path, double c)
    {
        double total = 0.0D;
        for (int i = 1; i < path.size(); ++i)
        {
            double t0 = path.get(i - 1)[0], y0 = path.get(i - 1)[1], t1 = path.get(i)[0], y1 = path.get(i)[1];
            if (y0 >= c && y1 >= c) total += t1 - t0;
            else if (y0 >= c || y1 >= c)
            {
                double f = (c - y0) / (y1 - y0); // crossing fraction
                total += y0 >= c ? f * (t1 - t0) : (1.0D - f) * (t1 - t0);
            }
        }
        return total;
    }

    /** Rate dependence at a glance: sprint-jump distance and corpus spread for each contact mode. */
    private static void rateSpreadSummary(List<Trace> traces)
    {
        header("Rate-spread summary (both contact modes)");
        Course floor = new Course().fill(-5, 99, -5, 5, 99, 200, Course.Shape.FULL);
        int[] rates = {2, 6, 12, 24, 48};
        for (SubstepModel.Contacts m : SubstepModel.Contacts.values())
        {
            mode = m;
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "  %-11s sprint-jump dz:", m));
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, min2 = Double.MAX_VALUE, max2 = -Double.MAX_VALUE;
            for (int n : rates)
            {
                double d = sprintJumpDistance(floor, n);
                sb.append(String.format(Locale.ROOT, " %dHz=%.4f", 20 * n, d));
                if (n >= 6)
                {
                    min = Math.min(min, d);
                    max = Math.max(max, d);
                }
                if (n >= 12 && n <= 24)
                {
                    min2 = Math.min(min2, d);
                    max2 = Math.max(max2, d);
                }
            }
            sb.append(String.format(Locale.ROOT, " | spread 120-960: %.4f (%.2f%%), 240-480: %.4f (%.2f%%)", max - min, 100 * (max - min) / max,
                max2 - min2, 100 * (max2 - min2) / max2));
            System.out.println(sb);

            // corpus: worst tick-boundary distance between 240 and 480 Hz, and 480 and 960 Hz
            double w240_480 = 0.0D, w480_960 = 0.0D;
            for (Trace t : traces)
            {
                if (m == SubstepModel.Contacts.DISCRETE && Scenario.isMedium(t.scenario)) continue;
                Course course = courseFor(t);
                PlayerState a = initial(t), b = a.copy(), c = a.copy();
                SubstepModel ma = model(12), mb = model(24), mc = model(48);
                for (Trace.Row r : t.rows)
                {
                    ma.tick(a, r.input, course);
                    mb.tick(b, r.input, course);
                    mc.tick(c, r.input, course);
                    if (!t.scenario.equals("sprint_jump_chain") && !t.scenario.equals("turning_jumps"))
                    {
                        w240_480 = Math.max(w240_480, posError(a, b));
                        w480_960 = Math.max(w480_960, posError(b, c));
                    }
                }
            }
            System.out.println(String.format(Locale.ROOT, "  %-11s corpus worst distance (excl. jump chains): 240 vs 480 Hz %.4f, 480 vs 960 Hz %.4f blocks",
                m, w240_480, w480_960));
        }
    }

    /**
     * 10 ticks of sprint, one sprint-jump at tau = 10 (jump released at 12), sprint held on. Returns the
     * distance covered from takeoff until tau = 24, a fixed time after landing. Measuring "takeoff to
     * landing substep" instead would include a rate-dependent bit of ground slide after touchdown.
     */
    private static double sprintJumpDistance(Course floor, int n)
    {
        InputTimeline tl = new InputTimeline()
            .add(0.0D, TickInput.fromKeyString("W-----R", 0.0F, 0.0F))
            .add(10.0D, TickInput.fromKeyString("W---J-R", 0.0F, 0.0F))
            .add(12.0D, TickInput.fromKeyString("W-----R", 0.0F, 0.0F));
        PlayerState s = restState();
        SubstepModel m = model(n);
        double z10 = 0.0D;
        for (long t = 0; t < 24; ++t)
        {
            if (t == 10) z10 = s.posZ();
            m.tick(s, tl, t, floor);
        }
        return s.posZ() - z10;
    }

    private static void costReport(List<Trace> traces)
    {
        header("Report 5: offline cost per vanilla tick (static course collision; excludes in-game world queries)");
        for (int rep = 0; rep < 2; ++rep) // first pass warms up the JIT
        {
            StringBuilder line = new StringBuilder("  ");
            for (int n : RATES)
            {
                long ticks = 0;
                long t0 = System.nanoTime();
                for (int iter = 0; iter < 3; ++iter)
                    for (Trace t : traces)
                    {
                        Course course = courseFor(t);
                        PlayerState s = initial(t);
                        SubstepModel m = model(n);
                        for (Trace.Row r : t.rows) m.tick(s, r.input, course);
                        ticks += t.rows.size();
                    }
                double usPerTick = (System.nanoTime() - t0) / 1000.0D / ticks;
                line.append(String.format(Locale.ROOT, "%dHz %.1fus  ", 20 * n, usPerTick));
            }
            if (rep == 1) System.out.println(line);
        }
    }

    /** Diagnostic: per-tick vanilla vs substep state for one scenario. */
    private static void dumpComparison(Trace t, int n)
    {
        Course course = courseFor(t);
        PlayerState ref = initial(t), s = ref.copy();
        if ("GRID".equals(System.getenv("HRMOVE_DUMPMODE"))) mode = SubstepModel.Contacts.GRID;
        SubstepModel m = model(n);
        SubstepModel refModel = "1".equals(System.getenv("HRMOVE_DUMPREF")) ? model(REF_N) : null; // else vanilla
        System.out.println("tick keys    | " + (refModel == null ? "vanilla" : (20 * REF_N) + "Hz") + " x y z mx mz g | " + (20 * n) + "Hz x y z mx mz g  (" + mode + ")");
        for (Trace.Row r : t.rows)
        {
            if (refModel == null) VanillaModel.tick(ref, r.input, course);
            else refModel.tick(ref, r.input, course);
            m.tick(s, r.input, course);
            System.out.println(String.format(Locale.ROOT, "%4d %s | %.4f %.4f %.4f %+.4f %+.4f %s | %.4f %.4f %.4f %+.4f %+.4f %s",
                r.tick, r.input.keyString(),
                ref.posX(), ref.posY(), ref.posZ(), ref.motionX, ref.motionZ, ref.onGround ? "G" : "-",
                s.posX(), s.posY(), s.posZ(), s.motionX, s.motionZ, s.onGround ? "G" : "-"));
        }
    }

    // ================================================================== helpers

    private static String rateList()
    {
        StringBuilder sb = new StringBuilder();
        for (int n : RATES) sb.append(sb.length() == 0 ? "" : ", ").append(20 * n).append(" Hz");
        return sb.toString();
    }

    private static PlayerState initial(Trace t)
    {
        PlayerState s = t.rows.get(0).pre.copy();
        applyPrevInput(s, t, 0);
        return s;
    }

    private static PlayerState restState()
    {
        PlayerState s = new PlayerState();
        s.box = Box.forPlayerAt(0.5D, 100.0D, 0.5D);
        s.onGround = true;
        s.motionY = -0.0784000015258789D;
        return s;
    }

    private static PlayerState airborne(double mx, double my, double mz, boolean sprinting)
    {
        PlayerState s = new PlayerState();
        s.box = Box.forPlayerAt(0.5D, 500.0D, 0.5D);
        s.motionX = mx;
        s.motionY = my;
        s.motionZ = mz;
        s.setSprinting(sprinting);
        s.jumpMovementFactor = VanillaModel.airAcceleration(sprinting);
        return s;
    }

    private static PlayerState startState(Scenario sc, int ox, int oy, int oz)
    {
        PlayerState s = new PlayerState();
        s.box = Box.forPlayerAt(ox + sc.startX, oy + sc.startY, oz + sc.startZ);
        s.onGround = true;
        s.motionY = -0.0784000015258789D;
        s.yaw = sc.startYaw;
        return s;
    }

    private static Course courseFor(Trace t)
    {
        for (Scenario s : Scenario.all()) if (s.name.equals(t.scenario)) return s.course(t.originX, t.originY, t.originZ);
        throw new IllegalStateException("unknown scenario " + t.scenario);
    }

    /** movementInput values from the previous tick (not stored in the trace). */
    private static void applyPrevInput(PlayerState s, Trace t, int i)
    {
        if (i == 0)
        {
            s.prevJump = false;
            s.prevSneak = false;
            s.prevForwardHigh = false;
            return;
        }
        TickInput prev = t.rows.get(i - 1).input;
        s.prevJump = prev.jump;
        s.prevSneak = prev.sneak;
        s.prevForwardHigh = prev.moveForward() * (prev.use ? 0.2F : 1.0F) >= 0.8F; // vanilla keeps the item-use-scaled value
        s.usingItem = prev.use;
    }

    private static double stateError(PlayerState a, PlayerState b)
    {
        double[] d = {a.box.minX - b.box.minX, a.box.minY - b.box.minY, a.box.minZ - b.box.minZ,
            a.box.maxX - b.box.maxX, a.box.maxY - b.box.maxY, a.box.maxZ - b.box.maxZ,
            a.motionX - b.motionX, a.motionY - b.motionY, a.motionZ - b.motionZ};
        double e = 0.0D;
        for (double x : d) e = Math.max(e, Math.abs(x));
        return e;
    }

    private static double relError(PlayerState a, PlayerState b)
    {
        double[][] p = {{a.box.minX, b.box.minX}, {a.box.minY, b.box.minY}, {a.box.minZ, b.box.minZ},
            {a.motionX, b.motionX}, {a.motionY, b.motionY}, {a.motionZ, b.motionZ}};
        double e = 0.0D;
        for (double[] q : p) e = Math.max(e, Math.abs(q[0] - q[1]) / Math.max(1.0D, Math.abs(q[0])));
        return e;
    }

    private static double posError(PlayerState a, PlayerState b)
    {
        double dx = a.posX() - b.posX(), dy = a.posY() - b.posY(), dz = a.posZ() - b.posZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean flagsMatch(PlayerState a, PlayerState b)
    {
        return a.onGround == b.onGround && a.collidedHorizontally == b.collidedHorizontally
            && a.collidedVertically == b.collidedVertically && a.sprinting == b.sprinting
            && (int)a.jumpTicks == (int)b.jumpTicks && (int)a.sprintToggleTimer == (int)b.sprintToggleTimer
            && a.sprintingTicksLeft == b.sprintingTicksLeft && a.jumpMovementFactor == b.jumpMovementFactor
            && a.flying == b.flying && a.inWeb == b.inWeb;
    }

    private static String describeDiff(PlayerState sim, PlayerState rec)
    {
        StringBuilder sb = new StringBuilder();
        if (sim.box.minX != rec.box.minX) sb.append(" minX ").append(sim.box.minX).append("!=").append(rec.box.minX);
        if (sim.box.minY != rec.box.minY) sb.append(" minY ").append(sim.box.minY).append("!=").append(rec.box.minY);
        if (sim.box.minZ != rec.box.minZ) sb.append(" minZ ").append(sim.box.minZ).append("!=").append(rec.box.minZ);
        if (sim.motionX != rec.motionX) sb.append(" mx ").append(sim.motionX).append("!=").append(rec.motionX);
        if (sim.motionY != rec.motionY) sb.append(" my ").append(sim.motionY).append("!=").append(rec.motionY);
        if (sim.motionZ != rec.motionZ) sb.append(" mz ").append(sim.motionZ).append("!=").append(rec.motionZ);
        if (sim.onGround != rec.onGround) sb.append(" onGround ").append(sim.onGround).append("!=").append(rec.onGround);
        if (sim.collidedHorizontally != rec.collidedHorizontally) sb.append(" colH");
        if (sim.sprinting != rec.sprinting) sb.append(" sprinting ").append(sim.sprinting).append("!=").append(rec.sprinting);
        if ((int)sim.jumpTicks != (int)rec.jumpTicks) sb.append(" jumpTicks ").append(sim.jumpTicks).append("!=").append(rec.jumpTicks);
        if ((int)sim.sprintToggleTimer != (int)rec.sprintToggleTimer) sb.append(" sprintToggle ").append(sim.sprintToggleTimer).append("!=").append(rec.sprintToggleTimer);
        if (sim.sprintingTicksLeft != rec.sprintingTicksLeft) sb.append(" sprintLeft ").append(sim.sprintingTicksLeft).append("!=").append(rec.sprintingTicksLeft);
        if (sim.jumpMovementFactor != rec.jumpMovementFactor) sb.append(" jmf ").append(sim.jumpMovementFactor).append("!=").append(rec.jumpMovementFactor);
        return sb.toString().trim();
    }

    private static List<Trace> loadTraces(File dir) throws Exception
    {
        List<Trace> list = new ArrayList<Trace>();
        for (Scenario s : Scenario.all())
        {
            File f = new File(dir, s.name + ".csv");
            if (f.isFile()) list.add(Trace.read(f));
        }
        System.out.println("Loaded " + list.size() + " baseline traces from " + dir.getAbsolutePath());
        return list;
    }

    private static void header(String title)
    {
        System.out.println();
        System.out.println("=== " + title);
    }

    private static void check(boolean ok, String what)
    {
        if (!ok)
        {
            ++failures;
            System.out.println("  FAIL: " + what);
        }
    }
}
