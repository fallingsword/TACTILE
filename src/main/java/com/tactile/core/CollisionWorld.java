package com.tactile.core;

import java.util.List;

/**
 * The world as the movement code sees it. In game this is backed by the client world (the same calls vanilla
 * makes); offline by a static {@link Course}.
 */
public interface CollisionWorld
{
    /** World.getCollidingBoundingBoxes for the player. */
    List<Box> getCollidingBoxes(Box query);

    /** Block.slipperiness of the block at the given cell. */
    float slipperiness(int x, int y, int z);

    /**
     * World.handleMaterialAcceleration(bb, water, player): null if the box is not in water, else the water current
     * vanilla adds to the player's motion (zero for still water). {@code bb} is {@link Box#waterTestBox()}.
     */
    default double[] waterPush(Box bb)
    {
        return null;
    }

    /** World.isMaterialInBB(bb, lava); {@code bb} is {@link Box#lavaTestBox()}. */
    default boolean lavaIn(Box bb)
    {
        return false;
    }

    /** World.isAnyLiquid(bb). */
    default boolean anyLiquid(Box bb)
    {
        return false;
    }

    /** EntityLivingBase.isOnLadder for a player with this box (ForgeHooks.isLivingOnLadder). */
    default boolean onLadder(Box box)
    {
        return false;
    }

    int LANDING_DEFAULT = 0, LANDING_SLIME = 1;

    /**
     * The block Entity.moveEntity calls onLanded and the walking callback on: the block at (posX, posY - 0.2, posZ),
     * or a fence/wall below an air block. LANDING_SLIME for slime, else LANDING_DEFAULT.
     */
    default int landingBlock(double posX, double posY, double posZ)
    {
        return LANDING_DEFAULT;
    }

    /**
     * Entity.doBlockCollisions for this box: {number of soul sand blocks it touches (each multiplies horizontal motion
     * by 0.4), 1 if it touches a cobweb}.
     */
    default int[] blockCollisions(Box box)
    {
        return new int[] {0, 0};
    }
}
