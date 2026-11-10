package com.tactile.harness;

import java.util.Locale;

import com.tactile.core.Course;
import com.tactile.core.InputTimeline;
import com.tactile.core.PlayerState;
import com.tactile.core.SubstepModel;
import com.tactile.core.TickInput;
import com.tactile.core.VanillaModel;

/**
 * Corner clip at a one-block step (gradlew cornerClipTest). Vanilla resolves each tick's move vertically first, then
 * horizontally, so a sprint-jump that rises past the step's top within a tick clears the corner even when a straight
 * line between the tick positions would have hit the block. This checks that EXACT reproduces it.
 * <p>
 * Course: floor top y = 100, step block y 100..101 from z = 6 (face at z = 6). A "corner clip" is a pair of consecutive
 * game-tick observations where the feet go from below the step top and in front of the face to at/above the top and
 * past the face, while the straight line between the two observations crosses the face plane below the top.
 * <ul>
 * <li>Part A: jump at a tick start (vanilla's only timing), vanilla vs EXACT at 20, 240 and 960 Hz.</li>
 * <li>Part B: jump pressed mid-tick (EXACT 960 Hz), run-up shifted back by the extra sprint distance so the takeoff spots
 * match part A. The jump starts a grid tick at the press, so the outcome should match vanilla's for the same spot.
 * The clip count can differ slightly: observations stay on the game clock, no longer aligned with the jump's ticks.</li>
 * </ul>
 */
public final class CornerClipTest
{
    static final double FACE = 6.0D, TOP = 101.0D, HALF = 0.3D;
    static final Course C = new Course().fill(-3, 99, -3, 3, 99, 30, Course.Shape.FULL).fill(-3, 100, 6, 3, 100, 30, Course.Shape.FULL);
    static final int COUNT = 3000;
    static final double FROM = 2.0D, STEP = 0.001D;

    static final class Result
    {
        boolean onTop, clip;
        double endY, endZ;
    }

    static Result run(double z0, double phase, int n)
    {
        double takeoff = 2.0D + phase;
        InputTimeline tl = new InputTimeline()
            .add(0.0D, TickInput.fromKeyString("W-----R", 0.0F, 0.0F))
            .add(takeoff, TickInput.fromKeyString("W---J-R", 0.0F, 0.0F))
            .add(takeoff + 2.0D, TickInput.fromKeyString("W-----R", 0.0F, 0.0F));
        PlayerState s = EdgeLandingTest.rest(z0);
        s.setSprinting(true);
        s.motionZ = 0.2806D * 0.546D;
        s.jumpMovementFactor = 0.026F;
        SubstepModel m = n == 0 ? null : new SubstepModel(n, SubstepModel.Contacts.GRID, true);
        Result r = new Result();
        double py = s.posY(), pz = s.posZ();
        for (long t = 0; t < 30; ++t)
        {
            if (m == null) VanillaModel.tick(s, tl.at((double)t), C);
            else m.tickExact(s, tl, t, C);
            double y = s.posY(), z = s.posZ();
            double f0 = pz + HALF, f1 = z + HALF;
            if (py < TOP - 1e-9 && f0 <= FACE + 1e-9 && y >= TOP - 1e-9 && f1 > FACE + 1e-9)
            {
                double u = (FACE - f0) / (f1 - f0);
                if (py + u * (y - py) < TOP - 1e-9) r.clip = true;
            }
            py = y;
            pz = z;
        }
        r.endY = s.posY();
        r.endZ = s.posZ();
        r.onTop = r.endY >= TOP - 1e-6;
        return r;
    }

    public static void main(String[] args)
    {
        int[] rates = {1, 12, 48};
        int vClip = 0, vTop = 0;
        int[] sameOutcome = new int[rates.length], sameClip = new int[rates.length];
        double[] maxDiff = new double[rates.length];
        boolean[] vanillaTop = new boolean[COUNT];
        for (int i = 0; i < COUNT; ++i)
        {
            double z0 = FROM + i * STEP;
            Result v = run(z0, 0.0D, 0);
            vanillaTop[i] = v.onTop;
            if (v.clip) ++vClip;
            if (v.onTop) ++vTop;
            for (int k = 0; k < rates.length; ++k)
            {
                Result e = run(z0, 0.0D, rates[k]);
                if (e.onTop == v.onTop) ++sameOutcome[k];
                if (e.clip == v.clip) ++sameClip[k];
                maxDiff[k] = Math.max(maxDiff[k], Math.max(Math.abs(e.endY - v.endY), Math.abs(e.endZ - v.endZ)));
            }
        }
        System.out.println(String.format(Locale.ROOT, "Part A, jump at a tick start, %d run-up positions (z0 %.3f..%.3f):", COUNT, FROM, FROM + (COUNT - 1) * STEP));
        System.out.println(String.format(Locale.ROOT, "  VANILLA: on top of the step in %d runs; corner clips %d", vTop, vClip));
        for (int k = 0; k < rates.length; ++k)
            System.out.println(String.format(Locale.ROOT, "  EXACT %4d Hz: same outcome %d/%d, same corner clip %d/%d, max end-position difference %.4f",
                20 * rates[k], sameOutcome[k], COUNT, sameClip[k], COUNT, maxDiff[k]));

        System.out.println("Part B, jump pressed mid-tick, EXACT 960 Hz, takeoff spots matched to part A:");
        for (int j = 1; j <= 9; j += 2)
        {
            double p = j / 10.0D;
            int top = 0, clip = 0, same = 0;
            for (int i = 0; i < COUNT; ++i)
            {
                Result e = run(FROM + i * STEP - p * 0.2806D, p, 48);
                if (e.onTop) ++top;
                if (e.clip) ++clip;
                if (e.onTop == vanillaTop[i]) ++same;
            }
            System.out.println(String.format(Locale.ROOT, "  press at +%.1f tick: on top %d, same outcome as vanilla's jump from that spot %d/%d, corner clips %d",
                p, top, same, COUNT, clip));
        }
    }
}
