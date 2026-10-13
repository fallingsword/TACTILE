package com.tactile.harness;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Offline analysis of run/hrm-input (written by InputProbe). Usage: gradlew inputAnalysis.
 *
 * <p>With injected pulses (auto mode), the injection time is the reference. Latencies are measured from
 * the moment the OS accepted the synthetic event, so they cover OS, LWJGL and Minecraft processing.
 * Keyboard hardware and USB latency are <b>not</b> included and cannot be measured in software.
 * Without injections (manual mode), the async poller is used as the reference for the other sources.
 */
public final class InputAnalysis
{
    static final class Ev
    {
        String source, key;
        boolean down, focused;
        long nanos, extra;
    }

    static final class Frame
    {
        char type;
        long nanos;
        double partial, lastHR;
        int fps;
        long ticksBefore;
    }

    private static final String[] SOURCES = {"raw", "async", "frameA", "frameB", "lwjgl"};

    public static void main(String[] args) throws IOException
    {
        File dir = new File(args.length > 0 ? args[0] : "run/hrm-input");
        List<Ev> events = readEvents(new File(dir, "events.csv"));
        List<Frame> frames = readFrames(new File(dir, "frames.csv"));
        List<long[]> phases = phases(events);
        System.out.println("Loaded " + events.size() + " events, " + frames.size() + " frame/tick rows from " + dir.getAbsolutePath());

        boolean injected = false;
        for (Ev e : events) if (e.source.equals("inject")) injected = true;
        if (injected) injectedReport(events, phases);
        else manualReport(events);

        frameReport(frames, phases);
        lwjglTimestampReport(events);
        pollReport(new File(dir, "poll_hist.csv"));
    }

    // ------------------------------------------------------------------ injected pulses

    private static void injectedReport(List<Ev> events, List<long[]> phases)
    {
        List<Ev> inj = filter(events, "inject", "F13");
        List<long[]> pulses = new ArrayList<long[]>(); // {pressT, releaseT}
        for (int i = 0; i + 1 < inj.size(); ++i)
            if (inj.get(i).down && !inj.get(i + 1).down) pulses.add(new long[] {inj.get(i).nanos, inj.get(i + 1).nanos});
        System.out.println("\n=== Injected pulses: " + pulses.size() + " (latency measured from OS injection; hardware/USB latency excluded)");

        for (long[] ph : phases)
        {
            System.out.println(String.format(Locale.ROOT, "\n--- %s", phaseName(ph)));
            System.out.println(String.format(Locale.ROOT, "  %-8s %6s %7s | press latency ms: %6s %6s %6s %6s %6s | release p50/p99", "source", "pulses", "missed",
                "p10", "p50", "p90", "p99", "max"));
            for (String src : SOURCES)
            {
                List<Ev> s = filter(events, src, "F13");
                List<Double> pressLat = new ArrayList<Double>(), relLat = new ArrayList<Double>();
                int n = 0, missed = 0;
                Map<Long, int[]> missByDur = new LinkedHashMap<Long, int[]>();
                for (int p = 0; p < pulses.size(); ++p)
                {
                    long tp = pulses.get(p)[0], tr = pulses.get(p)[1];
                    if (tp < ph[0] || tp >= ph[1]) continue;
                    long limit = p + 1 < pulses.size() ? pulses.get(p + 1)[0] : Long.MAX_VALUE;
                    ++n;
                    long durMs = Math.round((tr - tp) / 1e6);
                    int[] md = missByDur.get(durMs);
                    if (md == null) missByDur.put(durMs, md = new int[2]);
                    md[0]++;
                    Ev press = first(s, true, tp - 200_000L, limit);
                    if (press == null)
                    {
                        ++missed;
                        md[1]++;
                        continue;
                    }
                    pressLat.add((press.nanos - tp) / 1e6);
                    Ev rel = first(s, false, Math.max(tr, press.nanos) - 200_000L, limit);
                    if (rel != null) relLat.add((rel.nanos - tr) / 1e6);
                }
                if (n == 0) continue;
                Collections.sort(pressLat);
                Collections.sort(relLat);
                System.out.println(String.format(Locale.ROOT, "  %-8s %6d %6.1f%% | %25s %6.2f %6.2f %6.2f %6.2f %6.2f | %5.2f / %5.2f",
                    src, n, 100.0 * missed / n, "", pct(pressLat, 10), pct(pressLat, 50), pct(pressLat, 90), pct(pressLat, 99), pct(pressLat, 100),
                    pct(relLat, 50), pct(relLat, 99)));
                StringBuilder sb = new StringBuilder("           missed by pulse length:");
                for (Map.Entry<Long, int[]> e : missByDur.entrySet())
                    sb.append(String.format(Locale.ROOT, " %dms=%d/%d", e.getKey(), e.getValue()[1], e.getValue()[0]));
                System.out.println(sb);
            }
            vanillaVisibility(events, pulses, ph);
        }
    }

    /**
     * Vanilla physics reads KeyBinding state once per tick, after the event loop. If a pulse's press
     * and release are both handled in the same event loop (observed less than 1 ms apart), the
     * binding ends that loop released and vanilla physics never sees the press.
     */
    private static void vanillaVisibility(List<Ev> events, List<long[]> pulses, long[] ph)
    {
        List<Ev> lw = filter(events, "lwjgl", "F13");
        Map<Long, int[]> byDur = new java.util.TreeMap<Long, int[]>();
        for (int p = 0; p < pulses.size(); ++p)
        {
            long tp = pulses.get(p)[0], tr = pulses.get(p)[1];
            if (tp < ph[0] || tp >= ph[1]) continue;
            long limit = p + 1 < pulses.size() ? pulses.get(p + 1)[0] : Long.MAX_VALUE;
            Ev press = first(lw, true, tp - 200_000L, limit);
            if (press == null) continue;
            Ev rel = first(lw, false, press.nanos, limit);
            long dur = Math.round((tr - tp) / 1e6);
            long bucket = dur <= 2 ? dur : dur <= 5 ? 4 : dur <= 10 ? 8 : dur <= 20 ? 16 : dur <= 40 ? 33 : 66;
            int[] c = byDur.get(bucket);
            if (c == null) byDur.put(bucket, c = new int[2]);
            c[0]++;
            if (rel != null && rel.nanos - press.nanos < 1_000_000L) c[1]++;
        }
        StringBuilder sb = new StringBuilder("  vanilla physics never sees the press (press+release in one event loop), by pulse length:");
        for (Map.Entry<Long, int[]> e : byDur.entrySet())
            sb.append(String.format(Locale.ROOT, " %dms=%d/%d", e.getKey(), e.getValue()[1], e.getValue()[0]));
        System.out.println(sb);
    }

    private static Ev first(List<Ev> list, boolean down, long from, long before)
    {
        for (Ev e : list) if (e.nanos >= from && e.nanos < before && e.down == down) return e;
        return null;
    }

    // ------------------------------------------------------------------ manual

    private static void manualReport(List<Ev> events)
    {
        System.out.println("\n=== Manual session: other sources vs async poller (focused transitions only)");
        String[] keys = {"forward", "back", "left", "right", "jump", "sneak", "sprint"};
        List<Double> taps = new ArrayList<Double>();
        for (String k : keys)
        {
            List<Ev> a = filter(events, "async", k);
            for (int i = 0; i + 1 < a.size(); ++i)
                if (a.get(i).down && !a.get(i + 1).down && a.get(i).focused) taps.add((a.get(i + 1).nanos - a.get(i).nanos) / 1e6);
        }
        Collections.sort(taps);
        System.out.println(String.format(Locale.ROOT, "  key hold durations (async, ms): n=%d p1=%.1f p10=%.1f p50=%.1f min=%.1f",
            taps.size(), pct(taps, 1), pct(taps, 10), pct(taps, 50), taps.isEmpty() ? 0 : taps.get(0)));
        for (String src : new String[] {"frameA", "frameB", "lwjgl", "binding"})
        {
            List<Double> lat = new ArrayList<Double>();
            int unmatched = 0, total = 0;
            for (String k : keys)
            {
                List<Ev> a = filter(events, "async", k), s = filter(events, src, k);
                for (int i = 0; i < a.size(); ++i)
                {
                    Ev ref = a.get(i);
                    if (!ref.focused || i == 0) continue;
                    long limit = i + 1 < a.size() ? a.get(i + 1).nanos : Long.MAX_VALUE;
                    ++total;
                    Ev m = first(s, ref.down, ref.nanos - 20_000_000L, limit);
                    if (m == null) ++unmatched;
                    else lat.add((m.nanos - ref.nanos) / 1e6);
                }
            }
            Collections.sort(lat);
            System.out.println(String.format(Locale.ROOT, "  %-8s n=%d unmatched=%d  latency vs async ms: p10=%.2f p50=%.2f p90=%.2f p99=%.2f max=%.2f",
                src, total, unmatched, pct(lat, 10), pct(lat, 50), pct(lat, 90), pct(lat, 99), pct(lat, 100)));
        }
        // Keyboard report period estimate: phase concentration of async press times modulo P
        List<Ev> presses = new ArrayList<Ev>();
        for (String k : keys) for (Ev e : filter(events, "async", k)) if (e.down && e.focused) presses.add(e);
        StringBuilder sb = new StringBuilder("  phase concentration R of async transition times modulo P (R near 0 = no grid):");
        for (int pMs : new int[] {2, 4, 8, 16})
        {
            double c = 0, s = 0;
            for (Ev e : presses)
            {
                double ph = 2 * Math.PI * ((e.nanos % (pMs * 1_000_000L)) / (pMs * 1e6));
                c += Math.cos(ph);
                s += Math.sin(ph);
            }
            double r = presses.isEmpty() ? 0 : Math.sqrt(c * c + s * s) / presses.size();
            sb.append(String.format(Locale.ROOT, " %dms=%.2f", pMs, r));
        }
        System.out.println(sb + "  (n=" + presses.size() + ")");
    }

    // ------------------------------------------------------------------ frames, timer, LWJGL timestamps, poller

    private static void frameReport(List<Frame> frames, List<long[]> phases)
    {
        System.out.println("\n=== Frame / tick timing");
        for (long[] ph : phases)
        {
            List<Double> fi = new ArrayList<Double>();
            long prev = -1;
            List<double[]> anchor = new ArrayList<double[]>(); // {lastHRTimeSec, tau}
            for (Frame f : frames)
            {
                if (f.type != 'F' || f.nanos < ph[0] || f.nanos >= ph[1]) continue;
                if (prev > 0) fi.add((f.nanos - prev) / 1e6);
                prev = f.nanos;
                anchor.add(new double[] {f.lastHR, f.ticksBefore + f.partial, f.nanos / 1e9});
            }
            Collections.sort(fi);
            if (fi.isEmpty()) continue;
            // Timer clock consistency: tau(t) from the Timer's own time base should be linear (slope ~20/s).
            // Frame-to-frame consistency: what a game-time anchor actually needs. Increment error between
            // consecutive frames = d(tau) - 20 * d(t), in ms, for each candidate clock.
            List<Double> incTimer = new ArrayList<Double>(), incNano = new ArrayList<Double>();
            for (int i = 1; i < anchor.size(); ++i)
            {
                double dTau = anchor.get(i)[1] - anchor.get(i - 1)[1];
                incTimer.add(Math.abs(dTau - 20.0D * (anchor.get(i)[0] - anchor.get(i - 1)[0])) * 50.0D);
                incNano.add(Math.abs(dTau - 20.0D * (anchor.get(i)[2] - anchor.get(i - 1)[2])) * 50.0D);
            }
            Collections.sort(incTimer);
            Collections.sort(incNano);
            int worst = -1;
            double worstVal = 0;
            for (int i = 1; i < anchor.size(); ++i)
            {
                double e = Math.abs((anchor.get(i)[1] - anchor.get(i - 1)[1]) - 20.0D * (anchor.get(i)[0] - anchor.get(i - 1)[0]));
                if (e > worstVal)
                {
                    worstVal = e;
                    worst = i;
                }
            }
            System.out.println(String.format(Locale.ROOT,
                "  %-22s frame-to-frame anchor error ms: Timer clock p50=%.3f p99=%.3f max=%.2f | frame nanoTime p50=%.3f p99=%.3f max=%.2f | worst at frame %d (dTau=%.3f)",
                phaseName(ph), pct(incTimer, 50), pct(incTimer, 99), pct(incTimer, 100), pct(incNano, 50), pct(incNano, 99), pct(incNano, 100),
                worst, worst > 0 ? anchor.get(worst)[1] - anchor.get(worst - 1)[1] : 0));
            double[] fit = linearFit(anchor, 0);
            List<Double> res = residualsMs(anchor, fit, 0);
            double[] fitN = linearFit(anchor, 2);
            List<Double> resN = residualsMs(anchor, fitN, 2);
            System.out.println(String.format(Locale.ROOT,
                "  %-22s frames=%d interval ms p50=%.2f p90=%.2f p99=%.2f max=%.2f | tau vs Timer clock: slope=%.4f/s resid p99=%.2fms | tau vs frame nanoTime: resid p99=%.2fms",
                phaseName(ph), fi.size(), pct(fi, 50), pct(fi, 90), pct(fi, 99), pct(fi, 100), fit[0], pct(res, 99), pct(resN, 99)));
        }
    }

    private static double[] linearFit(List<double[]> pts, int xi)
    {
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        int n = pts.size();
        for (double[] p : pts)
        {
            sx += p[xi];
            sy += p[1];
            sxx += p[xi] * p[xi];
            sxy += p[xi] * p[1];
        }
        double mx = sx / n, my = sy / n;
        double slope = (sxy - n * mx * my) / (sxx - n * mx * mx);
        return new double[] {slope, my - slope * mx};
    }

    private static List<Double> residualsMs(List<double[]> pts, double[] fit, int xi)
    {
        List<Double> r = new ArrayList<Double>();
        for (double[] p : pts) r.add(Math.abs(p[1] - (fit[0] * p[xi] + fit[1])) * 50.0D); // ticks -> ms
        Collections.sort(r);
        return r;
    }

    private static void lwjglTimestampReport(List<Ev> events)
    {
        List<Ev> lw = new ArrayList<Ev>();
        for (Ev e : events) if (e.source.equals("lwjgl")) lw.add(e);
        if (lw.isEmpty()) return;
        long gcd = 0;
        List<Double> lag = new ArrayList<Double>();
        long minOff = Long.MAX_VALUE;
        for (Ev e : lw) minOff = Math.min(minOff, e.nanos - e.extra);
        for (Ev e : lw)
        {
            gcd = gcd(gcd, Math.abs(e.extra));
            lag.add((e.nanos - e.extra - minOff) / 1e6);
        }
        Collections.sort(lag);
        System.out.println(String.format(Locale.ROOT,
            "\n=== LWJGL event timestamps: n=%d, all multiples of %.3f ms; (observe - stamp) spread above minimum ms: p50=%.2f p90=%.2f p99=%.2f max=%.2f",
            lw.size(), gcd / 1e6, pct(lag, 50), pct(lag, 90), pct(lag, 99), pct(lag, 100)));
    }

    private static long gcd(long a, long b)
    {
        while (b != 0)
        {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    private static void pollReport(File f) throws IOException
    {
        if (!f.isFile()) return;
        List<long[]> buckets = new ArrayList<long[]>();
        String summary = "";
        BufferedReader r = new BufferedReader(new FileReader(f));
        String line;
        while ((line = r.readLine()) != null)
        {
            if (line.startsWith("#")) summary = line.substring(1).trim();
            else if (!line.startsWith("bucket")) buckets.add(new long[] {Long.parseLong(line.split(",")[0]), Long.parseLong(line.split(",")[1])});
        }
        r.close();
        long total = 0;
        for (long[] b : buckets) total += b[1];
        long acc = 0;
        StringBuilder sb = new StringBuilder();
        double[] targets = {0.5, 0.9, 0.99, 0.999};
        int ti = 0;
        for (long[] b : buckets)
        {
            acc += b[1];
            while (ti < targets.length && acc >= targets[ti] * total)
            {
                sb.append(String.format(Locale.ROOT, " p%s<%.2fms", fmtPct(targets[ti]), (b[0] + 50) / 1000.0));
                ++ti;
            }
        }
        long over5 = 0;
        for (long[] b : buckets) if (b[0] >= 5000) over5 += b[1];
        System.out.println("\n=== Async poll intervals (target 1 ms):" + sb + "  intervals>5ms=" + over5 + "  [" + summary + "]");
    }

    private static String fmtPct(double p)
    {
        return p == 0.999 ? "99.9" : String.valueOf((int)Math.round(p * 100));
    }

    // ------------------------------------------------------------------ parsing / util

    private static List<long[]> phases(List<Ev> events)
    {
        List<long[]> list = new ArrayList<long[]>();
        long start = -1;
        String name = null;
        List<String> names = new ArrayList<String>();
        for (Ev e : events)
        {
            if (!e.source.equals("marker")) continue;
            if (start >= 0) list.add(new long[] {start, e.nanos});
            start = e.nanos;
            names.add(e.key);
        }
        if (list.isEmpty())
        {
            list.add(new long[] {Long.MIN_VALUE, Long.MAX_VALUE});
            PHASE_NAMES.add("whole session");
        }
        else
        {
            for (int i = 0; i < list.size(); ++i) PHASE_NAMES.add(names.get(i));
        }
        PHASE_LIST.addAll(list);
        return list;
    }

    private static final List<String> PHASE_NAMES = new ArrayList<String>();
    private static final List<long[]> PHASE_LIST = new ArrayList<long[]>();

    private static String phaseName(long[] ph)
    {
        int i = PHASE_LIST.indexOf(ph);
        return i >= 0 && i < PHASE_NAMES.size() ? PHASE_NAMES.get(i) : "?";
    }

    private static List<Ev> filter(List<Ev> all, String source, String key)
    {
        List<Ev> out = new ArrayList<Ev>();
        for (Ev e : all) if (e.source.equals(source) && e.key.equals(key)) out.add(e);
        return out;
    }

    private static double pct(List<Double> sorted, double p)
    {
        if (sorted.isEmpty()) return Double.NaN;
        int i = (int)Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    private static List<Ev> readEvents(File f) throws IOException
    {
        List<Ev> list = new ArrayList<Ev>();
        BufferedReader r = new BufferedReader(new FileReader(f));
        String line;
        while ((line = r.readLine()) != null)
        {
            if (line.startsWith("source,")) continue;
            String[] p = line.split(",");
            if (p.length < 6) continue;
            Ev e = new Ev();
            e.source = p[0];
            e.key = p[1];
            e.down = "1".equals(p[2]);
            e.nanos = Long.parseLong(p[3]);
            e.extra = Long.parseLong(p[4]);
            e.focused = "1".equals(p[5]);
            list.add(e);
        }
        r.close();
        Collections.sort(list, new java.util.Comparator<Ev>()
        {
            public int compare(Ev a, Ev b)
            {
                return Long.compare(a.nanos, b.nanos);
            }
        });
        return list;
    }

    private static List<Frame> readFrames(File f) throws IOException
    {
        List<Frame> list = new ArrayList<Frame>();
        BufferedReader r = new BufferedReader(new FileReader(f));
        String line;
        long ticks = 0;
        while ((line = r.readLine()) != null)
        {
            if (line.startsWith("type,")) continue;
            String[] p = line.split(",");
            Frame fr = new Frame();
            fr.type = p[0].charAt(0);
            fr.nanos = Long.parseLong(p[2]);
            fr.partial = Double.parseDouble(p[3]);
            fr.lastHR = Double.parseDouble(p[4]);
            fr.fps = Integer.parseInt(p[5]);
            if (fr.type == 'T') ++ticks;
            fr.ticksBefore = ticks;
            list.add(fr);
        }
        r.close();
        return list;
    }
}
