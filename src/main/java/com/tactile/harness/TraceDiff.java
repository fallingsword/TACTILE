package com.tactile.harness;

import java.io.File;
import java.util.Locale;

import com.tactile.core.PlayerState;
import com.tactile.core.Trace;

/**
 * Compares two in-game recordings of the corpus, row by row (gradlew traceDiff -Pa=dir -Pb=dir): for example
 * VANILLA and EXACT recorded under the same conditions (a potion effect, low food). Reports, per scenario, the
 * largest tick-boundary position distance and the first tick that differs.
 */
public final class TraceDiff
{
    public static void main(String[] args) throws Exception
    {
        File a = new File(args[0]), b = new File(args[1]);
        int lead = args.length > 2 ? Integer.parseInt(args[2]) : 0; // b's script starts this many ticks later (high-res runs use lead 1)
        System.out.println("=== " + a.getName() + " vs " + b.getName() + " (max tick-boundary distance, blocks)");
        double worst = 0.0D;
        File[] files = a.listFiles();
        if (files == null) throw new IllegalArgumentException("not a directory: " + a);
        java.util.Arrays.sort(files);
        for (File fa : files)
        {
            if (!fa.getName().endsWith(".csv")) continue;
            File fb = new File(b, fa.getName());
            if (!fb.isFile()) continue;
            Trace ta = Trace.read(fa), tb = Trace.read(fb);
            int n = Math.min(ta.rows.size(), tb.rows.size() - lead), first = -1;
            double max = 0.0D;
            for (int i = 0; i < n; ++i)
            {
                PlayerState pa = ta.rows.get(i).post, pb = tb.rows.get(i + lead).post;
                // positions relative to each recording's origin (the two runs may use different course origins)
                double dx = (pa.posX() - ta.originX) - (pb.posX() - tb.originX);
                double dy = (pa.posY() - ta.originY) - (pb.posY() - tb.originY);
                double dz = (pa.posZ() - ta.originZ) - (pb.posZ() - tb.originZ);
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (ta.scenario.equals(System.getenv("HRMOVE_DUMP")) && i == 0)
                {
                    PlayerState qa = ta.rows.get(0).pre, qb = tb.rows.get(lead).pre;
                    System.out.println(String.format(Locale.ROOT, "    pre a (%.4f %.4f %.4f mz %.4f g%b s%b) b (%.4f %.4f %.4f mz %.4f g%b s%b)",
                        qa.posX() - ta.originX, qa.posY() - ta.originY, qa.posZ() - ta.originZ, qa.motionZ, qa.onGround, qa.sprinting,
                        qb.posX() - tb.originX, qb.posY() - tb.originY, qb.posZ() - tb.originZ, qb.motionZ, qb.onGround, qb.sprinting));
                }
                if (ta.scenario.equals(System.getenv("HRMOVE_DUMP")) && i < 30)
                    System.out.println(String.format(Locale.ROOT, "    t%-3d a %s (%.4f %.4f %.4f g%b s%b j%.0f mz %.4f) b %s (%.4f %.4f %.4f g%b s%b j%.0f mz %.4f)", i,
                        keys(ta.rows.get(i).input), pa.posX() - ta.originX, pa.posY() - ta.originY, pa.posZ() - ta.originZ, pa.onGround, pa.sprinting, pa.jumpTicks, pa.motionZ,
                        keys(tb.rows.get(i + lead).input), pb.posX() - tb.originX, pb.posY() - tb.originY, pb.posZ() - tb.originZ, pb.onGround, pb.sprinting, pb.jumpTicks, pb.motionZ));
                if (d > 1e-9D && first < 0) first = i;
                max = Math.max(max, d);
            }
            worst = Math.max(worst, max);
            System.out.println(String.format(Locale.ROOT, "  %-20s %3d ticks  max %.4f  first difference at tick %s", ta.scenario, n, max, first < 0 ? "-" : String.valueOf(first)));
        }
        System.out.println(String.format(Locale.ROOT, "  worst %.4f", worst));
    }

    private static String keys(com.tactile.core.TickInput in)
    {
        return (in.forward ? "W" : "-") + (in.left ? "A" : "-") + (in.back ? "S" : "-") + (in.right ? "D" : "-") + (in.jump ? "J" : "-")
            + (in.sneak ? "N" : "-") + (in.sprint ? "R" : "-");
    }
}
