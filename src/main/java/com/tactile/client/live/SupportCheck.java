package com.tactile.client.live;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBasePressurePlate;
import net.minecraft.block.BlockButton;
import net.minecraft.block.BlockCactus;
import net.minecraft.block.BlockFarmland;
import net.minecraft.block.BlockFire;
import net.minecraft.block.BlockHay;
import net.minecraft.block.BlockRedstoneOre;
import net.minecraft.block.BlockTripWire;
import net.minecraft.block.material.Material;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/**
 * Decides whether the high-resolution engine may control the player; if not, it returns the fallback
 * reason. The engine supports on-foot movement on ordinary blocks only.
 *
 * <p>Block side effects (Entity.doBlockCollisions, Block.onLanded, Block.onFallenUpon) run per move in
 * vanilla. Repeating them per substep would multiply them (soul sand slows motion x0.4 per call), and
 * skipping them would change behavior. So any block near the player whose class overrides one of those
 * callbacks is unsupported, unless it is on an allowlist of blocks whose callbacks have no client-side
 * effect on motion (pressure plates, tripwire, buttons, cactus/fire damage, farmland trampling,
 * hay-bale fall damage, redstone-ore glow).
 */
final class SupportCheck
{
    private static final Map<Class<?>, Boolean> SPECIAL = new HashMap<Class<?>, Boolean>();
    // BlockStairs only forwards onEntityCollidedWithBlock to its material block (stone, wood, brick...),
    // none of which affects motion in vanilla.
    private static final Class<?>[] ALLOWED = {BlockBasePressurePlate.class, BlockTripWire.class, BlockButton.class, BlockCactus.class,
        BlockFire.class, BlockFarmland.class, BlockHay.class, BlockRedstoneOre.class, net.minecraft.block.BlockStairs.class};
    private static final java.lang.reflect.Field IS_IN_WEB = ReflectionHelper.findField(net.minecraft.entity.Entity.class, "isInWeb", "field_70134_J");

    private SupportCheck()
    {
    }

    /**
     * Null if supported, else a short reason. {@code media}: water, lava, ladders, vines, soul sand, slime, cobwebs
     * and creative flight are modeled (GRID contacts); otherwise they fall back to vanilla.
     */
    static String unsupportedReason(EntityPlayerSP p, World world, boolean media)
    {
        if (p.isDead || p.getHealth() <= 0.0F) return "dead";
        if (p.isRiding()) return "riding";
        // Creative flight is modeled (GRID). Flying while on a ladder (ladder clamps on a flying move) is not.
        if (p.capabilities.isFlying && (!media || p.isOnLadder())) return "flying";
        if (p.isSpectator() || p.noClip) return "spectator/noclip";
        if (p.isPlayerSleeping()) return "sleeping";
        // Using an item (eating, drinking, drawing a bow, blocking) is modeled: input x0.2, no sprint start (0.8.0).
        // Vanilla effects (ids 1-23) are modeled or irrelevant: speed and slowness act through the movement-speed
        // attribute, which the engine reads every tick; jump boost and blindness are modeled; the rest
        // (night vision, haste, regeneration, ...) do not touch movement. A modded effect may move the player
        // in ways the engine does not know about.
        for (net.minecraft.potion.PotionEffect e : p.getActivePotionEffects())
            if (e.getPotionID() < 1 || e.getPotionID() > 23) return "effect " + e.getEffectName();
        if (!media && p.isInWater()) return "in water";
        if (!media && p.isInLava()) return "in lava";
        if (!media && p.isOnLadder()) return "on ladder/vine";
        try
        {
            if (!media && IS_IN_WEB.getBoolean(p)) return "in web";
        }
        catch (IllegalAccessException e)
        {
            return "reflection failure";
        }
        if (!world.isBlockLoaded(new BlockPos(p.posX, 0.0D, p.posZ))) return "chunk not loaded";

        // Blocks overlapping the box, grown 0.3 sideways and 1.0 downward.
        AxisAlignedBB bb = p.getEntityBoundingBox();
        int x0 = MathHelper.floor_double(bb.minX - 0.3D), x1 = MathHelper.floor_double(bb.maxX + 0.3D);
        int y0 = MathHelper.floor_double(bb.minY - 1.0D), y1 = MathHelper.floor_double(bb.maxY);
        int z0 = MathHelper.floor_double(bb.minZ - 0.3D), z1 = MathHelper.floor_double(bb.maxZ + 0.3D);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; ++x)
            for (int y = y0; y <= y1; ++y)
                for (int z = z0; z <= z1; ++z)
                {
                    pos.set(x, y, z);
                    Block b = world.getBlockState(pos).getBlock();
                    if (b.getMaterial() == Material.air) continue;
                    if (!media && b.getMaterial().isLiquid()) return "liquid nearby";
                    // soul sand, slime and cobwebs are modeled with GRID contacts (their exact vanilla classes only)
                    if (media && (b.getClass() == net.minecraft.block.BlockSoulSand.class || b.getClass() == net.minecraft.block.BlockSlime.class
                        || b.getClass() == net.minecraft.block.BlockWeb.class)) continue;
                    if (isSpecial(b.getClass())) return "block " + Block.blockRegistry.getNameForObject(b);
                }
        return null;
    }

    private static boolean isSpecial(Class<?> c)
    {
        Boolean cached = SPECIAL.get(c);
        if (cached != null) return cached;
        boolean special = false;
        boolean allowed = false;
        for (Class<?> a : ALLOWED) if (a.isAssignableFrom(c)) allowed = true;
        if (!allowed)
        {
            for (Class<?> k = c; k != null && k != Block.class; k = k.getSuperclass())
            {
                for (Method m : k.getDeclaredMethods())
                {
                    String n = m.getName();
                    if (n.equals("onEntityCollidedWithBlock") || n.equals("func_176199_a") || n.equals("func_180634_a")
                        || n.equals("onLanded") || n.equals("func_176216_a") || n.equals("onFallenUpon") || n.equals("func_180658_a"))
                    {
                        special = true;
                    }
                }
            }
        }
        SPECIAL.put(c, special);
        return special;
    }
}
