package com.tactile.harness;

import java.util.Locale;

import com.tactile.core.Box;
import com.tactile.core.Course;
import com.tactile.core.PlayerState;
import com.tactile.core.SubstepModel;
import com.tactile.core.TickInput;

/**
 * Vertical smoothness of the drawn position on a ladder (gradlew ladderViewCheck). Climb, hold still with sneak,
 * then let go and slide. While holding, the drawn height should not move at all; while climbing or sliding it should
 * move one way only. Reported for the physics box and for the view, at 240 and 960 Hz.
 */
public final class LadderViewCheck
{
    public static void main(String[] args)
    {
        Course c = new Course()
            .fill(-3, 99, -2, 3, 99, 4, Course.Shape.FULL)
            .fill(-2, 100, 3, 2, 106, 3, Course.Shape.FULL)
            .fill(0, 100, 2, 0, 105, 2, Course.Shape.LADDER_NORTH);
        System.out.println("  rate  path   hold: height range   climb: reversals   slide: reversals");
        for (int n : new int[] {12, 48})
            for (boolean view : new boolean[] {false, true})
            {
                PlayerState s = new PlayerState();
                s.box = Box.forPlayerAt(0.5D, 100.0D, 0.5D);
                s.onGround = true;
                s.motionY = -0.0784000015258789D;
                SubstepModel m = new SubstepModel(n, SubstepModel.Contacts.GRID, true);
                double holdMin = Double.MAX_VALUE, holdMax = -Double.MAX_VALUE;
                int climbRev = 0, slideRev = 0;
                double lastY = y(s, view), lastDir = 0.0D;
                for (int t = 0; t < 90; ++t)
                {
                    String keys = t < 30 ? "W------" : t < 70 ? "-----N-" : "-------";
                    TickInput in = TickInput.fromKeyString(keys, 0.0F, 0.0F);
                    for (int k = 0; k < n; ++k)
                    {
                        m.substep(s, c, in, k == 0);
                        double y = y(s, view), d = y - lastY;
                        lastY = y;
                        if (Math.abs(d) > 1e-9D)
                        {
                            double dir = Math.signum(d);
                            if (lastDir != 0.0D && dir != lastDir)
                            {
                                if (t >= 8 && t < 30) ++climbRev;
                                if (t >= 72) ++slideRev;
                            }
                            lastDir = dir;
                        }
                        if (t >= 40 && t < 70)
                        {
                            holdMin = Math.min(holdMin, y);
                            holdMax = Math.max(holdMax, y);
                        }
                    }
                }
                System.out.println(String.format(Locale.ROOT, "  %4d  %-5s  %.5f              %3d                 %3d", 20 * n, view ? "view" : "box",
                    holdMax - holdMin, climbRev, slideRev));
            }
        slimeStand();
    }

    /**
     * Standing on slime (after sneaking, then releasing sneak): vanilla's idle bounce gives every other tick an upward
     * speed that is used up by the tick's end, so vanilla's drawn height never moves. The view is sampled at frame-like
     * times inside each substep through the EXACT render path (a throwaway copy advanced to the frame time).
     */
    static void slimeStand()
    {
        Course c = new Course().fill(-3, 99, -3, 3, 99, 3, Course.Shape.SLIME);
        System.out.println("  standing on slime: drawn height range (frames at 7 ms steps)");
        for (int n : new int[] {1, 12, 48})
        {
            PlayerState s = new PlayerState();
            s.box = Box.forPlayerAt(0.5D, 100.0D, 0.5D);
            s.onGround = true;
            s.motionY = -0.0784000015258789D;
            SubstepModel m = new SubstepModel(n, SubstepModel.Contacts.GRID, true);
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, boxMin = Double.MAX_VALUE, boxMax = -Double.MAX_VALUE;
            for (int t = 0; t < 60; ++t)
            {
                TickInput in = TickInput.fromKeyString(t < 20 ? "-----N-" : "-------", 0.0F, 0.0F);
                for (int k = 0; k < n; ++k)
                {
                    if (t >= 22)
                        for (double f = 0.07D; f < 1.0D; f += 0.14D) // frames inside this substep
                        {
                            PlayerState copy = s.copy();
                            SubstepModel.Saved saved = m.save();
                            m.substep(copy, c, new TickInput[] {in}, new double[] {0.0D}, 1, k == 0, f / n);
                            m.restore(saved);
                            min = Math.min(min, copy.viewY());
                            max = Math.max(max, copy.viewY());
                            boxMin = Math.min(boxMin, copy.posY());
                            boxMax = Math.max(boxMax, copy.posY());
                        }
                    m.substep(s, c, in, k == 0);
                }
            }
            System.out.println(String.format(Locale.ROOT, "  %4d  view %.5f   (physics box %.5f)", 20 * n, max - min, boxMax - boxMin));
        }
    }

    static double y(PlayerState s, boolean view)
    {
        return view ? s.viewY() : s.posY();
    }
}
