package com.tactile.core;

import java.util.List;

/**
 * Port of the collision section of Entity.moveEntity (1.8.9 L569-767): sneak-edge clamp, Y-X-Z
 * clipping and the step-up search. It updates the box and the collision flags only. Side effects of
 * the vanilla method (fall state, onLanded, sounds, doBlockCollisions, fire) are deliberately absent.
 * The motion response to clipping belongs to the caller.
 */
public final class Collider
{
    public static final float STEP_HEIGHT = 0.6F;

    /** Which axes were clipped relative to the (sneak-adjusted) request. */
    public static final class Result
    {
        public boolean clippedX, clippedY, clippedZ;
        public double requestedY;
        public double movedX, movedY, movedZ;
    }

    private Collider()
    {
    }

    public static Result move(PlayerState s, CollisionWorld world, double x, double y, double z, boolean sneaking)
    {
        double d3 = x;
        double d4 = y;
        double d5 = z;
        boolean sneakEdge = s.onGround && sneaking;

        if (sneakEdge)
        {
            double d6 = 0.05D;

            for (; x != 0.0D && world.getCollidingBoxes(s.box.offset(x, -1.0D, 0.0D)).isEmpty(); d3 = x)
            {
                if (x < d6 && x >= -d6) x = 0.0D;
                else if (x > 0.0D) x -= d6;
                else x += d6;
            }

            for (; z != 0.0D && world.getCollidingBoxes(s.box.offset(0.0D, -1.0D, z)).isEmpty(); d5 = z)
            {
                if (z < d6 && z >= -d6) z = 0.0D;
                else if (z > 0.0D) z -= d6;
                else z += d6;
            }

            for (; x != 0.0D && z != 0.0D && world.getCollidingBoxes(s.box.offset(x, -1.0D, z)).isEmpty(); d5 = z)
            {
                if (x < d6 && x >= -d6) x = 0.0D;
                else if (x > 0.0D) x -= d6;
                else x += d6;

                d3 = x;

                if (z < d6 && z >= -d6) z = 0.0D;
                else if (z > 0.0D) z -= d6;
                else z += d6;
            }
        }

        List<Box> list1 = world.getCollidingBoxes(s.box.addCoord(x, y, z));
        Box original = s.box;

        for (Box b : list1) y = b.calculateYOffset(s.box, y);
        s.box = s.box.offset(0.0D, y, 0.0D);
        boolean flag1 = s.onGround || d4 != y && d4 < 0.0D;

        for (Box b : list1) x = b.calculateXOffset(s.box, x);
        s.box = s.box.offset(x, 0.0D, 0.0D);

        for (Box b : list1) z = b.calculateZOffset(s.box, z);
        s.box = s.box.offset(0.0D, 0.0D, z);

        if (STEP_HEIGHT > 0.0F && flag1 && (d3 != x || d5 != z))
        {
            double d11 = x;
            double d7 = y;
            double d8 = z;
            Box afterPlain = s.box;
            s.box = original;
            y = (double)STEP_HEIGHT;
            List<Box> list = world.getCollidingBoxes(s.box.addCoord(d3, y, d5));
            Box bb4 = s.box;
            Box bb5 = bb4.addCoord(d3, 0.0D, d5);
            double d9 = y;
            for (Box b : list) d9 = b.calculateYOffset(bb5, d9);
            bb4 = bb4.offset(0.0D, d9, 0.0D);
            double d15 = d3;
            for (Box b : list) d15 = b.calculateXOffset(bb4, d15);
            bb4 = bb4.offset(d15, 0.0D, 0.0D);
            double d16 = d5;
            for (Box b : list) d16 = b.calculateZOffset(bb4, d16);
            bb4 = bb4.offset(0.0D, 0.0D, d16);

            Box bb14 = s.box;
            double d17 = y;
            for (Box b : list) d17 = b.calculateYOffset(bb14, d17);
            bb14 = bb14.offset(0.0D, d17, 0.0D);
            double d18 = d3;
            for (Box b : list) d18 = b.calculateXOffset(bb14, d18);
            bb14 = bb14.offset(d18, 0.0D, 0.0D);
            double d19 = d5;
            for (Box b : list) d19 = b.calculateZOffset(bb14, d19);
            bb14 = bb14.offset(0.0D, 0.0D, d19);

            double d20 = d15 * d15 + d16 * d16;
            double d10 = d18 * d18 + d19 * d19;

            if (d20 > d10)
            {
                x = d15;
                z = d16;
                y = -d9;
                s.box = bb4;
            }
            else
            {
                x = d18;
                z = d19;
                y = -d17;
                s.box = bb14;
            }

            for (Box b : list) y = b.calculateYOffset(s.box, y);
            s.box = s.box.offset(0.0D, y, 0.0D);

            if (d11 * d11 + d8 * d8 >= x * x + z * z)
            {
                x = d11;
                y = d7;
                z = d8;
                s.box = afterPlain;
            }
        }

        s.collidedHorizontally = d3 != x || d5 != z;
        s.collidedVertically = d4 != y;
        s.onGround = s.collidedVertically && d4 < 0.0D;

        Result r = new Result();
        r.clippedX = d3 != x;
        r.clippedY = d4 != y;
        r.clippedZ = d5 != z;
        r.requestedY = d4;
        r.movedX = x;
        r.movedY = y;
        r.movedZ = z;
        return r;
    }
}
