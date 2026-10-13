package com.tactile.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Sample-and-hold input: an ordered list of observed input states, each stamped with the normalized
 * time tau (vanilla ticks) at which it was observed. A substep that starts at tau uses the latest sample
 * observed at or before tau. Inputs are never split inside a substep and never interpolated.
 * A press and release that both fall between two substep starts are not seen; vanilla behaves the
 * same way between ticks.
 */
public final class InputTimeline
{
    private final List<Double> taus = new ArrayList<Double>();
    private final List<TickInput> inputs = new ArrayList<TickInput>();

    /** Appends a sample. Samples must be added in non-decreasing tau order. */
    public InputTimeline add(double tau, TickInput input)
    {
        if (!taus.isEmpty() && tau < taus.get(taus.size() - 1))
            throw new IllegalArgumentException("samples must be time-ordered: " + tau + " < " + taus.get(taus.size() - 1));
        taus.add(tau);
        inputs.add(input);
        return this;
    }

    public int size()
    {
        return taus.size();
    }

    /**
     * STEPPED mode: the keys in effect at {@code tau} when key changes take effect only at multiples of {@code stepTicks}
     * (from time 0): those at the last step boundary, with any press since the boundary before it counted if
     * {@code latch} (so a tap between two boundaries still lasts one step). Yaw and pitch are those at the boundary.
     */
    public TickInput stepped(double tau, double stepTicks, boolean latch)
    {
        double b = Math.floor(tau / stepTicks + 1e-9D) * stepTicks;
        double first = firstTau();
        if (b < first) return at(Math.max(tau, first)); // before the first sample
        return latch && b - stepTicks >= first ? atLatched(b - stepTicks, b) : at(b);
    }

    /** STEPPED mode: step boundaries (multiples of stepTicks) strictly inside (from, to), written to {@code out}; returns the count. */
    public static int stepsInside(double from, double to, double stepTicks, double[][] out)
    {
        int n = 0;
        for (double k = Math.floor(from / stepTicks + 1e-9D) + 1.0D; k * stepTicks < to - 1e-12D; k += 1.0D)
        {
            if (n == out[0].length) out[0] = java.util.Arrays.copyOf(out[0], 2 * n + 4);
            out[0][n++] = k * stepTicks;
        }
        return n;
    }

    /** Time of the oldest sample, or positive infinity if empty. */
    public double firstTau()
    {
        return taus.isEmpty() ? Double.POSITIVE_INFINITY : taus.get(0);
    }

    /** Time of the newest sample, or negative infinity if empty. */
    public double lastTau()
    {
        return taus.isEmpty() ? Double.NEGATIVE_INFINITY : taus.get(taus.size() - 1);
    }

    public TickInput last()
    {
        return inputs.isEmpty() ? null : inputs.get(inputs.size() - 1);
    }

    /** Drops samples no longer needed for lookups at or after {@code tau} (keeps the last one at or before it). */
    public void pruneBefore(double tau)
    {
        int keep = indexAt(tau);
        if (keep <= 0) return;
        taus.subList(0, keep).clear();
        inputs.subList(0, keep).clear();
    }

    /** Removes samples later than {@code tau}. */
    public void truncateAfter(double tau)
    {
        int keep = indexAt(tau) + 1;
        taus.subList(keep, taus.size()).clear();
        inputs.subList(keep, inputs.size()).clear();
    }

    public void clear()
    {
        taus.clear();
        inputs.clear();
    }

    /**
     * Input for a substep starting at {@code tau} when the previous substep started at {@code prevTau}.
     * It is the held state at tau, except that any key with a press edge in (prevTau, tau] counts as
     * down even if it has been released again by tau (press latch). Without the latch, a tap shorter
     * than one substep that falls between two substep starts is never seen. Releases are not latched.
     * Yaw and pitch come from the sample at tau.
     */
    public TickInput atLatched(double prevTau, double tau)
    {
        TickInput held = at(tau);
        int first = indexAt(prevTau) + 1; // first sample after prevTau
        int last = indexAt(tau);
        if (first > last) return held;
        TickInput prev = first > 0 ? inputs.get(first - 1) : null;
        boolean f = held.forward, b = held.back, l = held.left, r = held.right, j = held.jump, n = held.sneak, s = held.sprint;
        for (int i = first; i <= last; ++i)
        {
            TickInput cur = inputs.get(i);
            if (prev == null || cur.forward && !prev.forward) f |= cur.forward;
            if (prev == null || cur.back && !prev.back) b |= cur.back;
            if (prev == null || cur.left && !prev.left) l |= cur.left;
            if (prev == null || cur.right && !prev.right) r |= cur.right;
            if (prev == null || cur.jump && !prev.jump) j |= cur.jump;
            if (prev == null || cur.sneak && !prev.sneak) n |= cur.sneak;
            if (prev == null || cur.sprint && !prev.sprint) s |= cur.sprint;
            prev = cur;
        }
        if (f == held.forward && b == held.back && l == held.left && r == held.right && j == held.jump && n == held.sneak && s == held.sprint) return held;
        return new TickInput(f, b, l, r, j, n, s, held.yaw, held.pitch);
    }

    /**
     * Times of the samples strictly inside (fromTau, toTau), in order, written to {@code out} (grown as needed).
     * Returns the count. Used by EXACT input timing to split a substep at each input change.
     */
    public int samplesInside(double fromTau, double toTau, double[][] out)
    {
        int first = indexAt(fromTau) + 1;
        int n = 0;
        for (int i = first; i < taus.size() && taus.get(i) < toTau; ++i)
        {
            if (n == out[0].length) out[0] = java.util.Arrays.copyOf(out[0], 2 * n + 4);
            out[0][n++] = taus.get(i);
        }
        return n;
    }

    /** True if jump went from up to down at some sample in (fromTau, toTau]. */
    public boolean jumpPressedIn(double fromTau, double toTau)
    {
        int first = indexAt(fromTau) + 1, last = indexAt(toTau);
        boolean prev = first > 0 && inputs.get(first - 1).jump;
        for (int i = Math.max(first, 0); i <= last; ++i)
        {
            boolean cur = inputs.get(i).jump;
            if (cur && !prev) return true;
            prev = cur;
        }
        return false;
    }

    /** Index of the latest sample with sampleTau <= tau, or -1. */
    private int indexAt(double tau)
    {
        int lo = 0, hi = taus.size() - 1, found = -1;
        while (lo <= hi)
        {
            int mid = (lo + hi) >>> 1;
            if (taus.get(mid) <= tau)
            {
                found = mid;
                lo = mid + 1;
            }
            else
            {
                hi = mid - 1;
            }
        }
        return found;
    }

    /** Latest sample with sampleTau <= tau. Throws if tau precedes the first sample. */
    public TickInput at(double tau)
    {
        int lo = 0, hi = taus.size() - 1, found = -1;
        while (lo <= hi)
        {
            int mid = (lo + hi) >>> 1;
            if (taus.get(mid) <= tau)
            {
                found = mid;
                lo = mid + 1;
            }
            else
            {
                hi = mid - 1;
            }
        }
        if (found < 0) throw new IllegalStateException("no input sample at or before tau=" + tau);
        return inputs.get(found);
    }
}
