package com.tactile.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A small static block layout. It is the single source of truth for test geometry: the in-game
 * baseline runner places exactly these blocks, and the offline harness collides against them.
 *
 * <p>Liquids are source blocks only, and courses keep them enclosed by solid blocks or other sources on every
 * horizontal side, so vanilla's flow vector is zero and the water current needs no simulation offline.
 */
public final class Course implements CollisionWorld
{
    public enum Kind { SOLID, WATER, LAVA, LADDER, VINE, SOUL_SAND, SLIME, WEB }

    public enum Shape
    {
        FULL(Kind.SOLID, 0, 0, 0, 1, 1, 1),
        BOTTOM_SLAB(Kind.SOLID, 0, 0, 0, 1, 0.5D, 1),
        /** Water source: no collision box. */
        WATER(Kind.WATER, 0, 0, 0, 0, 0, 0),
        /** Lava source: no collision box. */
        LAVA(Kind.LAVA, 0, 0, 0, 0, 0, 0),
        /** Ladders by facing (BlockLadder.setBlockBoundsBasedOnState, f = 0.125). */
        LADDER_NORTH(Kind.LADDER, 0, 0, 1.0D - 0.125D, 1, 1, 1),
        LADDER_SOUTH(Kind.LADDER, 0, 0, 0, 1, 1, 0.125D),
        LADDER_WEST(Kind.LADDER, 1.0D - 0.125D, 0, 0, 1, 1, 1),
        LADDER_EAST(Kind.LADDER, 0, 0, 0, 0.125D, 1, 1),
        /** Vine: climbable, no collision box. */
        VINE(Kind.VINE, 0, 0, 0, 0, 0, 0),
        /** Soul sand: 0.875 high; horizontal motion x 0.4 per block touched, every move. */
        SOUL_SAND(Kind.SOUL_SAND, 0, 0, 0, 1, 0.875D, 1),
        /** Slime block: full cube, slipperiness 0.8, bounces landings, slows walking. */
        SLIME(Kind.SLIME, 0, 0, 0, 1, 1, 1),
        /** Cobweb: no collision box; scales the next move and zeroes motion. */
        WEB(Kind.WEB, 0, 0, 0, 0, 0, 0);

        public final Kind kind;
        public final double minX, minY, minZ, maxX, maxY, maxZ;

        Shape(Kind kind, double minX, double minY, double minZ, double maxX, double maxY, double maxZ)
        {
            this.kind = kind;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        boolean collides()
        {
            return kind == Kind.SOLID || kind == Kind.LADDER || kind == Kind.SOUL_SAND || kind == Kind.SLIME;
        }

        boolean liquid()
        {
            return kind == Kind.WATER || kind == Kind.LAVA;
        }
    }

    public static final class Cell
    {
        public final int x, y, z;
        public final Shape shape;

        Cell(int x, int y, int z, Shape shape)
        {
            this.x = x;
            this.y = y;
            this.z = z;
            this.shape = shape;
        }
    }

    private final Map<Long, Cell> cells = new HashMap<Long, Cell>();
    private final List<Cell> ordered = new ArrayList<Cell>();

    private static long key(int x, int y, int z)
    {
        return ((long)(x & 0x3FFFFFF) << 38) | ((long)(y & 0xFFF) << 26) | (long)(z & 0x3FFFFFF);
    }

    public Course set(int x, int y, int z, Shape shape)
    {
        Cell cell = new Cell(x, y, z, shape);
        Cell old = cells.put(key(x, y, z), cell);
        if (old != null) ordered.remove(old);
        ordered.add(cell);
        return this;
    }

    /** Fills the inclusive cuboid with {@code shape}. */
    public Course fill(int x1, int y1, int z1, int x2, int y2, int z2, Shape shape)
    {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); ++x)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); ++y)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); ++z)
                    set(x, y, z, shape);
        return this;
    }

    public List<Cell> cells()
    {
        return Collections.unmodifiableList(ordered);
    }

    private Shape shapeAt(int x, int y, int z)
    {
        Cell c = cells.get(key(x, y, z));
        return c == null ? null : c.shape;
    }

    /** Same cell iteration bounds as World.getCollidingBoundingBoxes (y starts one below). */
    @Override
    public List<Box> getCollidingBoxes(Box query)
    {
        List<Box> list = new ArrayList<Box>();
        int x0 = McMath.floor(query.minX), x1 = McMath.floor(query.maxX + 1.0D);
        int y0 = McMath.floor(query.minY), y1 = McMath.floor(query.maxY + 1.0D);
        int z0 = McMath.floor(query.minZ), z1 = McMath.floor(query.maxZ + 1.0D);

        for (int x = x0; x < x1; ++x)
            for (int z = z0; z < z1; ++z)
                for (int y = y0 - 1; y < y1; ++y)
                {
                    Shape s = shapeAt(x, y, z);
                    if (s == null || !s.collides()) continue;
                    Box box = new Box(x + s.minX, y + s.minY, z + s.minZ, x + s.maxX, y + s.maxY, z + s.maxZ);
                    if (query.intersectsWith(box)) list.add(box);
                }
        return list;
    }

    @Override
    public float slipperiness(int x, int y, int z)
    {
        Shape s = shapeAt(x, y, z);
        return s != null && s.kind == Kind.SLIME ? 0.8F : 0.6F; // slime 0.8; stone, slab, ladder, soul sand and air the Block default
    }

    /**
     * World.handleMaterialAcceleration for water: in water if a water cell in the box's cell range reaches the
     * range's (integer) top bound. Source blocks have level 0, height (0 + 1) / 9. Flow is zero (enclosed sources).
     */
    @Override
    public double[] waterPush(Box bb)
    {
        int i = McMath.floor(bb.minX), j = McMath.floor(bb.maxX + 1.0D);
        int k = McMath.floor(bb.minY), l = McMath.floor(bb.maxY + 1.0D);
        int i1 = McMath.floor(bb.minZ), j1 = McMath.floor(bb.maxZ + 1.0D);
        boolean in = false;
        for (int x = i; x < j; ++x)
            for (int y = k; y < l; ++y)
                for (int z = i1; z < j1; ++z)
                {
                    Shape s = shapeAt(x, y, z);
                    if (s == null || s.kind != Kind.WATER) continue;
                    double d0 = (double)((float)(y + 1) - (float)(0 + 1) / 9.0F);
                    if ((double)l >= d0) in = true;
                }
        return in ? new double[] {0.0D, 0.0D, 0.0D} : null;
    }

    /** World.isMaterialInBB(bb, lava). */
    @Override
    public boolean lavaIn(Box bb)
    {
        int i = McMath.floor(bb.minX), j = McMath.floor(bb.maxX + 1.0D);
        int k = McMath.floor(bb.minY), l = McMath.floor(bb.maxY + 1.0D);
        int i1 = McMath.floor(bb.minZ), j1 = McMath.floor(bb.maxZ + 1.0D);
        for (int x = i; x < j; ++x)
            for (int y = k; y < l; ++y)
                for (int z = i1; z < j1; ++z)
                {
                    Shape s = shapeAt(x, y, z);
                    if (s != null && s.kind == Kind.LAVA) return true;
                }
        return false;
    }

    /** World.isAnyLiquid (inclusive integer bounds, unlike the two above). */
    @Override
    public boolean anyLiquid(Box bb)
    {
        int i = McMath.floor(bb.minX), j = McMath.floor(bb.maxX);
        int k = McMath.floor(bb.minY), l = McMath.floor(bb.maxY);
        int i1 = McMath.floor(bb.minZ), j1 = McMath.floor(bb.maxZ);
        for (int x = i; x <= j; ++x)
            for (int y = k; y <= l; ++y)
                for (int z = i1; z <= j1; ++z)
                {
                    Shape s = shapeAt(x, y, z);
                    if (s != null && s.liquid()) return true;
                }
        return false;
    }

    /** Entity.moveEntity's block1: the block at (posX, posY - 0.2, posZ). Courses have no fences or walls. */
    @Override
    public int landingBlock(double posX, double posY, double posZ)
    {
        Shape s = shapeAt(McMath.floor(posX), McMath.floor(posY - 0.20000000298023224D), McMath.floor(posZ));
        return s != null && s.kind == Kind.SLIME ? LANDING_SLIME : LANDING_DEFAULT;
    }

    /** Entity.doBlockCollisions: blocks in the box contracted by 0.001 (inclusive integer bounds). */
    @Override
    public int[] blockCollisions(Box box)
    {
        int x0 = McMath.floor(box.minX + 0.001D), y0 = McMath.floor(box.minY + 0.001D), z0 = McMath.floor(box.minZ + 0.001D);
        int x1 = McMath.floor(box.maxX - 0.001D), y1 = McMath.floor(box.maxY - 0.001D), z1 = McMath.floor(box.maxZ - 0.001D);
        int sand = 0, web = 0;
        for (int x = x0; x <= x1; ++x)
            for (int y = y0; y <= y1; ++y)
                for (int z = z0; z <= z1; ++z)
                {
                    Shape s = shapeAt(x, y, z);
                    if (s == null) continue;
                    if (s.kind == Kind.SOUL_SAND) ++sand;
                    if (s.kind == Kind.WEB) web = 1;
                }
        return new int[] {sand, web};
    }

    /** EntityLivingBase.isOnLadder with Forge's default (fullBoundingBoxLadders off): the block at the feet. */
    @Override
    public boolean onLadder(Box box)
    {
        Shape s = shapeAt(McMath.floor(box.centerX()), McMath.floor(box.minY), McMath.floor(box.centerZ()));
        return s != null && (s.kind == Kind.LADDER || s.kind == Kind.VINE);
    }
}
