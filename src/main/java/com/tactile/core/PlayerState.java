package com.tactile.core;

/**
 * The movement-relevant subset of EntityPlayerSP state. The bounding box is the authoritative
 * position (vanilla derives posX/Y/Z from it after every move), so it is stored directly.
 */
public final class PlayerState
{
    public Box box;
    public double motionX, motionY, motionZ;
    public boolean onGround, collidedHorizontally, collidedVertically;
    public boolean sprinting;
    /** EntityLivingBase.jumpTicks. Kept as a double so the substep model can count normalized time. */
    public double jumpTicks;
    /** EntityPlayerSP.sprintToggleTimer (double-tap window). A double for the same reason as jumpTicks. */
    public double sprintToggleTimer;
    public int sprintingTicksLeft;
    /** Set after each tick's move from that tick's sprint state; used as air acceleration next tick. */
    public float jumpMovementFactor = 0.02F;
    /** movementInput.jump / sneak / (moveForward >= 0.8) from the previous tick. */
    public boolean prevJump, prevSneak, prevForwardHigh;
    public float yaw;
    /**
     * Movement-speed attribute value without and with the sprint modifier (EntityLivingBase.getAIMoveSpeed). The
     * defaults are vanilla's base speed. Live play reads them from the entity's attribute each tick, so potions
     * (speed, slowness) and server-set speeds are included, computed exactly as vanilla computes them.
     */
    public float walkSpeed = (float)0.10000000149011612D;
    public float sprintSpeed = (float)(0.10000000149011612D * (1.0D + 0.30000001192092896D));
    /** Jump boost amplifier, or -1 without the effect. */
    public int jumpBoost = -1;
    /** Food level above 6 (or allowFlying): sprinting may start and continue. Blindness: sprinting may not start. */
    public boolean foodAllowsSprint = true, blind;
    /** Entity.inWater as set by the last handleWaterMovement (start of the last tick). */
    public boolean inWater;
    /** EnchantmentHelper.getDepthStriderModifier (boots). */
    public int depthStrider;
    /** PlayerCapabilities.isFlying, and its fly speed (getFlySpeed). */
    public boolean flying;
    public float flySpeed = 0.05F;
    /** Entity.isInWeb: set by a cobweb during a move, it scales and consumes the next move. */
    public boolean inWeb;
    /** Using an item this tick (eating, drinking, drawing a bow, blocking): input x0.2, no sprint start. Set at tick starts. */
    public boolean usingItem;
    public static final int FLY = 3;
    /** The current grid tick's move is a cobweb move (scaled displacement, motion zeroed). */
    public boolean gridWeb;
    /** In water or lava at the grid-tick start: jump adds the swim push (also while flying). */
    public boolean gridSwim;

    // Medium of the current grid tick, fixed at its start as vanilla fixes it at a tick start
    public static final int LAND = 0, WATER = 1, LAVA = 2;
    public int gridMedium = LAND;
    /** On a ladder or vine at the grid-tick start: the climbing clamps apply when the grid tick starts moving. */
    public boolean gridLadder;
    /** Water current to add when the grid tick starts moving (vanilla adds it at the tick start). */
    public double gridPushX, gridPushY, gridPushZ;
    /**
     * Ladder clamp active on this axis for the grid tick: vanilla clamps (motion + acceleration) to 0.15, so the
     * tick moves exactly the clamped speed. The grid then holds that speed with no further acceleration.
     */
    public boolean gridClampX, gridClampZ;


    // GRID contact mode (SubstepModel.Contacts.GRID): the current physics tick of the collision grid.
    /** Box at the start of the current grid tick; null = grid must be (re)initialized from the current state. */
    public Box gridStartBox;
    /** Contact flags at the start of the grid tick (they drive the collider's sneak guard and step-up, as in vanilla). */
    public boolean gridStartOnGround;
    /** Time elapsed in the current grid tick, in ticks. */
    public double gridElapsed;
    /** Free (unclipped) displacement accumulated since the grid tick started. */
    public double accX, accY, accZ;
    /** Friction of the grid tick, fixed at its start (vanilla evaluates it once per tick). */
    public float gridFriction;
    /** Whether the grid tick uses ground parameters (vanilla: onGround at the tick start). */
    public boolean gridGround;
    /**
     * Display offset from {@link #box} inside a grid tick (null = none). The physics box is vanilla's collider on
     * the partial displacement, which can sawtooth (sneak guard) or stall and release (Y-first clipping) within a
     * tick. The view never passes the tick's predicted end on limited axes and is used for rendering only.
     */
    public double viewDX, viewDY, viewDZ;

    public double viewX()
    {
        return posX() + viewDX;
    }

    public double viewY()
    {
        return posY() + viewDY;
    }

    public double viewZ()
    {
        return posZ() + viewDZ;
    }

    public double posX()
    {
        return box.centerX();
    }

    public double posY()
    {
        return box.minY;
    }

    public double posZ()
    {
        return box.centerZ();
    }

    /** EntityPlayer.getAIMoveSpeed: the movementSpeed attribute, with the sprint modifier (op 2) while sprinting. */
    public float aiMoveSpeed()
    {
        return sprinting ? sprintSpeed : walkSpeed;
    }

    /** Overwrites every field with {@code o}'s values. */
    public void set(PlayerState o)
    {
        box = o.box;
        motionX = o.motionX;
        motionY = o.motionY;
        motionZ = o.motionZ;
        onGround = o.onGround;
        collidedHorizontally = o.collidedHorizontally;
        collidedVertically = o.collidedVertically;
        sprinting = o.sprinting;
        jumpTicks = o.jumpTicks;
        sprintToggleTimer = o.sprintToggleTimer;
        sprintingTicksLeft = o.sprintingTicksLeft;
        jumpMovementFactor = o.jumpMovementFactor;
        prevJump = o.prevJump;
        prevSneak = o.prevSneak;
        prevForwardHigh = o.prevForwardHigh;
        yaw = o.yaw;
        walkSpeed = o.walkSpeed;
        sprintSpeed = o.sprintSpeed;
        jumpBoost = o.jumpBoost;
        foodAllowsSprint = o.foodAllowsSprint;
        blind = o.blind;
        inWater = o.inWater;
        depthStrider = o.depthStrider;
        flying = o.flying;
        flySpeed = o.flySpeed;
        inWeb = o.inWeb;
        usingItem = o.usingItem;
        gridWeb = o.gridWeb;
        gridSwim = o.gridSwim;
        gridMedium = o.gridMedium;
        gridLadder = o.gridLadder;
        gridPushX = o.gridPushX;
        gridPushY = o.gridPushY;
        gridPushZ = o.gridPushZ;
        gridClampX = o.gridClampX;
        gridClampZ = o.gridClampZ;
        gridStartBox = o.gridStartBox;
        gridStartOnGround = o.gridStartOnGround;
        gridElapsed = o.gridElapsed;
        accX = o.accX;
        accY = o.accY;
        accZ = o.accZ;
        gridFriction = o.gridFriction;
        gridGround = o.gridGround;
        viewDX = o.viewDX;
        viewDY = o.viewDY;
        viewDZ = o.viewDZ;
    }

    public PlayerState copy()
    {
        PlayerState s = new PlayerState();
        s.box = box;
        s.motionX = motionX;
        s.motionY = motionY;
        s.motionZ = motionZ;
        s.onGround = onGround;
        s.collidedHorizontally = collidedHorizontally;
        s.collidedVertically = collidedVertically;
        s.sprinting = sprinting;
        s.jumpTicks = jumpTicks;
        s.sprintToggleTimer = sprintToggleTimer;
        s.sprintingTicksLeft = sprintingTicksLeft;
        s.jumpMovementFactor = jumpMovementFactor;
        s.prevJump = prevJump;
        s.prevSneak = prevSneak;
        s.prevForwardHigh = prevForwardHigh;
        s.yaw = yaw;
        s.walkSpeed = walkSpeed;
        s.sprintSpeed = sprintSpeed;
        s.jumpBoost = jumpBoost;
        s.foodAllowsSprint = foodAllowsSprint;
        s.blind = blind;
        s.inWater = inWater;
        s.depthStrider = depthStrider;
        s.flying = flying;
        s.flySpeed = flySpeed;
        s.inWeb = inWeb;
        s.usingItem = usingItem;
        s.gridWeb = gridWeb;
        s.gridSwim = gridSwim;
        s.gridMedium = gridMedium;
        s.gridLadder = gridLadder;
        s.gridPushX = gridPushX;
        s.gridPushY = gridPushY;
        s.gridPushZ = gridPushZ;
        s.gridClampX = gridClampX;
        s.gridClampZ = gridClampZ;
        s.gridStartBox = gridStartBox;
        s.gridStartOnGround = gridStartOnGround;
        s.gridElapsed = gridElapsed;
        s.accX = accX;
        s.accY = accY;
        s.accZ = accZ;
        s.gridFriction = gridFriction;
        s.gridGround = gridGround;
        s.viewDX = viewDX;
        s.viewDY = viewDY;
        s.viewDZ = viewDZ;
        return s;
    }

    public void setSprinting(boolean value)
    {
        sprinting = value;
        sprintingTicksLeft = value ? 600 : 0;
    }
}
