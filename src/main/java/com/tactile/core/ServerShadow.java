package com.tactile.core;

/**
 * What the server most likely makes of the positions the client sends, and the vanilla rules for what a position
 * packet should say. Used by the live engine (option "server-safe slime and knockback", and the onGround flag of every
 * packet) and by the offline tests, so both run the same code.
 * <p>
 * Prediction-based server checks (GrimAC, UNCP, and reportedly Hypixel's) replay each tick with vanilla's movement code
 * from their own copy of the player's state: they try every vanilla input for that tick (directions, jump, sprint,
 * sneak), take the result closest to the reported position, and keep that result's motion for the next tick. This class
 * does the same, so the client knows the motion the server expects.
 */
public final class ServerShadow
{
    private PlayerState state;
    private double lastOffset;

    /** Starts from a known state (activation, teleport): the server's view equals it. */
    public void reset(PlayerState s)
    {
        state = s.copy();
        state.gridStartBox = null;
        lastOffset = 0.0D;
    }

    public boolean ready()
    {
        return state != null;
    }

    /** The server's view after the last packet: position as reported, motion of the closest vanilla prediction. */
    public PlayerState state()
    {
        return state;
    }

    /** Distance between the last reported position and the closest vanilla prediction (blocks). */
    public double lastOffset()
    {
        return lastOffset;
    }

    /** A server velocity packet (knockback): the server sets the player's motion to it. */
    public void setVelocity(double vx, double vy, double vz)
    {
        if (state == null) return;
        state.motionX = vx;
        state.motionY = vy;
        state.motionZ = vz;
    }

    /**
     * One position packet. {@code sent} holds what the packet reports (box, and the attributes the server also knows:
     * speeds, potions, food, item use, flight). Tries the 72 vanilla inputs (forward/back/none x left/right/none x jump
     * x sprint x sneak) with the reported yaw, keeps the closest, and moves its state to the reported position.
     */
    public double observe(PlayerState sent, CollisionWorld world, float yaw, float pitch)
    {
        if (state == null)
        {
            reset(sent);
            return 0.0D;
        }
        state.walkSpeed = sent.walkSpeed;
        state.sprintSpeed = sent.sprintSpeed;
        state.jumpBoost = sent.jumpBoost;
        state.foodAllowsSprint = sent.foodAllowsSprint;
        state.blind = sent.blind;
        state.depthStrider = sent.depthStrider;
        state.flying = sent.flying;
        state.flySpeed = sent.flySpeed;
        state.usingItem = sent.usingItem;
        PlayerState best = null;
        double bestD = Double.MAX_VALUE;
        for (int f = 0; f < 3; ++f)
            for (int side = 0; side < 3; ++side)
                for (int j = 0; j < 2; ++j)
                    for (int r = 0; r < 2; ++r)
                        for (int n = 0; n < 2; ++n)
                        {
                            TickInput in = new TickInput(f == 0, f == 1, side == 0, side == 1, j == 1, n == 1, r == 1, sent.usingItem, yaw, pitch);
                            PlayerState c = state.copy();
                            VanillaModel.tick(c, in, world);
                            double dx = c.box.minX - sent.box.minX, dy = c.box.minY - sent.box.minY, dz = c.box.minZ - sent.box.minZ;
                            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                            if (d < bestD)
                            {
                                bestD = d;
                                best = c;
                            }
                        }
        best.box = sent.box;
        state = best;
        lastOffset = bestD;
        return bestD;
    }

    /**
     * The onGround flag vanilla would send for this movement (Entity.moveEntity: onGround = the vertical part of the
     * attempted move was downward and was stopped). {@code attemptedDy} is the free vertical displacement since the
     * last packet, before collisions; the reported displacement is the difference of the two boxes.
     */
    public static boolean packetOnGround(Box lastSent, Box now, double attemptedDy)
    {
        if (attemptedDy >= 0.0D) return false;
        return Math.abs((now.minY - lastSent.minY) - attemptedDy) > 1e-9D;
    }

    /**
     * Slime involved: the player stands on slime, or the first solid block below the player's centre, within
     * {@code depth} blocks, is slime. Vanilla decides a bounce by the block under the centre (Entity.moveEntity's
     * landing block), so the centre column is what matters.
     */
    public static boolean slimeBelow(PlayerState s, CollisionWorld world, double depth)
    {
        double x = s.posX(), z = s.posZ();
        if (s.onGround && world.landingBlock(x, s.posY(), z) == CollisionWorld.LANDING_SLIME) return true;
        int bx = (int)Math.floor(x), bz = (int)Math.floor(z);
        int top = (int)Math.floor(s.box.minY - 1e-7D), bottom = (int)Math.floor(s.box.minY - depth);
        double e = 1e-7D;
        for (int y = top; y >= bottom; --y)
        {
            if (world.getCollidingBoxes(new Box(bx + e, y + e, bz + e, bx + 1 - e, y + 1 - e, bz + 1 - e)).isEmpty()) continue;
            return world.landingBlock(x, y + 0.7D, z) == CollisionWorld.LANDING_SLIME; // the block at (posY - 0.2) is cell y
        }
        return false;
    }
}
