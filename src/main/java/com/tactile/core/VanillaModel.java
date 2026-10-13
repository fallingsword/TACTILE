package com.tactile.core;

/**
 * Port of one vanilla 1.8.9 EntityPlayerSP tick, limited to the supported state: on foot, not flying,
 * not riding, no liquids/ladders/webs, no potion effects, food above 6. (Item use is modeled since 0.8.0.)
 * Statement order and float/double casts follow the source (see docs/SOURCE_AUDIT.md section 3).
 *
 * This is the 20 Hz reference that the substep model must reproduce at h = 1. It is validated
 * against traces recorded from the real client, not against itself.
 */
public final class VanillaModel
{
    private VanillaModel()
    {
    }

    public static void tick(PlayerState s, TickInput in, CollisionWorld world)
    {
        s.yaw = in.yaw;

        // Entity.onEntityUpdate -> handleWaterMovement: the water current and the inWater flag for this tick
        double[] push = world.waterPush(s.box.waterTestBox());
        s.inWater = push != null;
        if (push != null && !s.flying) // EntityPlayer.isPushedByWater: not while flying
        {
            s.motionX += push[0];
            s.motionY += push[1];
            s.motionZ += push[2];
        }

        // EntityPlayerSP.onLivingUpdate
        if (s.sprintingTicksLeft > 0)
        {
            --s.sprintingTicksLeft;
            if (s.sprintingTicksLeft == 0) s.setSprinting(false);
        }
        if (s.sprintToggleTimer > 0) --s.sprintToggleTimer;

        boolean prevSneak = s.prevSneak;
        boolean prevForwardHigh = s.prevForwardHigh;
        s.usingItem = in.use; // started or stopped by this tick's click handling, before the player update
        float use = itemUseFactor(s);
        float moveForward = in.moveForward() * use;
        float moveStrafe = in.moveStrafe() * use;
        updateSprint(s, in, moveForward, prevSneak, prevForwardHigh);
        s.prevJump = in.jump;
        s.prevSneak = in.sneak;
        s.prevForwardHigh = moveForward >= 0.8F;
        if (s.flying) flightPush(s, in);

        // EntityLivingBase.onLivingUpdate
        if (s.jumpTicks > 0) --s.jumpTicks;
        applyMotionCutoff(s);

        boolean lava = !s.inWater && world.lavaIn(s.box.lavaTestBox());
        if (in.jump)
        {
            if (s.inWater || lava) s.motionY += LIQUID_JUMP; // updateAITick / handleJumpLava
            else if (s.onGround && s.jumpTicks == 0)
            {
                jump(s);
                s.jumpTicks = 10;
            }
        }
        else
        {
            s.jumpTicks = 0;
        }

        float strafe = moveStrafe * 0.98F;
        float forward = moveForward * 0.98F;

        // EntityLivingBase.moveEntityWithHeading (EntityPlayer's flying override runs the land branch)
        if (s.inWater && !s.flying) liquidMove(s, world, in, strafe, forward, true);
        else if (lava && !s.flying) liquidMove(s, world, in, strafe, forward, false);
        else landMove(s, world, in, strafe, forward);

        // EntityPlayer.onLivingUpdate, after the move
        s.jumpMovementFactor = airAcceleration(s.sprinting);
        // EntityPlayerSP.onLivingUpdate: landing ends flight
        if (s.onGround && s.flying) s.flying = false;
    }

    /** EntityPlayerSP.onLivingUpdate while flying: sneak descends, jump ascends (before the cutoff). */
    static void flightPush(PlayerState s, TickInput in)
    {
        if (in.sneak) s.motionY -= (double)(s.flySpeed * 3.0F);
        if (in.jump) s.motionY += (double)(s.flySpeed * 3.0F);
    }

    /** EntityPlayer.moveEntityWithHeading while flying: the air acceleration is the fly speed (doubled sprinting). */
    static float flyAcceleration(PlayerState s)
    {
        return s.flySpeed * (float)(s.sprinting ? 2 : 1);
    }

    /** Flying: the vertical speed after the move is 0.6 x the speed it moved with (EntityPlayer: motionY = d3 * 0.6). */
    public static final double FLY_DRAG = 0.6D;
    /** Cobweb move scale (Entity.moveEntity while isInWeb) and soul sand's horizontal factor per block touched. */
    public static final double WEB_H = 0.25D, WEB_V = 0.05000000074505806D, SOUL_SAND = 0.4D;

    /**
     * The tail of Entity.moveEntity after the collision: clipped axes stop, onLanded on the block under the feet
     * (slime bounces unless sneaking; others stop), the walking callback on that block (slime slows walking), then
     * doBlockCollisions (soul sand x0.4 per block touched; a cobweb flags the next move). {@code guard}: moveEntity's
     * sneak flag (on the ground and sneaking before the move).
     */
    static void moveResponses(PlayerState s, CollisionWorld world, Collider.Result r, TickInput in, boolean guard)
    {
        if (r.clippedX) s.motionX = 0.0D;
        if (r.clippedZ) s.motionZ = 0.0D;
        int landing = world.landingBlock(s.posX(), s.posY(), s.posZ());
        if (r.clippedY)
        {
            if (landing == CollisionWorld.LANDING_SLIME && !in.sneak)
            {
                if (s.motionY < 0.0D) s.motionY = -s.motionY;
            }
            else s.motionY = 0.0D; // Block.onLanded default
        }
        if (!s.flying && !guard && s.onGround && landing == CollisionWorld.LANDING_SLIME && Math.abs(s.motionY) < 0.1D && !in.sneak)
        {
            double d0 = 0.4D + Math.abs(s.motionY) * 0.2D;
            s.motionX *= d0;
            s.motionZ *= d0;
        }
        int[] bc = world.blockCollisions(s.box);
        for (int i = 0; i < bc[0]; ++i)
        {
            s.motionX *= SOUL_SAND;
            s.motionZ *= SOUL_SAND;
        }
        if (bc[1] != 0 && !s.flying) s.inWeb = true; // EntityPlayer.setInWeb: not while flying
    }

    /** Entity.moveEntity: the requested displacement, scaled (and motion zeroed) when the last move touched a cobweb. */
    static Collider.Result move(PlayerState s, CollisionWorld world, TickInput in)
    {
        double x = s.motionX, y = s.motionY, z = s.motionZ;
        if (s.inWeb)
        {
            s.inWeb = false;
            x *= WEB_H;
            y *= WEB_V;
            z *= WEB_H;
            s.motionX = s.motionY = s.motionZ = 0.0D;
        }
        boolean guard = s.onGround && in.sneak;
        Collider.Result r = Collider.move(s, world, x, y, z, in.sneak);
        moveResponses(s, world, r, in, guard);
        return r;
    }

    /** Swimming upward while jump is held (EntityLivingBase.updateAITick / handleJumpLava). */
    public static final double LIQUID_JUMP = 0.03999999910593033D;
    /** Vertical drag in water (float 0.8 widened) and lava; liquid gravity; the jump-out-of-liquid impulse. */
    public static final double WATER_DRAG = 0.800000011920929D, LAVA_DRAG = 0.5D, LIQUID_GRAVITY = 0.02D, LIQUID_EXIT = 0.30000001192092896D;
    /** Ladder clamps: horizontal speed (float 0.15 widened), vertical floor; the climbing speed. */
    public static final double LADDER_SPEED = (double)0.15F, LADDER_FALL = 0.15D, LADDER_CLIMB = 0.2D;

    /** Land branch, with the ladder rules; flying runs it with the fly acceleration and replaces the vertical result. */
    static void landMove(PlayerState s, CollisionWorld world, TickInput in, float strafe, float forward)
    {
        double d3 = s.motionY;
        float f4 = groundFriction(s, world);
        float f = 0.16277136F / (f4 * f4 * f4);
        float f5 = s.onGround ? s.aiMoveSpeed() * f : (s.flying ? flyAcceleration(s) : s.jumpMovementFactor);
        moveFlying(s, strafe, forward, f5);
        f4 = groundFriction(s, world);

        if (world.onLadder(s.box))
        {
            s.motionX = clamp(s.motionX, -LADDER_SPEED, LADDER_SPEED);
            s.motionZ = clamp(s.motionZ, -LADDER_SPEED, LADDER_SPEED);
            if (s.motionY < -LADDER_FALL) s.motionY = -LADDER_FALL;
            if (in.sneak && s.motionY < 0.0D) s.motionY = 0.0D;
        }

        move(s, world, in);

        if (s.collidedHorizontally && world.onLadder(s.box)) s.motionY = LADDER_CLIMB;

        s.motionY -= 0.08D;
        s.motionY *= 0.9800000190734863D;
        s.motionX *= (double)f4;
        s.motionZ *= (double)f4;
        if (s.flying) s.motionY = d3 * FLY_DRAG;
    }

    /** Water branch (with depth strider) or lava branch. */
    static void liquidMove(PlayerState s, CollisionWorld world, TickInput in, float strafe, float forward, boolean water)
    {
        double d0 = s.posY();
        float[] fa = water ? waterFrictionAndAccel(s) : new float[] {0.5F, 0.02F};
        float f1 = fa[0];
        moveFlying(s, strafe, forward, fa[1]);

        move(s, world, in);

        double h = water ? (double)f1 : LAVA_DRAG;
        s.motionX *= h;
        s.motionY *= water ? WATER_DRAG : LAVA_DRAG;
        s.motionZ *= h;
        s.motionY -= LIQUID_GRAVITY;
        if (s.collidedHorizontally && offsetPositionInLiquid(s, world, s.motionX, s.motionY + 0.6000000238418579D - s.posY() + d0, s.motionZ))
            s.motionY = LIQUID_EXIT;
    }

    /** Entity.isOffsetPositionInLiquid: the offset box is free of collision boxes and of liquid. */
    static boolean offsetPositionInLiquid(PlayerState s, CollisionWorld world, double x, double y, double z)
    {
        Box b = s.box.offset(x, y, z);
        return world.getCollidingBoxes(b).isEmpty() && !world.anyLiquid(b);
    }

    static double clamp(double v, double lo, double hi)
    {
        return v < lo ? lo : (v > hi ? hi : v); // MathHelper.clamp_double
    }

    /** Water branch horizontal friction and acceleration (depth strider), as liquidMove computes them. */
    static float[] waterFrictionAndAccel(PlayerState s)
    {
        float f1 = 0.8F, f2 = 0.02F;
        float f3 = (float)s.depthStrider;
        if (f3 > 3.0F) f3 = 3.0F;
        if (!s.onGround) f3 *= 0.5F;
        if (f3 > 0.0F)
        {
            f1 += (0.54600006F - f1) * f3 / 3.0F;
            f2 += (s.aiMoveSpeed() * 1.0F - f2) * f3 / 3.0F;
        }
        return new float[] {f1, f2};
    }

    /**
     * EntityPlayerSP.onLivingUpdate, right after the input update: while using an item (and not riding) the movement
     * input is multiplied by 0.2 in float and the sprint double-tap timer is cleared. Returns the input factor.
     */
    static float itemUseFactor(PlayerState s)
    {
        if (!s.usingItem) return 1.0F;
        s.sprintToggleTimer = 0;
        return 0.2F;
    }

    /** The three sprint rules of EntityPlayerSP.onLivingUpdate (L811-831): food/flying, item use and blindness. */
    static void updateSprint(PlayerState s, TickInput in, float moveForward, boolean prevSneak, boolean prevForwardHigh)
    {
        float threshold = 0.8F;
        boolean food = s.foodAllowsSprint; // flag3: food level > 6 or allowFlying
        if (s.onGround && !prevSneak && !prevForwardHigh && moveForward >= threshold && !s.sprinting && food && !s.usingItem && !s.blind)
        {
            if (s.sprintToggleTimer <= 0 && !in.sprint) s.sprintToggleTimer = 7;
            else s.setSprinting(true);
        }
        if (!s.sprinting && moveForward >= threshold && food && !s.usingItem && !s.blind && in.sprint) s.setSprinting(true);
        if (s.sprinting && (moveForward < threshold || s.collidedHorizontally || !food)) s.setSprinting(false);
    }

    static void applyMotionCutoff(PlayerState s)
    {
        if (Math.abs(s.motionX) < 0.005D) s.motionX = 0.0D;
        if (Math.abs(s.motionY) < 0.005D) s.motionY = 0.0D;
        if (Math.abs(s.motionZ) < 0.005D) s.motionZ = 0.0D;
    }

    /** EntityLivingBase.jump without potion effects. */
    static void jump(PlayerState s)
    {
        s.motionY = (double)0.42F;
        if (s.jumpBoost >= 0) s.motionY += (double)((float)(s.jumpBoost + 1) * 0.1F);
        if (s.sprinting)
        {
            float f = s.yaw * 0.017453292F;
            s.motionX -= (double)(McMath.sin(f) * 0.2F);
            s.motionZ += (double)(McMath.cos(f) * 0.2F);
        }
    }

    static float groundFriction(PlayerState s, CollisionWorld world)
    {
        if (!s.onGround) return 0.91F;
        int bx = McMath.floor(s.posX());
        int by = McMath.floor(s.box.minY) - 1;
        int bz = McMath.floor(s.posZ());
        return world.slipperiness(bx, by, bz) * 0.91F;
    }

    public static float airAcceleration(boolean sprinting)
    {
        float speedInAir = 0.02F;
        return sprinting ? (float)((double)speedInAir + (double)speedInAir * 0.3D) : speedInAir;
    }

    /** Entity.moveFlying. */
    static void moveFlying(PlayerState s, float strafe, float forward, float friction)
    {
        float[] d = flyingDelta(s.yaw, strafe, forward, friction);
        s.motionX += (double)d[0];
        s.motionZ += (double)d[1];
    }

    /** The (x, z) velocity increment Entity.moveFlying would add, in float as vanilla computes it. */
    static float[] flyingDelta(float yaw, float strafe, float forward, float friction)
    {
        float f = strafe * strafe + forward * forward;
        if (f >= 1.0E-4F)
        {
            f = McMath.sqrtFloat(f);
            if (f < 1.0F) f = 1.0F;
            f = friction / f;
            strafe = strafe * f;
            forward = forward * f;
            float f1 = McMath.sin(yaw * (float)Math.PI / 180.0F);
            float f2 = McMath.cos(yaw * (float)Math.PI / 180.0F);
            return new float[] {strafe * f2 - forward * f1, forward * f2 + strafe * f1};
        }
        return new float[] {0.0F, 0.0F};
    }
}
