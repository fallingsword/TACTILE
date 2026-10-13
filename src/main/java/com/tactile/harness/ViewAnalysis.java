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

/**
 * Analysis of run/hrm-view/frames.csv (ViewProbe, gradlew viewAnalysis).
 * <p>Part J (jump): per mode, the time from the OS key press to the first drawn rise, the drawn height 25/50/100 ms
 * after the press, and the display lag: the time shift that best fits the drawn heights to the exact jump arc started
 * at the press. For engine modes also the residual on the engine's own clock (drawn height vs the arc at
 * frame time minus the engine's jump time) and the input delay (engine jump time minus the press, on the engine clock).
 * <p>Part S (standing on slime after a sneak release): how much the drawn feet height, eye height and view-bobbing
 * terms move.
 */
public final class ViewAnalysis
{
    private static final int N = 96; // the reference arc: EXACT at 1920 Hz
    private static double[] arc;     // height at each 1/N tick after the jump

    public static void main(String[] args) throws Exception
    {
        File f = new File(args.length > 0 ? args[0] : "run/hrm-view/frames.csv");
        buildArc();
        Map<String, List<double[]>> jumps = new LinkedHashMap<String, List<double[]>>(), stands = new LinkedHashMap<String, List<double[]>>();
        BufferedReader r = new BufferedReader(new FileReader(f));
        String line;
        while ((line = r.readLine()) != null)
        {
            if (line.startsWith("mode")) continue;
            String[] p = line.split(",");
            String key = (p[0].equals("VANILLA") ? "VANILLA" : p[0] + " " + p[1]) + "|" + p[3];
            double[] v = new double[p.length - 4];
            for (int i = 4; i < p.length; ++i) v[i - 4] = Double.parseDouble(p[i]);
            Map<String, List<double[]>> m = p[2].equals("J") ? jumps : stands;
            List<double[]> l = m.get(key);
            if (l == null) m.put(key, l = new ArrayList<double[]>());
            l.add(v); // ms, frameTau, jumpTau, y, eye, bobYaw, bobPitch, walked, onGround
        }
        r.close();
        jumpReport(jumps);
        standReport(stands);
        File presses = new File(f.getParentFile(), "presses.csv");
        if (presses.exists()) pressReport(presses);
    }

    /**
     * Each jump press through the engine's key source: delivery (OS press to the key thread), wait (key thread to the
     * game thread collecting it), moved (how much later than its own time it entered the input timeline), and the jump
     * relative to that entry. Split by whether the engine had just restarted.
     */
    private static void pressReport(File file) throws Exception
    {
        System.out.println("=== Jump presses through the key source (ms): delivery, wait, moved, jump - entered");
        Map<String, double[]> agg = new LinkedHashMap<String, double[]>(); // n, sum/max per component
        BufferedReader r = new BufferedReader(new FileReader(file));
        String line;
        while ((line = r.readLine()) != null)
        {
            if (line.startsWith("mode")) continue;
            String[] p = line.split(",");
            if (p[5].equals("NaN")) continue;
            double press = Double.parseDouble(p[4]), received = Double.parseDouble(p[5]), collected = Double.parseDouble(p[6]);
            double eventTau = Double.parseDouble(p[7]), enteredTau = Double.parseDouble(p[8]), jumpTau = Double.parseDouble(p[9]);
            double[] c = {(received - press) / 1e6, (collected - received) / 1e6, (enteredTau - eventTau) * 50.0D, (jumpTau - enteredTau) * 50.0D};
            String key = p[0] + " " + p[1] + (p[3].equals("1") ? " restarted" : " normal");
            double[] a = agg.get(key);
            if (a == null) agg.put(key, a = new double[9]);
            a[0] += 1;
            for (int i = 0; i < 4; ++i)
            {
                a[1 + i] += c[i];
                a[5 + i] = Math.max(a[5 + i], c[i]);
            }
            if (c[0] > 2.0D || c[1] > 2.0D || c[2] > 2.0D || Math.abs(c[3]) > 2.0D)
                System.out.println(String.format(Locale.ROOT, "  slow: %s trial %s: delivery %.2f, wait %.2f, moved %.2f, jump-entered %.2f (event phase %.3f)%s",
                    key, p[2], c[0], c[1], c[2], c[3], eventTau - Math.floor(eventTau), p.length >= 12 && !p[10].equals("NaN")
                        ? String.format(Locale.ROOT, "; handed over %.2f after waking; previous drain %.2f after the hand-over",
                            (Double.parseDouble(p[10]) - received) / 1e6, (Double.parseDouble(p[11]) - Double.parseDouble(p[10])) / 1e6) : "")
                    + (p.length >= 17 && !p[12].equals("NaN") ? String.format(Locale.ROOT, "; foreground check done %.2f, event read done %.2f after waking; "
                        + "time-critical %s; GCs in trial %s (%s ms)", (Double.parseDouble(p[12]) - received) / 1e6, (Double.parseDouble(p[13]) - received) / 1e6,
                        p[14], p[15], p[16]) : ""));
        }
        r.close();
        System.out.println(String.format(Locale.ROOT, "  %-24s %4s %17s %17s %17s %17s", "mode", "n", "delivery mean/max", "wait mean/max", "moved mean/max", "jump-ent mean/max"));
        for (Map.Entry<String, double[]> e : agg.entrySet())
        {
            double[] a = e.getValue();
            System.out.println(String.format(Locale.ROOT, "  %-24s %4.0f %8.2f/%8.2f %8.2f/%8.2f %8.2f/%8.2f %8.2f/%8.2f", e.getKey(), a[0],
                a[1] / a[0], a[5], a[2] / a[0], a[6], a[3] / a[0], a[7], a[4] / a[0], a[8]));
        }
    }

    private static void buildArc()
    {
        Course c = new Course().fill(-5, 99, -5, 5, 99, 5, Course.Shape.FULL);
        PlayerState s = new PlayerState();
        s.box = Box.forPlayerAt(0.5D, 100.0D, 0.5D);
        s.onGround = true;
        s.motionY = -0.0784000015258789D;
        SubstepModel m = new SubstepModel(N, SubstepModel.Contacts.GRID, true);
        InputTimeline tl = new InputTimeline().add(0.0D, TickInput.fromKeyString("----J--", 0.0F, 0.0F)).add(2.0D, TickInput.fromKeyString("-------", 0.0F, 0.0F));
        arc = new double[20 * N + 1];
        arc[0] = 0.0D;
        int i = 1;
        for (long t = 0; t < 20; ++t)
            for (int k = 0; k < N; ++k)
            {
                double tau = m.substepTau(t, k), next = m.substepTau(t, k + 1);
                m.substep(s, c, new TickInput[] {tl.at(tau)}, new double[] {0.0D}, 1, k == 0);
                arc[i++] = s.posY() - 100.0D;
                if (next >= 20.0D) break;
            }
    }

    /** Exact arc height {@code ms} after the jump (0 before it). */
    private static double arcAt(double ms)
    {
        if (ms <= 0.0D) return 0.0D;
        double x = ms / 50.0D * N;
        int i = (int)Math.floor(x);
        if (i + 1 >= arc.length) return 0.0D;
        return arc[i] + (arc[i + 1] - arc[i]) * (x - i);
    }

    private static double heightAt(List<double[]> frames, double ms)
    {
        double[] prev = null;
        for (double[] v : frames)
        {
            if (v[0] >= ms)
            {
                if (prev == null) return v[3];
                return prev[3] + (v[3] - prev[3]) * (ms - prev[0]) / (v[0] - prev[0]);
            }
            prev = v;
        }
        return Double.NaN;
    }

    private static void jumpReport(Map<String, List<double[]>> jumps)
    {
        System.out.println("=== Jump: OS space press -> drawn height (ms after the press; heights in blocks)");
        System.out.println(String.format(Locale.ROOT, "  %-14s %6s %9s %8s %8s %8s %10s %9s %12s %10s", "mode", "trials", "1st rise", "h@25", "h@50", "h@100",
            "lag ms", "fit rms", "engine resid", "input ms"));
        Map<String, double[]> sums = new LinkedHashMap<String, double[]>();
        Map<String, double[]> lagRange = new LinkedHashMap<String, double[]>();
        for (Map.Entry<String, List<double[]>> e : jumps.entrySet())
        {
            String mode = e.getKey().split("\\|")[0];
            List<double[]> fr = e.getValue();
            double first = Double.NaN;
            for (double[] v : fr) if (v[0] >= 0.0D && v[3] > 1e-3D) { first = v[0]; break; }
            // lag: shift that best fits the drawn heights to the exact arc (frames 0..500 ms)
            double bestLag = Double.NaN, bestErr = Double.MAX_VALUE;
            for (double lag = -10.0D; lag <= 160.0D; lag += 0.1D)
            {
                double se = 0.0D;
                int n = 0;
                for (double[] v : fr)
                {
                    if (v[0] < 0.0D || v[0] > 500.0D) continue;
                    double d = v[3] - arcAt(v[0] - lag);
                    se += d * d;
                    ++n;
                }
                if (n > 0 && se / n < bestErr)
                {
                    bestErr = se / n;
                    bestLag = lag;
                }
            }
            // engine clock: drawn vs arc at (frame tau - jump tau); input delay = jump tau - press tau
            double engRes = Double.NaN, input = Double.NaN;
            double jumpTau = Double.NaN;
            for (double[] v : fr) if (!Double.isNaN(v[2]) && v[0] > 50.0D) jumpTau = v[2];
            if (!Double.isNaN(jumpTau))
            {
                double se = 0.0D, pressSum = 0.0D;
                int n = 0, np = 0;
                for (double[] v : fr)
                {
                    if (Double.isNaN(v[1])) continue;
                    pressSum += v[1] - v[0] / 50.0D;
                    ++np;
                    double x = (v[1] - jumpTau) * 50.0D;
                    if (x < 0.0D || x > 500.0D) continue;
                    double d = v[3] - arcAt(x);
                    se += d * d;
                    ++n;
                }
                if (n > 0) engRes = Math.sqrt(se / n);
                if (np > 0) input = (jumpTau - pressSum / np) * 50.0D;
            }
            double[] s = sums.get(mode);
            if (s == null) sums.put(mode, s = new double[9]);
            s[0] += 1;
            s[1] += first;
            s[2] += heightAt(fr, 25.0D);
            s[3] += heightAt(fr, 50.0D);
            s[4] += heightAt(fr, 100.0D);
            s[5] += bestLag;
            s[6] += Math.sqrt(bestErr);
            s[7] += Double.isNaN(engRes) ? 0.0D : engRes;
            s[8] += Double.isNaN(input) ? 0.0D : input;
            double[] lr = lagRange.get(mode);
            if (lr == null) lagRange.put(mode, lr = new double[] {Double.MAX_VALUE, -Double.MAX_VALUE});
            lr[0] = Math.min(lr[0], bestLag);
            lr[1] = Math.max(lr[1], bestLag);
        }
        for (Map.Entry<String, double[]> e : sums.entrySet())
        {
            double[] s = e.getValue();
            double n = s[0];
            boolean engine = !e.getKey().equals("VANILLA");
            double[] lr = lagRange.get(e.getKey());
            System.out.println(String.format(Locale.ROOT, "  %-14s %6.0f %9.1f %8.4f %8.4f %8.4f %4.1f (%4.1f-%4.1f) %8.4f %12s %10s", e.getKey(), n, s[1] / n, s[2] / n, s[3] / n,
                s[4] / n, s[5] / n, lr[0], lr[1], s[6] / n, engine ? String.format(Locale.ROOT, "%.5f", s[7] / n) : "-",
                engine ? String.format(Locale.ROOT, "%.2f", s[8] / n) : "-"));
        }
    }

    private static void standReport(Map<String, List<double[]>> stands)
    {
        System.out.println("=== Standing on slime after a sneak release (3 s): range of each camera term over all frames");
        System.out.println(String.format(Locale.ROOT, "  %-14s %10s %10s %12s %12s %8s", "mode", "feet y", "eye", "bob y", "bob pitch deg", "frames"));
        Map<String, double[]> agg = new LinkedHashMap<String, double[]>();
        for (Map.Entry<String, List<double[]>> e : stands.entrySet())
        {
            String mode = e.getKey().split("\\|")[0];
            double[] a = agg.get(mode);
            if (a == null) agg.put(mode, a = new double[] {0, 0, 0, 0, 0});
            double[] lo = {1e9, 1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9, -1e9};
            for (double[] v : e.getValue())
            {
                if (v[0] < 300.0D) continue; // after the eye height has settled from the sneak release
                double walked = v[7], bobYaw = v[5];
                double bobY = -Math.abs(Math.cos(-walked * Math.PI) * bobYaw);
                double bobPitch = Math.abs(Math.cos(-walked * Math.PI - 0.2D) * bobYaw) * 5.0D + v[6];
                double[] terms = {v[3], v[4], bobY, bobPitch};
                for (int i = 0; i < 4; ++i)
                {
                    lo[i] = Math.min(lo[i], terms[i]);
                    hi[i] = Math.max(hi[i], terms[i]);
                }
                a[4] += 1;
            }
            for (int i = 0; i < 4; ++i) a[i] = Math.max(a[i], hi[i] - lo[i]);
        }
        for (Map.Entry<String, double[]> e : agg.entrySet())
        {
            double[] a = e.getValue();
            System.out.println(String.format(Locale.ROOT, "  %-14s %10.5f %10.5f %12.6f %12.5f %8.0f", e.getKey(), a[0], a[1], a[2], a[3], a[4]));
        }
    }
}
