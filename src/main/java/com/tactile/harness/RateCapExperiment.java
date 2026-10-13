package com.tactile.harness;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.tactile.core.Box;
import com.tactile.core.Course;
import com.tactile.core.PlayerState;
import com.tactile.core.Scenario;
import com.tactile.core.SubstepModel;
import com.tactile.core.TickInput;
import com.tactile.core.Trace;
import com.tactile.core.VanillaModel;

/**
 * Upper-rate experiment (gradlew rateCapExperiment). Raises the substep rate far past the configured
 * cap and looks for error that grows with rate instead of shrinking: floating-point cancellation in the
 * flow coefficients at tiny h, accumulation over long runs, or event-location trouble. For each rate:
 * <ol>
 * <li>free-flight error vs the vanilla tick map (should stay ~1e-14);</li>
 * <li>corpus distance to the next higher rate (should shrink or stay ~0);</li>
 * <li>long run: 6000 ticks (5 minutes) of held sprint-jumping vs vanilla (arc parity: should stay ~0);</li>
 * <li>offline cost per vanilla tick.</li>
 * </ol>
 */
public final class RateCapExperiment
{
    public static void main(String[] args) throws Exception
    {
        File dir = new File(args.length > 0 ? args[0] : "run/hrm-baseline");
        List<Trace> traces = new ArrayList<Trace>();
        for (Scenario s : Scenario.corpus())
        {
            File f = new File(dir, s.name + ".csv");
            if (f.isFile()) traces.add(Trace.read(f));
        }
        int[] steps = {12, 24, 48, 96, 192, 384, 768};
        System.out.println(String.format(Locale.ROOT, "%8s %14s %16s %18s %16s", "rate Hz", "free-flight", "corpus vs 2x", "6000-tick chain", "us/tick"));
        for (int n : steps)
        {
            double free = freeFlight(n);
            double next = corpusDistance(traces, n, n * 2);
            double chain = longChain(n);
            double cost = cost(traces, n);
            System.out.println(String.format(Locale.ROOT, "%8d %14.3g %16.3g %18.3g %16.1f", 20 * n, free, next, chain, cost));
        }
    }

    private static double freeFlight(int n)
    {
        Course empty = new Course();
        double worst = 0.0D;
        for (String k : new String[] {"W---J-R", "-A-----", "W-S----"})
        {
            boolean sprint = k.charAt(0) == 'W' && k.charAt(2) != 'S' && k.charAt(6) == 'R';
            PlayerState a = new PlayerState();
            a.box = Box.forPlayerAt(0.5D, 500.0D, 0.5D);
            a.motionX = 0.31D;
            a.motionY = 0.2D;
            a.motionZ = -0.17D;
            a.setSprinting(sprint);
            a.jumpMovementFactor = VanillaModel.airAcceleration(sprint);
            PlayerState b = a.copy();
            SubstepModel m = new SubstepModel(n, SubstepModel.Contacts.GRID);
            for (int i = 0; i < 200; ++i)
            {
                TickInput in = TickInput.fromKeyString(k, 17.0F + 3.0F * i, 0.0F);
                VanillaModel.tick(a, in, empty);
                m.tick(b, in, empty);
                worst = Math.max(worst, Math.abs(a.box.minY - b.box.minY) / Math.max(1.0D, Math.abs(a.box.minY)));
                worst = Math.max(worst, Math.abs(a.box.minX - b.box.minX) / Math.max(1.0D, Math.abs(a.box.minX)));
            }
        }
        return worst;
    }

    private static double corpusDistance(List<Trace> traces, int n1, int n2)
    {
        double worst = 0.0D;
        for (Trace t : traces)
        {
            Course c = null;
            for (Scenario s : Scenario.all()) if (s.name.equals(t.scenario)) c = s.course(t.originX, t.originY, t.originZ);
            PlayerState a = t.rows.get(0).pre.copy(), b = a.copy();
            SubstepModel m1 = new SubstepModel(n1, SubstepModel.Contacts.GRID), m2 = new SubstepModel(n2, SubstepModel.Contacts.GRID);
            for (Trace.Row r : t.rows)
            {
                m1.tick(a, r.input, c);
                m2.tick(b, r.input, c);
                double dx = a.posX() - b.posX(), dy = a.posY() - b.posY(), dz = a.posZ() - b.posZ();
                worst = Math.max(worst, Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
        }
        return worst;
    }

    /** 6000 ticks of held forward+sprint+jump along a long floor, turning slowly; distance from vanilla. */
    private static double longChain(int n)
    {
        Course floor = new Course().fill(-400, 99, -400, 400, 99, 400, Course.Shape.FULL);
        PlayerState v = new PlayerState();
        v.box = Box.forPlayerAt(0.5D, 100.0D, 0.5D);
        v.onGround = true;
        v.motionY = -0.0784000015258789D;
        PlayerState s = v.copy();
        SubstepModel m = new SubstepModel(n, SubstepModel.Contacts.GRID);
        double worst = 0.0D;
        for (int i = 0; i < 6000; ++i)
        {
            TickInput in = TickInput.fromKeyString("W---J-R", (float)(i * 0.25D % 360.0D), 0.0F);
            VanillaModel.tick(v, in, floor);
            m.tick(s, in, floor);
            double dx = v.posX() - s.posX(), dy = v.posY() - s.posY(), dz = v.posZ() - s.posZ();
            worst = Math.max(worst, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return worst;
    }

    private static double cost(List<Trace> traces, int n)
    {
        long ticks = 0, t0 = 0;
        for (int rep = 0; rep < 2; ++rep)
        {
            ticks = 0;
            t0 = System.nanoTime();
            for (Trace t : traces)
            {
                Course c = null;
                for (Scenario s : Scenario.all()) if (s.name.equals(t.scenario)) c = s.course(t.originX, t.originY, t.originZ);
                PlayerState a = t.rows.get(0).pre.copy();
                SubstepModel m = new SubstepModel(n, SubstepModel.Contacts.GRID);
                for (Trace.Row r : t.rows) m.tick(a, r.input, c);
                ticks += t.rows.size();
            }
        }
        return (System.nanoTime() - t0) / 1e3 / ticks;
    }
}
