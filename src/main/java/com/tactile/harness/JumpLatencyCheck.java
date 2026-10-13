package com.tactile.harness;

import java.util.Locale;

import com.tactile.core.Box;
import com.tactile.core.Course;
import com.tactile.core.InputTimeline;
import com.tactile.core.PlayerState;
import com.tactile.core.SubstepModel;
import com.tactile.core.TickInput;

/**
 * Height after a mid-tick jump press, at frame times, as the live EXACT renderer computes it (gradlew
 * jumpLatencyCheck): full substeps up to the last substep end, then a throwaway partial substep to the frame time.
 * Rates must agree (EXACT is rate-independent); a delay would show as zero height at low rates.
 */
public final class JumpLatencyCheck
{
    public static void main(String[] args)
    {
        Course floor = new Course().fill(-5, 99, -5, 5, 99, 50, Course.Shape.FULL);
        double press = 5.3D;
        InputTimeline tl = new InputTimeline()
            .add(0.0D, TickInput.fromKeyString("-------", 0.0F, 0.0F))
            .add(press, TickInput.fromKeyString("----J--", 0.0F, 0.0F));
        double[] frames = {5.35D, 5.45D, 5.6D, 5.8D, 6.0D, 6.2D, 6.5D};
        System.out.println("height above the floor at frame time (blocks), jump pressed at tau " + press);
        StringBuilder head = new StringBuilder("  rate   ");
        for (double f : frames) head.append(String.format(Locale.ROOT, " %8.2f", f));
        System.out.println(head);
        for (int n : new int[] {1, 3, 12, 48})
        {
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  %4dHz ", 20 * n));
            for (double frame : frames) line.append(String.format(Locale.ROOT, " %8.4f", heightAt(floor, tl, n, frame)));
            System.out.println(line);
        }
    }

    static double heightAt(Course c, InputTimeline tl, int n, double frame)
    {
        PlayerState s = new PlayerState();
        s.box = Box.forPlayerAt(0.5D, 100.0D, 0.5D);
        s.onGround = true;
        s.motionY = -0.0784000015258789D;
        SubstepModel m = new SubstepModel(n, SubstepModel.Contacts.GRID, true);
        long k = 0;
        while ((double)(k + 1) / n <= frame + 1e-12)
        {
            long t = k / n;
            int j = (int)(k % n);
            double tau = m.substepTau(t, j);
            substep(m, s, c, tl, tau, tau + m.h, j == 0, m.h);
            ++k;
        }
        double from = (double)k / n;
        if (frame - from > 1e-12)
        {
            PlayerState p = s.copy();
            SubstepModel.Saved saved = m.save();
            substep(m, p, c, tl, from, frame, k % n == 0, frame - from);
            m.restore(saved);
            return p.viewY() - 100.0D;
        }
        return s.viewY() - 100.0D;
    }

    /** As LiveEngine.substepExact: split at timeline samples inside [tau, end). */
    static void substep(SubstepModel m, PlayerState s, Course c, InputTimeline tl, double tau, double end, boolean boundary, double span)
    {
        double[][] buf = {new double[4]};
        int cnt = tl.samplesInside(tau, end, buf);
        TickInput[] ins = new TickInput[cnt + 1];
        double[] offs = new double[cnt + 1];
        ins[0] = tl.at(tau);
        for (int i = 0; i < cnt; ++i)
        {
            offs[i + 1] = buf[0][i] - tau;
            ins[i + 1] = tl.at(buf[0][i]);
        }
        m.substep(s, c, ins, offs, cnt + 1, boundary, span);
    }
}
