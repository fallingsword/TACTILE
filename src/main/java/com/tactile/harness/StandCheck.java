package com.tactile.harness;

import java.util.Locale;

import com.tactile.core.Box;
import com.tactile.core.Course;
import com.tactile.core.InputTimeline;
import com.tactile.core.PlayerState;
import com.tactile.core.SubstepModel;
import com.tactile.core.TickInput;

/** Standing still in EXACT mode: state at tick boundaries per rate (diagnostic). */
public final class StandCheck
{
    public static void main(String[] args)
    {
        Course floor = new Course().fill(-5, 99, -5, 5, 99, 5, Course.Shape.FULL);
        InputTimeline tl = new InputTimeline().add(0.0D, TickInput.fromKeyString("-------", 0.0F, 0.0F));
        for (int n : new int[] {1, 3, 12})
        {
            PlayerState s = new PlayerState();
            s.box = Box.forPlayerAt(0.5D, 100.0D, 0.5D);
            s.onGround = true;
            s.motionY = -0.0784000015258789D;
            SubstepModel m = new SubstepModel(n, SubstepModel.Contacts.GRID, true);
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "  %4d Hz:", 20 * n));
            for (long t = 0; t < 6; ++t)
            {
                // a partial (render) advance first, as the live engine does between ticks, then the full tick
                PlayerState p = s.copy();
                SubstepModel.Saved saved = m.save();
                JumpLatencyCheck.substep(m, p, floor, tl, (double)t, t + 0.4D / n, true, 0.4D / n);
                m.restore(saved);
                m.tickExact(s, tl, t, floor);
                sb.append(String.format(Locale.ROOT, "  t%d y %.4f my %.4f g %b", t + 1, s.posY(), s.motionY, s.onGround));
            }
            System.out.println(sb);
        }
    }
}
