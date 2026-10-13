package com.tactile.client.live;

import java.util.ArrayList;
import java.util.List;

import com.tactile.core.Box;
import com.tactile.core.CollisionWorld;

import net.minecraft.block.Block;
import net.minecraft.block.BlockSlime;
import net.minecraft.block.BlockSoulSand;
import net.minecraft.block.BlockWeb;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeModContainer;

/**
 * CollisionWorld backed by the live client world. It calls the same query as vanilla's moveEntity
 * (World.getCollidingBoundingBoxes, including entity collision boxes and the world border), so the
 * ported collider sees exactly what vanilla's would. Gate A shows the port matches vanilla on
 * identical boxes. Collision queries are counted for profiling.
 */
final class WorldCollision implements CollisionWorld
{
    private final World world;
    private final Entity entity;
    long queries;

    WorldCollision(World world, Entity entity)
    {
        this.world = world;
        this.entity = entity;
    }

    @Override
    public List<Box> getCollidingBoxes(Box q)
    {
        ++queries;
        List<AxisAlignedBB> list = world.getCollidingBoundingBoxes(entity, new AxisAlignedBB(q.minX, q.minY, q.minZ, q.maxX, q.maxY, q.maxZ));
        List<Box> out = new ArrayList<Box>(list.size());
        for (AxisAlignedBB b : list) out.add(new Box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ));
        return out;
    }

    @Override
    public float slipperiness(int x, int y, int z)
    {
        return world.getBlockState(new BlockPos(x, y, z)).getBlock().slipperiness;
    }

    private static AxisAlignedBB aabb(Box b)
    {
        return new AxisAlignedBB(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
    }

    /**
     * Vanilla's own World.handleMaterialAcceleration on the player, so flowing water, Forge's
     * isEntityInsideMaterial hooks and modded fluids behave exactly as in vanilla. It adds the current to the
     * entity's motion; the motion is zeroed before and restored after, and the added part is returned.
     */
    @Override
    public double[] waterPush(Box bb)
    {
        double mx = entity.motionX, my = entity.motionY, mz = entity.motionZ;
        entity.motionX = entity.motionY = entity.motionZ = 0.0D;
        try
        {
            boolean in = world.handleMaterialAcceleration(aabb(bb), Material.water, entity);
            return in ? new double[] {entity.motionX, entity.motionY, entity.motionZ} : null;
        }
        finally
        {
            entity.motionX = mx;
            entity.motionY = my;
            entity.motionZ = mz;
        }
    }

    @Override
    public boolean lavaIn(Box bb)
    {
        return world.isMaterialInBB(aabb(bb), Material.lava);
    }

    @Override
    public boolean anyLiquid(Box bb)
    {
        return world.isAnyLiquid(aabb(bb));
    }

    /** Entity.moveEntity's block1: the block at (posX, posY - 0.2, posZ), or a fence/wall/gate below an air block. */
    @Override
    public int landingBlock(double posX, double posY, double posZ)
    {
        BlockPos pos = new BlockPos(MathHelper.floor_double(posX), MathHelper.floor_double(posY - 0.20000000298023224D), MathHelper.floor_double(posZ));
        Block block = world.getBlockState(pos).getBlock();
        // (a fence/wall/gate under an air block is never slime, so the air case needs no lookup below)
        return block instanceof BlockSlime ? LANDING_SLIME : LANDING_DEFAULT;
    }

    /** Entity.doBlockCollisions: blocks in the box contracted by 0.001. */
    @Override
    public int[] blockCollisions(Box box)
    {
        int x0 = MathHelper.floor_double(box.minX + 0.001D), y0 = MathHelper.floor_double(box.minY + 0.001D), z0 = MathHelper.floor_double(box.minZ + 0.001D);
        int x1 = MathHelper.floor_double(box.maxX - 0.001D), y1 = MathHelper.floor_double(box.maxY - 0.001D), z1 = MathHelper.floor_double(box.maxZ - 0.001D);
        int sand = 0, web = 0;
        for (int x = x0; x <= x1; ++x)
            for (int y = y0; y <= y1; ++y)
                for (int z = z0; z <= z1; ++z)
                {
                    Block b = world.getBlockState(new BlockPos(x, y, z)).getBlock();
                    if (b instanceof BlockSoulSand) ++sand;
                    else if (b instanceof BlockWeb) web = 1;
                }
        return new int[] {sand, web};
    }

    /** ForgeHooks.isLivingOnLadder for this box (both of Forge's ladder modes). */
    @Override
    public boolean onLadder(Box box)
    {
        EntityLivingBase living = (EntityLivingBase)entity;
        if (living instanceof EntityPlayer && ((EntityPlayer)living).isSpectator()) return false;
        if (!ForgeModContainer.fullBoundingBoxLadders)
        {
            BlockPos pos = new BlockPos(MathHelper.floor_double(box.centerX()), MathHelper.floor_double(box.minY), MathHelper.floor_double(box.centerZ()));
            return world.getBlockState(pos).getBlock().isLadder(world, pos, living);
        }
        int mX = MathHelper.floor_double(box.minX), mY = MathHelper.floor_double(box.minY), mZ = MathHelper.floor_double(box.minZ);
        for (int y2 = mY; y2 < box.maxY; y2++)
            for (int x2 = mX; x2 < box.maxX; x2++)
                for (int z2 = mZ; z2 < box.maxZ; z2++)
                {
                    BlockPos tmp = new BlockPos(x2, y2, z2);
                    if (world.getBlockState(tmp).getBlock().isLadder(world, tmp, living)) return true;
                }
        return false;
    }
}
