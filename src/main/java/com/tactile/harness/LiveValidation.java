package com.tactile.harness;

import java.io.File;
import java.util.Locale;

import com.tactile.core.Course;
import com.tactile.core.PlayerState;
import com.tactile.core.Scenario;
import com.tactile.core.SubstepModel;
import com.tactile.core.TickInput;
import com.tactile.core.Trace;

/**
 * Gate L: traces recorded in-game with the live engine (scripted input) must match the offline
 * SubstepModel bit-exactly. That checks the whole live path at once: world collision adapter, state
 * transfer, the restore inside the vanilla tick, frame batching, and the tick-boundary catch-up.
 *
 * <p>Usage: gradlew liveValidation -Ptraces=run/hrm-live-x -Psteps=N -Plead=L. With lead L the live
 * script started L ticks after the recorded row 0, so the offline replay runs L idle ticks first.
 */
public final class LiveValidation
{
    public static void main(String[] args) throws Exception
    {
        File dir = new File(args[0]);
        int n = Integer.parseInt(args[1]);
        int lead = Integer.parseInt(args[2]);
        SubstepModel.Contacts contacts = args.length > 3 ? SubstepModel.Contacts.valueOf(args[3]) : SubstepModel.Contacts.GRID;
        boolean exactTiming = args.length > 4 && Boolean.parseBoolean(args[4]); // EXACT mode (scripted input has no in-substep splits)
        int failures = 0, total = 0;
        System.out.println("=== Gate L: live traces in " + dir + " vs offline SubstepModel(" + n + " substeps/tick = " + 20 * n + " Hz), lead " + lead);
        for (Scenario sc : Scenario.all())
        {
            File f = new File(dir, sc.name + ".csv");
            if (!f.isFile()) continue;
            Trace t = Trace.read(f);
            Course course = sc.course(t.originX, t.originY, t.originZ);
            PlayerState s = t.rows.get(0).pre.copy();
            SubstepModel m = new SubstepModel(n, contacts, exactTiming);
            TickInput idle = TickInput.fromKeyString("-------", sc.startYaw, 0.0F);
            int exact = 0, rows = 0, firstBad = -1;
            double maxErr = 0.0D;
            String firstDiff = "";
            for (int i = 0; i < t.rows.size(); ++i)
            {
                // Keys from the script (the engine's input, shifted by the lead). Item use as the client actually had it
                // (recorded): the engine takes it at the move hook of row i - lead, whose interval this row's post ends.
                TickInput in = (i < lead ? idle : sc.script().get(i - lead)).withUse(i >= lead && t.rows.get(i - lead).input.use);
                m.tick(s, in, course);
                Trace.Row r = t.rows.get(i);
                double e = Math.max(Math.max(Math.abs(s.box.minX - r.post.box.minX), Math.abs(s.box.minY - r.post.box.minY)),
                    Math.max(Math.abs(s.box.minZ - r.post.box.minZ), Math.max(Math.abs(s.motionX - r.post.motionX),
                        Math.max(Math.abs(s.motionY - r.post.motionY), Math.abs(s.motionZ - r.post.motionZ)))));
                boolean flags = s.onGround == r.post.onGround && s.sprinting == r.post.sprinting && s.collidedHorizontally == r.post.collidedHorizontally;
                ++rows;
                if (e == 0.0D && flags) ++exact;
                else if (firstBad < 0)
                {
                    firstBad = r.tick;
                    firstDiff = String.format(Locale.ROOT, "pos %.17g,%.17g,%.17g vs %.17g,%.17g,%.17g ground %s/%s",
                        s.box.minX, s.box.minY, s.box.minZ, r.post.box.minX, r.post.box.minY, r.post.box.minZ, s.onGround, r.post.onGround);
                }
                maxErr = Math.max(maxErr, e);
            }
            ++total;
            if (exact != rows) ++failures;
            System.out.println(String.format(Locale.ROOT, "  %-18s %4d/%-4d bit-exact  maxErr=%.3g%s", sc.name, exact, rows, maxErr,
                firstBad >= 0 ? "  first diff at tick " + firstBad + ": " + firstDiff : ""));
        }
        System.out.println(total == 0 ? "NO TRACES" : failures == 0 ? "GATE L PASSED (" + total + " scenarios)" : "GATE L FAILED (" + failures + " of " + total + ")");
        if (total == 0 || failures != 0) System.exit(1);
    }
}
