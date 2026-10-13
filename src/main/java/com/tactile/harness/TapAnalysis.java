package com.tactile.harness;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.tactile.core.Box;
import com.tactile.core.Course;
import com.tactile.core.InputTimeline;
import com.tactile.core.PlayerState;
import com.tactile.core.SubstepModel;
import com.tactile.core.TickInput;
import com.tactile.core.VanillaModel;

/**
 * Analysis of the end-to-end tap test (run/hrm-input/taps.csv). For each mode and tap length it gives
 * the measured distance (mean, min, max over random phases) next to the offline model's prediction
 * for an exactly timed tap at the same physics rate. Vanilla's prediction is the expected value over
 * phases, since a tap is seen only if it spans a tick boundary.
 */
public final class TapAnalysis
{
    public static void main(String[] args) throws Exception
    {
        File f = new File(args.length > 0 ? args[0] : "run/hrm-input/taps.csv");
        Map<String, List<double[]>> byKey = new LinkedHashMap<String, List<double[]>>();
        BufferedReader r = new BufferedReader(new FileReader(f));
        String line;
        int inactive = 0;
        while ((line = r.readLine()) != null)
        {
            if (line.startsWith("mode")) continue;
            String[] p = line.split(",");
            String mode = p[0].equals("VANILLA") ? "VANILLA" : p[0] + " " + p[1] + "Hz";
            if (!p[0].equals("VANILLA") && p[3].equals("0")) ++inactive;
            String key = mode + "|" + p[2];
            List<double[]> l = byKey.get(key);
            if (l == null) byKey.put(key, l = new ArrayList<double[]>());
            // with the engine's record of the tap (0.7.2+): start time and release time in ticks
            l.add(p.length >= 7 ? new double[] {Double.parseDouble(p[4]), Double.parseDouble(p[5]), Double.parseDouble(p[6])} : new double[] {Double.parseDouble(p[4])});
        }
        r.close();
        System.out.println("Tap test: distance moved by one tap of the forward key (blocks), measured in-game vs offline model");
        if (inactive > 0) System.out.println("  WARNING: " + inactive + " high-res taps ran while the engine was inactive (fallback)");
        System.out.println(String.format(Locale.ROOT, "  %-14s %6s %8s %8s %8s %10s", "mode", "tap ms", "mean", "min", "max", "model"));
        for (Map.Entry<String, List<double[]>> e : byKey.entrySet())
        {
            String[] k = e.getKey().split("\\|");
            int ms = Integer.parseInt(k[1]);
            double sum = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (double[] v : e.getValue())
            {
                sum += v[0];
                min = Math.min(min, v[0]);
                max = Math.max(max, v[0]);
            }
            double model = k[0].equals("VANILLA") ? vanillaExpected(ms)
                : hrExact(ms, Integer.parseInt(k[0].replaceAll("\\D", "")) / 20, k[0].startsWith("EXACT"));
            System.out.println(String.format(Locale.ROOT, "  %-14s %6d %8.4f %8.4f %8.4f %10.4f", k[0], ms, sum / e.getValue().size(), min, max, model));
        }
        perTap(byKey);
    }

    /**
     * Engine modes with the engine's record of each tap: the model run with that tap's own start phase and hold length.
     * The residual (measured minus that model) separates the physics from the tap timing.
     */
    private static void perTap(Map<String, List<double[]>> byKey)
    {
        boolean header = false;
        for (Map.Entry<String, List<double[]>> e : byKey.entrySet())
        {
            String[] k = e.getKey().split("\\|");
            if (k[0].equals("VANILLA")) continue;
            int n = Integer.parseInt(k[0].replaceAll("\\D", "")) / 20, ms = Integer.parseInt(k[1]);
            boolean exact = k[0].startsWith("EXACT");
            double holdSum = 0, resSum = 0, resMax = 0;
            int count = 0;
            for (double[] v : e.getValue())
            {
                if (v.length < 3 || Double.isNaN(v[1]) || Double.isNaN(v[2]) || v[2] <= v[1]) continue;
                double start = 2.0D + (v[1] - Math.floor(v[1])), hold = v[2] - v[1];
                double res = v[0] - tap(start, hold, n, exact);
                holdSum += hold * 50.0D;
                resSum += res;
                resMax = Math.max(resMax, Math.abs(res));
                ++count;
            }
            if (count == 0) continue;
            if (!header)
            {
                System.out.println("Per tap, as the engine received it: hold length vs requested, and measured minus model for that exact tap (blocks)");
                System.out.println(String.format(Locale.ROOT, "  %-14s %6s %10s %12s %12s", "mode", "tap ms", "hold ms", "mean resid", "max |resid|"));
                header = true;
            }
            System.out.println(String.format(Locale.ROOT, "  %-14s %6d %10.2f %12.5f %12.5f", k[0], ms, holdSum / count, resSum / count, resMax));
        }
    }

    /** Forward held from {@code start} for {@code hold} ticks (GRID contacts). */
    private static double tap(double start, double hold, int n, boolean exact)
    {
        Course c = floor();
        InputTimeline tl = new InputTimeline().add(0.0D, TickInput.fromKeyString("-------", 0.0F, 0.0F))
            .add(start, TickInput.fromKeyString("W------", 0.0F, 0.0F)).add(start + hold, TickInput.fromKeyString("-------", 0.0F, 0.0F));
        PlayerState s = rest();
        SubstepModel m = new SubstepModel(n, SubstepModel.Contacts.GRID, exact);
        for (long t = 0; t < 40; ++t)
        {
            if (exact) m.tickExact(s, tl, t, c);
            else m.tick(s, tl, t, c);
        }
        return s.posZ() - 0.5D;
    }

    private static Course floor()
    {
        return new Course().fill(-5, 99, -5, 5, 99, 50, Course.Shape.FULL);
    }

    private static PlayerState rest()
    {
        PlayerState s = new PlayerState();
        s.box = Box.forPlayerAt(0.5D, 100.0D, 0.5D);
        s.onGround = true;
        s.motionY = -0.0784000015258789D;
        return s;
    }

    /** High-res: forward held for exactly ms milliseconds starting mid-tick, GRID contacts (HIGH_RES or EXACT timing). */
    private static double hrExact(int ms, int n, boolean exact)
    {
        Course c = floor();
        double start = 2.37D, end = start + ms / 50.0D;
        InputTimeline tl = new InputTimeline().add(0.0D, TickInput.fromKeyString("-------", 0.0F, 0.0F))
            .add(start, TickInput.fromKeyString("W------", 0.0F, 0.0F)).add(end, TickInput.fromKeyString("-------", 0.0F, 0.0F));
        PlayerState s = rest();
        SubstepModel m = new SubstepModel(n, SubstepModel.Contacts.GRID, exact);
        for (long t = 0; t < 40; ++t)
        {
            if (exact) m.tickExact(s, tl, t, c);
            else m.tick(s, tl, t, c);
        }
        return s.posZ() - 0.5D;
    }

    /** Vanilla: forward is seen for k whole ticks, where k depends on the phase; expected value over phases. */
    private static double vanillaExpected(int ms)
    {
        double d = ms / 50.0D; // tap length in ticks
        int base = (int)Math.floor(d);
        double frac = d - base;
        // spans base+1 tick boundaries with probability frac, else base
        return frac * vanillaTicks(base + 1) + (1.0D - frac) * vanillaTicks(base);
    }

    private static double vanillaTicks(int k)
    {
        if (k == 0) return 0.0D;
        Course c = floor();
        PlayerState s = rest();
        for (int t = 0; t < 40; ++t) VanillaModel.tick(s, TickInput.fromKeyString(t < k ? "W------" : "-------", 0.0F, 0.0F), c);
        return s.posZ() - 0.5D;
    }
}
