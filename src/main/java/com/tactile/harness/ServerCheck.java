package com.tactile.harness;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.tactile.core.Box;
import com.tactile.core.Course;
import com.tactile.core.InputTimeline;
import com.tactile.core.PlayerState;
import com.tactile.core.Scenario;
import com.tactile.core.ServerShadow;
import com.tactile.core.SubstepModel;
import com.tactile.core.TickInput;
import com.tactile.core.Trace;
import com.tactile.core.VanillaModel;

/**
 * How a prediction-based server movement check sees the packets TACTILE sends (gradlew serverCheck). The "server" is
 * {@link ServerShadow}: from its own state it tries every vanilla input for each tick and keeps the prediction closest
 * to the reported position (as GrimAC, UNCP and reportedly Hypixel's check do). Offsets of 0.001 blocks or more are
 * flagged by such checks; 0.1 or more in one tick is an immediate setback in GrimAC's defaults.
 * <ol>
 * <li>Packet onGround flag (ServerShadow.packetOnGround, used by the live engine) against vanilla's own flag, in every
 * recorded scenario with keys at tick starts. Must agree in every tick.</li>
 * <li>Offsets and ground-flag mismatches with keys pressed partway through a tick (informational).</li>
 * <li>Option "server-safe slime and knockback": a mid-tick sprint-jump onto slime, with the live engine's order of
 * steps. From the switch to vanilla tick timing on, every offset must be 0 and every flag must match.</li>
 * <li>Knockback arriving mid-tick, applied on arrival (default) and from the last packet's tick, rewound and replayed (option).
 * With the option, every
 * offset must be 0.</li>
 * <li>The legacy setting "instant air control" against vanilla on the recorded scenarios (informational: it brings back
 * the 1.0.x differences).</li>
 * </ol>
 */
public final class ServerCheck
{
    static final double FLAG = 1e-3D, SETBACK = 0.1D;
    static int failures;

    public static void main(String[] args) throws Exception
    {
        File dir = new File(args.length > 0 ? args[0] : "run/hrm-baseline");
        List<Trace> traces = new ArrayList<Trace>();
        for (Scenario s : Scenario.all())
        {
            File f = new File(dir, s.name + ".csv");
            if (f.isFile()) traces.add(Trace.read(f));
        }
        System.out.println("Loaded " + traces.size() + " recordings from " + dir.getAbsolutePath());
        groundFlagCheck(traces);
        midTickReport();
        slimeSafeCheck();
        knockbackCheck();
        legacyAirReport(traces);
        for (int i = 1; i < args.length; ++i) recordedPackets(new File(args[i]));
        System.out.println(failures == 0 ? "ALL SERVER CHECKS PASSED" : failures + " SERVER CHECK(S) FAILED");
        if (failures > 0) System.exit(1);
    }

    static void check(boolean ok, String what)
    {
        if (!ok)
        {
            ++failures;
            System.out.println("  FAIL: " + what);
        }
    }

    static TickInput k(String keys)
    {
        return TickInput.fromKeyString(keys, 0.0F, 0.0F);
    }

    static Course courseFor(Trace t)
    {
        for (Scenario s : Scenario.all()) if (s.name.equals(t.scenario)) return s.course(t.originX, t.originY, t.originZ);
        throw new IllegalStateException("unknown scenario " + t.scenario);
    }

    // ---------------------------------------------------------------- 1

    static void groundFlagCheck(List<Trace> traces)
    {
        System.out.println("=== 1. Packet onGround flag (vanilla's rule from the movement since the last packet) vs vanilla, keys at tick starts");
        check(!traces.isEmpty(), "no recordings found; run the client with HRMOVE_BASELINE=1 first");
        int ticks = 0, differ = 0;
        for (Trace t : traces)
        {
            Course c = courseFor(t);
            PlayerState v = t.rows.get(0).pre.copy();
            v.prevJump = v.prevSneak = v.prevForwardHigh = false;
            PlayerState e = v.copy();
            SubstepModel m = new SubstepModel(48, SubstepModel.Contacts.GRID, true);
            Box last = e.box;
            int d = 0;
            for (Trace.Row r : t.rows)
            {
                VanillaModel.tick(v, r.input, c);
                m.tick(e, r.input, c);
                boolean flag = ServerShadow.packetOnGround(last, e.box, e.packetFreeDy);
                e.packetFreeDy = 0.0D;
                last = e.box;
                ++ticks;
                if (flag != v.onGround) ++d;
            }
            if (d > 0) System.out.println(String.format(Locale.ROOT, "  %-22s %d ticks differ", t.scenario, d));
            differ += d;
        }
        System.out.println(String.format(Locale.ROOT, "  %d scenarios, %d ticks: flag differs from vanilla's in %d", traces.size(), ticks, differ));
        check(differ == 0, "packet onGround flag differs from vanilla's");
    }

    // ---------------------------------------------------------------- 2

    interface Run
    {
        Course course();
        PlayerState start();
        InputTimeline keys(double phase);
        int ticks();
    }

    static List<Object[]> runs()
    {
        List<Object[]> l = new ArrayList<Object[]>();
        l.add(new Object[] {"flat: sprint, bunny-hop with jump held", new Run()
        {
            public Course course() { return new Course().fill(-3, 99, -3, 3, 99, 200, Course.Shape.FULL); }
            public PlayerState start() { return EdgeLandingTest.rest(0.5D); }
            public InputTimeline keys(double p)
            {
                return new InputTimeline().add(0.0D, k("-------")).add(3.0D + p, k("W-----R")).add(20.0D + p, k("W---J-R"))
                    .add(60.0D + p, k("W-----R")).add(70.0D + p, k("-------"));
            }
            public int ticks() { return 90; }
        }});
        l.add(new Object[] {"flat: horizontal keys only, no jumps", new Run()
        {
            public Course course() { return new Course().fill(-30, 99, -3, 30, 99, 200, Course.Shape.FULL); }
            public PlayerState start() { return EdgeLandingTest.rest(0.5D); }
            public InputTimeline keys(double p)
            {
                return new InputTimeline().add(0.0D, k("-------")).add(2.0D + p, k("W------")).add(12.0D + p, k("W-----R")).add(25.0D + p, k("WA----R"))
                    .add(32.0D + p, k("W--D--R")).add(40.0D + p, k("W------")).add(48.0D + p, k("-------")).add(55.0D + p, k("--S----")).add(62.0D + p, k("-------"));
            }
            public int ticks() { return 75; }
        }});
        l.add(new Object[] {"slime: sprint-jump from stone onto slime, keep running", new Run()
        {
            public Course course() { return slimeCourse(); }
            public PlayerState start() { return EdgeLandingTest.rest(0.5D); }
            public InputTimeline keys(double p) { return slimeKeys(p); }
            public int ticks() { return 60; }
        }});
        return l;
    }

    static Course slimeCourse()
    {
        return new Course().fill(-3, 99, -3, 3, 99, 4, Course.Shape.FULL).fill(-3, 98, 5, 3, 98, 80, Course.Shape.SLIME);
    }

    static InputTimeline slimeKeys(double p)
    {
        return new InputTimeline().add(0.0D, k("-------")).add(1.0D + p, k("W-----R")).add(9.0D + p, k("W---J-R")).add(11.0D + p, k("W-----R"));
    }

    static final class Stats
    {
        int packets, flagged, setbacks, flagMismatch;
        double max;

        void add(double off)
        {
            ++packets;
            max = Math.max(max, off);
            if (off >= FLAG) ++flagged;
            if (off >= SETBACK) ++setbacks;
        }

        String line()
        {
            return String.format(Locale.ROOT, "packets %3d | max offset %.4f | >=0.001: %3d | >=0.1: %2d | onGround flag differs from server's: %d",
                packets, max, flagged, setbacks, flagMismatch);
        }
    }

    /** The server's onGround for this packet: vanilla's collision result of its best prediction. */
    static boolean serverGround(ServerShadow sh)
    {
        return sh.state().onGround;
    }

    static void midTickReport()
    {
        System.out.println("=== 2. Keys partway through a tick (EXACT, default settings): offsets from the server's prediction");
        for (Object[] r : runs())
        {
            Run run = (Run)r[1];
            System.out.println("  " + r[0]);
            for (double p : new double[] {0.0D, 0.1D, 0.3D, 0.5D, 0.7D, 0.9D})
            {
                Course c = run.course();
                InputTimeline tl = run.keys(p);
                PlayerState s = run.start();
                ServerShadow sh = new ServerShadow();
                sh.reset(s);
                SubstepModel m = new SubstepModel(48, SubstepModel.Contacts.GRID, true);
                Box last = s.box;
                Stats st = new Stats();
                for (long t = 0; t < run.ticks(); ++t)
                {
                    m.tickExact(s, tl, t, c);
                    boolean flag = ServerShadow.packetOnGround(last, s.box, s.packetFreeDy);
                    s.packetFreeDy = 0.0D;
                    last = s.box;
                    PlayerState sent = s.copy();
                    sent.onGround = flag;
                    st.add(sh.observe(sent, c, 0.0F, 0.0F));
                    if (flag != serverGround(sh)) ++st.flagMismatch;
                }
                System.out.println(String.format(Locale.ROOT, "    keys at +%.1f tick: %s", p, st.line()));
            }
        }
    }

    // ---------------------------------------------------------------- 3

    static void slimeSafeCheck()
    {
        System.out.println("=== 3. Option \"server-safe slime and knockback\": mid-tick sprint-jump onto slime, live engine's order of steps");
        for (double p : new double[] {0.1D, 0.3D, 0.5D, 0.7D, 0.9D})
        {
            Course c = slimeCourse();
            InputTimeline tl = slimeKeys(p);
            PlayerState s = EdgeLandingTest.rest(0.5D);
            ServerShadow sh = new ServerShadow();
            sh.reset(s);
            SubstepModel m = new SubstepModel(48, SubstepModel.Contacts.GRID, true);
            Box last = s.box;
            boolean safe = false;
            long switchedAt = -1;
            Stats before = new Stats(), after = new Stats();
            for (long t = 0; t < 60; ++t)
            {
                // the engine's tick: exact key timing, or vanilla tick timing while the option holds it
                if (safe) m.tick(s, tl.stepped((double)t, 1.0D, true), c);
                else m.tickExact(s, tl, t, c);
                // the packet at this game tick (LiveEngine.handleMoveImpl)
                boolean flag = ServerShadow.packetOnGround(last, s.box, s.packetFreeDy);
                s.packetFreeDy = 0.0D;
                last = s.box;
                PlayerState sent = s.copy();
                sent.onGround = flag;
                double off = sh.observe(sent, c, 0.0F, 0.0F);
                Stats st = safe ? after : before;
                st.add(off);
                if (flag != serverGround(sh)) ++st.flagMismatch;
                // LiveEngine.serverSafety
                boolean slime = ServerShadow.slimeBelow(s, c, 1.5D + 3.0D * Math.abs(s.motionY));
                if (!safe && slime)
                {
                    safe = true;
                    switchedAt = t + 1;
                    PlayerState sv = sh.state();
                    m.overrideVelocity(s, c, tl.at((double)t + 1.0D - 1e-9D), sv.motionX, sv.motionY, sv.motionZ);
                }
                else if (safe && !slime && s.onGround) safe = false;
            }
            System.out.println(String.format(Locale.ROOT, "  jump at +%.1f: before the switch %s", p, before.line()));
            System.out.println(String.format(Locale.ROOT, "             from tick %d on  %s", switchedAt, after.line()));
            check(switchedAt > 0, "the switch to vanilla tick timing never happened (jump at +" + p + ")");
            check(after.max < 1e-9D && after.flagMismatch == 0, "offsets or flag mismatches after the switch (jump at +" + p + ")");
        }
    }

    // ---------------------------------------------------------------- 4

    static void knockbackCheck()
    {
        System.out.println("=== 4. Knockback arriving partway through a tick (sprinting, keys at tick starts)");
        double[] kb = {0.0D, 0.36D, -0.42D};
        for (double p : new double[] {0.1D, 0.5D, 0.9D})
            for (int option = 0; option < 2; ++option)
            {
                Course c = new Course().fill(-10, 99, -10, 10, 99, 200, Course.Shape.FULL);
                TickInput in = k("W-----R");
                PlayerState s = EdgeLandingTest.rest(0.5D);
                ServerShadow sh = new ServerShadow();
                sh.reset(s);
                SubstepModel m = new SubstepModel(48, SubstepModel.Contacts.GRID, true);
                Box last = s.box;
                Stats st = new Stats();
                for (long t = 0; t < 40; ++t)
                {
                    if (t == 15)
                    {
                        // arrives at 15 + p: the server sets the velocity (the shadow takes it at arrival)
                        sh.setVelocity(kb[0], kb[1], kb[2]);
                        if (option == 0)
                        {
                            // applied on arrival (the default): a full grid tick starts at 15 + p
                            int at = (int)Math.round(p * 48);
                            for (int j = 0; j < 48; ++j)
                            {
                                if (j == at) m.overrideVelocity(s, c, in, kb[0], kb[1], kb[2]);
                                m.substep(s, c, new TickInput[] {in}, new double[] {0.0D}, 1, j == 0);
                            }
                        }
                        else
                        {
                            // the option: from the last packet's boundary (15), rewound and replayed, so the next packet
                            // already contains the knockback's first tick, as vanilla's does
                            m.overrideVelocity(s, c, in, kb[0], kb[1], kb[2]);
                            m.tick(s, in, c);
                        }
                    }
                    else m.tick(s, in, c);
                    boolean flag = ServerShadow.packetOnGround(last, s.box, s.packetFreeDy);
                    s.packetFreeDy = 0.0D;
                    last = s.box;
                    PlayerState sent = s.copy();
                    sent.onGround = flag;
                    double off = sh.observe(sent, c, 0.0F, 0.0F);
                    if (t >= 15) st.add(off);
                    if (t >= 15 && flag != serverGround(sh)) ++st.flagMismatch;
                }
                System.out.println(String.format(Locale.ROOT, "  arrives at +%.1f, %-22s %s", p, option == 0 ? "applied on arrival:" : "from the last tick:", st.line()));
                if (option == 1) check(st.max < 1e-9D && st.flagMismatch == 0, "knockback from the last tick does not match the server (arrival +" + p + ")");
            }
    }

    // ---------------------------------------------------------------- 5

    static void legacyAirReport(List<Trace> traces)
    {
        System.out.println("=== 5. Legacy setting \"instant air control\": EXACT vs vanilla on the recordings, keys at tick starts (max distance, blocks)");
        StringBuilder sb = new StringBuilder();
        int differing = 0;
        for (Trace t : traces)
        {
            Course c = courseFor(t);
            PlayerState v = t.rows.get(0).pre.copy();
            v.prevJump = v.prevSneak = v.prevForwardHigh = false;
            PlayerState a = v.copy(), b = v.copy();
            SubstepModel ma = new SubstepModel(48, SubstepModel.Contacts.GRID, true), mb = new SubstepModel(48, SubstepModel.Contacts.GRID, true);
            mb.instantAirControl = true;
            double da = 0.0D, db = 0.0D;
            for (Trace.Row r : t.rows)
            {
                VanillaModel.tick(v, r.input, c);
                ma.tick(a, r.input, c);
                mb.tick(b, r.input, c);
                da = Math.max(da, dist(v, a));
                db = Math.max(db, dist(v, b));
            }
            if (da > 1e-9D || db > 1e-9D)
            {
                ++differing;
                sb.append(String.format(Locale.ROOT, "  %-22s default %.4f | legacy %.4f%n", t.scenario, da, db));
            }
        }
        System.out.print(sb);
        System.out.println("  scenarios differing from vanilla: " + differing + " of " + traces.size());
    }

    // ---------------------------------------------------------------- 6

    /**
     * In-game recordings (HRMOVE_BASELINE with the engine): each row's state after the tick is what the client sent
     * (position and onGround flag). Feeds those packets to the server emulation. Informational.
     */
    static void recordedPackets(File dir) throws Exception
    {
        System.out.println("=== 6. Packets recorded in the game: " + dir.getName());
        for (Scenario sc : Scenario.all())
        {
            File f = new File(dir, sc.name + ".csv");
            if (!f.isFile()) continue;
            Trace t = Trace.read(f);
            Course c = courseFor(t);
            ServerShadow sh = new ServerShadow();
            PlayerState start = t.rows.get(0).pre.copy();
            start.prevJump = start.prevSneak = start.prevForwardHigh = false;
            sh.reset(start);
            Stats st = new Stats();
            for (Trace.Row r : t.rows)
            {
                PlayerState sent = r.post.copy();
                st.add(sh.observe(sent, c, r.input.yaw, r.input.pitch));
                if (sent.onGround != serverGround(sh)) ++st.flagMismatch;
            }
            System.out.println(String.format(Locale.ROOT, "  %-22s %s", sc.name, st.line()));
        }
    }

    static double dist(PlayerState a, PlayerState b)
    {
        double dx = a.posX() - b.posX(), dy = a.posY() - b.posY(), dz = a.posZ() - b.posZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
