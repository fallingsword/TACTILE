package com.tactile.core;

/**
 * Immutable port of net.minecraft.util.AxisAlignedBB (1.8.9), limited to the operations used by
 * Entity.moveEntity. Clipping semantics are copied exactly, including the strict inequalities.
 */
public final class Box
{
    public final double minX, minY, minZ, maxX, maxY, maxZ;

    public Box(double x1, double y1, double z1, double x2, double y2, double z2)
    {
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    /** The player box exactly as Entity.setPosition builds it (width/height are floats in vanilla). */
    public static Box forPlayerAt(double x, double y, double z)
    {
        float halfWidth = 0.6F / 2.0F;
        float height = 1.8F;
        return new Box(x - (double)halfWidth, y, z - (double)halfWidth, x + (double)halfWidth, y + (double)height, z + (double)halfWidth);
    }

    public Box addCoord(double x, double y, double z)
    {
        double d0 = minX, d1 = minY, d2 = minZ, d3 = maxX, d4 = maxY, d5 = maxZ;
        if (x < 0.0D) d0 += x; else if (x > 0.0D) d3 += x;
        if (y < 0.0D) d1 += y; else if (y > 0.0D) d4 += y;
        if (z < 0.0D) d2 += z; else if (z > 0.0D) d5 += z;
        return new Box(d0, d1, d2, d3, d4, d5);
    }

    public Box offset(double x, double y, double z)
    {
        return new Box(minX + x, minY + y, minZ + z, maxX + x, maxY + y, maxZ + z);
    }

    /** AxisAlignedBB.expand: grows (or, with negative values, shrinks) each side by the given amount. */
    public Box expand(double x, double y, double z)
    {
        return new Box(minX - x, minY - y, minZ - z, maxX + x, maxY + y, maxZ + z);
    }

    /** AxisAlignedBB.contract. */
    public Box contract(double x, double y, double z)
    {
        return new Box(minX + x, minY + y, minZ + z, maxX - x, maxY - y, maxZ - z);
    }

    /** The box Entity.handleWaterMovement tests for water. */
    public Box waterTestBox()
    {
        return expand(0.0D, -0.4000000059604645D, 0.0D).contract(0.001D, 0.001D, 0.001D);
    }

    /** The box Entity.isInLava tests. */
    public Box lavaTestBox()
    {
        return expand(-0.10000000149011612D, -0.4000000059604645D, -0.10000000149011612D);
    }

    public boolean intersectsWith(Box other)
    {
        return other.maxX > minX && other.minX < maxX
            && other.maxY > minY && other.minY < maxY
            && other.maxZ > minZ && other.minZ < maxZ;
    }

    /** {@code this} is the obstacle, {@code other} the moving box (vanilla argument order). */
    public double calculateXOffset(Box other, double offsetX)
    {
        if (other.maxY > minY && other.minY < maxY && other.maxZ > minZ && other.minZ < maxZ)
        {
            if (offsetX > 0.0D && other.maxX <= minX)
            {
                double d1 = minX - other.maxX;
                if (d1 < offsetX) offsetX = d1;
            }
            else if (offsetX < 0.0D && other.minX >= maxX)
            {
                double d0 = maxX - other.minX;
                if (d0 > offsetX) offsetX = d0;
            }
        }
        return offsetX;
    }

    public double calculateYOffset(Box other, double offsetY)
    {
        if (other.maxX > minX && other.minX < maxX && other.maxZ > minZ && other.minZ < maxZ)
        {
            if (offsetY > 0.0D && other.maxY <= minY)
            {
                double d1 = minY - other.maxY;
                if (d1 < offsetY) offsetY = d1;
            }
            else if (offsetY < 0.0D && other.minY >= maxY)
            {
                double d0 = maxY - other.minY;
                if (d0 > offsetY) offsetY = d0;
            }
        }
        return offsetY;
    }

    public double calculateZOffset(Box other, double offsetZ)
    {
        if (other.maxX > minX && other.minX < maxX && other.maxY > minY && other.minY < maxY)
        {
            if (offsetZ > 0.0D && other.maxZ <= minZ)
            {
                double d1 = minZ - other.maxZ;
                if (d1 < offsetZ) offsetZ = d1;
            }
            else if (offsetZ < 0.0D && other.minZ >= maxZ)
            {
                double d0 = maxZ - other.minZ;
                if (d0 > offsetZ) offsetZ = d0;
            }
        }
        return offsetZ;
    }

    public double centerX()
    {
        return (minX + maxX) / 2.0D;
    }

    public double centerZ()
    {
        return (minZ + maxZ) / 2.0D;
    }

    @Override
    public String toString()
    {
        return "Box[" + minX + ", " + minY + ", " + minZ + " -> " + maxX + ", " + maxY + ", " + maxZ + "]";
    }
}
