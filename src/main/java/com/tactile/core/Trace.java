package com.tactile.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * A recorded baseline: one row per vanilla tick holding the input applied and the player state
 * before and after the tick. Doubles are written with Double.toString, which round-trips exactly.
 */
public final class Trace
{
    public static final class Row
    {
        public int tick;
        public TickInput input;
        public PlayerState pre, post;
        /** State changed between the previous tick's END and this tick's START (packet, teleport). */
        public boolean externalChange;
        /** Yaw at END differed from the scripted yaw (mouse moved), or a GUI was open. */
        public boolean contaminated;
    }

    public String scenario;
    public int originX, originY, originZ;
    public final List<Row> rows = new ArrayList<Row>();

    private static final String STATE_COLS = "minX,minY,minZ,maxX,maxY,maxZ,mx,my,mz,onGround,colH,colV,sprinting,jumpTicks,sprintToggle,sprintLeft,jmf";

    public void write(File file) throws IOException
    {
        file.getParentFile().mkdirs();
        PrintWriter w = new PrintWriter(new FileWriter(file));
        try
        {
            w.println("# scenario=" + scenario);
            w.println("# origin=" + originX + "," + originY + "," + originZ);
            w.println("tick,keys,yaw,pitch,ext,contaminated," + prefixed("pre_") + "," + prefixed("post_") + ",pre_flying,pre_inWeb,post_flying,post_inWeb");
            for (Row r : rows)
            {
                w.println(r.tick + "," + r.input.keyString() + "," + r.input.yaw + "," + r.input.pitch + ","
                    + (r.externalChange ? 1 : 0) + "," + (r.contaminated ? 1 : 0) + ","
                    + stateCsv(r.pre) + "," + stateCsv(r.post) + ","
                    + (r.pre.flying ? 1 : 0) + "," + (r.pre.inWeb ? 1 : 0) + "," + (r.post.flying ? 1 : 0) + "," + (r.post.inWeb ? 1 : 0));
            }
        }
        finally
        {
            w.close();
        }
    }

    public static Trace read(File file) throws IOException
    {
        Trace t = new Trace();
        BufferedReader r = new BufferedReader(new FileReader(file));
        try
        {
            String line;
            while ((line = r.readLine()) != null)
            {
                if (line.startsWith("# scenario=")) t.scenario = line.substring(11).trim();
                else if (line.startsWith("# origin="))
                {
                    String[] p = line.substring(9).trim().split(",");
                    t.originX = Integer.parseInt(p[0]);
                    t.originY = Integer.parseInt(p[1]);
                    t.originZ = Integer.parseInt(p[2]);
                }
                else if (line.startsWith("tick,") || line.startsWith("#") || line.trim().isEmpty()) continue;
                else
                {
                    String[] p = line.split(",");
                    Row row = new Row();
                    row.tick = Integer.parseInt(p[0]);
                    row.input = TickInput.fromKeyString(p[1], Float.parseFloat(p[2]), Float.parseFloat(p[3]));
                    row.externalChange = "1".equals(p[4]);
                    row.contaminated = "1".equals(p[5]);
                    row.pre = parseState(p, 6);
                    row.post = parseState(p, 6 + 17);
                    if (p.length >= 6 + 34 + 4) // flying / in-web columns (recordings made since 0.6.0)
                    {
                        row.pre.flying = "1".equals(p[40]);
                        row.pre.inWeb = "1".equals(p[41]);
                        row.post.flying = "1".equals(p[42]);
                        row.post.inWeb = "1".equals(p[43]);
                    }
                    row.pre.yaw = row.input.yaw;
                    row.post.yaw = row.input.yaw;
                    t.rows.add(row);
                }
            }
        }
        finally
        {
            r.close();
        }
        return t;
    }

    private static String prefixed(String prefix)
    {
        StringBuilder sb = new StringBuilder();
        for (String c : STATE_COLS.split(","))
        {
            if (sb.length() > 0) sb.append(',');
            sb.append(prefix).append(c);
        }
        return sb.toString();
    }

    public static String stateCsv(PlayerState s)
    {
        Box b = s.box;
        return b.minX + "," + b.minY + "," + b.minZ + "," + b.maxX + "," + b.maxY + "," + b.maxZ + ","
            + s.motionX + "," + s.motionY + "," + s.motionZ + ","
            + (s.onGround ? 1 : 0) + "," + (s.collidedHorizontally ? 1 : 0) + "," + (s.collidedVertically ? 1 : 0) + ","
            + (s.sprinting ? 1 : 0) + "," + (int)s.jumpTicks + "," + (int)s.sprintToggleTimer + "," + s.sprintingTicksLeft + ","
            + s.jumpMovementFactor;
    }

    private static PlayerState parseState(String[] p, int i)
    {
        PlayerState s = new PlayerState();
        s.box = new Box(Double.parseDouble(p[i]), Double.parseDouble(p[i + 1]), Double.parseDouble(p[i + 2]),
            Double.parseDouble(p[i + 3]), Double.parseDouble(p[i + 4]), Double.parseDouble(p[i + 5]));
        s.motionX = Double.parseDouble(p[i + 6]);
        s.motionY = Double.parseDouble(p[i + 7]);
        s.motionZ = Double.parseDouble(p[i + 8]);
        s.onGround = "1".equals(p[i + 9]);
        s.collidedHorizontally = "1".equals(p[i + 10]);
        s.collidedVertically = "1".equals(p[i + 11]);
        s.sprinting = "1".equals(p[i + 12]);
        s.jumpTicks = Integer.parseInt(p[i + 13]);
        s.sprintToggleTimer = Integer.parseInt(p[i + 14]);
        s.sprintingTicksLeft = Integer.parseInt(p[i + 15]);
        s.jumpMovementFactor = Float.parseFloat(p[i + 16]);
        return s;
    }
}
