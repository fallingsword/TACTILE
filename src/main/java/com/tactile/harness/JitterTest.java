package com.tactile.harness;

import java.util.Locale;

import com.tactile.core.Course;
import com.tactile.core.PlayerState;
import com.tactile.core.SubstepModel;
import com.tactile.core.TickInput;
import com.tactile.core.VanillaModel;

/**
 * Smoothness of the per-substep path (gradlew jitterTest). Vanilla renders a linear interpolation between
 * tick positions, so its on-screen path never backtracks when the player keeps moving one way. This test
 * measures, for the substep path along the direction of travel (+z):
 * <ul>
 * <li>backtrack: the largest single substep move backwards (blocks), and the total;</li>
 * <li>spike: the largest substep step divided by the vanilla per-tick step spread evenly over the
 * substeps of that tick (1.0 = as smooth as vanilla's interpolation; a stall followed by a release shows
 * as a large value).</li>
 * </ul>
 * Tick-boundary positions are also checked against vanilla, so smoothing never changes the outcome.
 */
public final class JitterTest
{
    static final boolean DUMP = System.getenv("HRMOVE_DUMP") != null;

    public static void main(String[] args)
    {
        int[] rates = {12, 48};

        // Sneak into a platform end at z = 21, from several starts.
        Course edge = new Course().fill(-3, 99, -3, 3, 99, 20, Course.Shape.FULL);
        System.out.println("Sneak + W into a platform end (edge z = 21), 120 ticks:");
        for (int n : rates)
            for (boolean view : new boolean[] {false, true})
            {
                Stats all = new Stats();
                for (int i = 0; i < 10; ++i)
                {
                    PlayerState s = EdgeLandingTest.rest(18.0D + i * 0.037D);
                    run(edge, s, "W----N-", n, 120, all, view);
                }
                all.print(20 * n + " Hz " + (view ? "view" : "box "));
            }

        // Sprint-jump at a one-block step whose top (y = 101) the feet only just reach or clip.
        Course step = new Course().fill(-3, 99, -3, 3, 99, 30, Course.Shape.FULL).fill(-3, 100, 6, 3, 100, 30, Course.Shape.FULL);
        System.out.println("Sprint-jump onto a one-block step (face z = 6), 400 takeoff offsets (reaching the face rising, at the apex and falling):");
        for (int n : rates)
            for (boolean view : new boolean[] {false, true})
            {
                Stats all = new Stats();
                for (int i = 0; i < 400; ++i)
                {
                    PlayerState s = EdgeLandingTest.rest(-1.0D + i * 0.0175D);
                    s.setSprinting(true);
                    s.motionZ = 0.2806D * 0.546D;
                    s.jumpMovementFactor = 0.026F;
                    run(step, s, "W---J-R", n, 40, all, view);
                }
                all.print(20 * n + " Hz " + (view ? "view" : "box "));
            }
    }

    static final class Stats
    {
        double maxBack, totalBack, maxCatchUp, maxCatchUpY, maxErr;
        int tickMismatch, runs;
        String where = "";

        void print(String label)
        {
            System.out.println(String.format(Locale.ROOT, "  %-12s backtrack max %.5f total %.5f | catch-up z %.5f y %.5f | runs diverging from vanilla %d/%d (max %.5f)",
                label, maxBack, totalBack, maxCatchUp, maxCatchUpY, tickMismatch, runs, maxErr));
            if (DUMP) System.out.println("    worst catch-up at " + where);
        }
    }

    static double zOf(PlayerState s, boolean view)
    {
        return view ? s.viewZ() : s.posZ();
    }

    static double yOf(PlayerState s, boolean view)
    {
        return view ? s.viewY() : s.posY();
    }

    static void run(Course c, PlayerState start, String keys, int n, int ticks, Stats st, boolean view)
    {
        TickInput in = TickInput.fromKeyString(keys, 0.0F, 0.0F);
        PlayerState v = start.copy();
        PlayerState s = start.copy();
        SubstepModel m = new SubstepModel(n, SubstepModel.Contacts.GRID);
        ++st.runs;
        boolean diverged = false;
        double lastFreeZ = 0.0D;
        for (int t = 0; t < ticks; ++t)
        {
            double vz0 = v.posZ();
            VanillaModel.tick(v, in, c);
            double z = zOf(s, view), y = yOf(s, view);
            for (int k = 0; k < n; ++k)
            {
                double accZ0 = s.accZ, accY0 = s.accY, el0 = s.gridElapsed;
                m.substep(s, c, in, k == 0);
                boolean sameGrid = s.gridElapsed > el0;
                // at a grid restart the old grid's last free step is gone: use the previous substep's (+ 50 %)
                double freeZ = sameGrid ? s.accZ - accZ0 : 1.5D * lastFreeZ;
                if (sameGrid) lastFreeZ = Math.abs(freeZ);
                double freeY = sameGrid ? s.accY - accY0 : s.accY;
                double d = zOf(s, view) - z, dy = yOf(s, view) - y;
                z = zOf(s, view);
                y = yOf(s, view);
                if (d < -1e-9)
                {
                    st.maxBack = Math.max(st.maxBack, -d);
                    st.totalBack += -d;
                }
                // catch-up: the path moved further than the free motion of this substep (a stall being released)
                if (freeZ >= 0.0D && d - freeZ > st.maxCatchUp)
                {
                    st.maxCatchUp = d - freeZ;
                    st.where = String.format(Locale.ROOT, "t%d k%d el %.3f sameGrid %b z %.4f y %.4f d %.4f free %.4f ground %b", t, k, s.gridElapsed, sameGrid, z, y, d, freeZ, s.onGround);
                }
                if (sameGrid && Math.abs(dy - freeY) < 0.5D) st.maxCatchUpY = Math.max(st.maxCatchUpY, Math.abs(dy) - Math.abs(freeY));
            }
            if (DUMP) System.out.println(String.format(Locale.ROOT, "t%d v(%.5f %.5f %b) g(%.5f %.5f %b)", t, v.posY(), v.posZ(), v.onGround, s.posY(), s.posZ(), s.onGround));
            double err = Math.max(Math.abs(s.posZ() - v.posZ()), Math.abs(s.posY() - v.posY()));
            st.maxErr = Math.max(st.maxErr, err);
            if (err > 1e-9 && !diverged)
            {
                diverged = true;
                ++st.tickMismatch;
            }
        }
    }
}
