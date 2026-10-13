package com.tactile.client.live;

import com.tactile.core.TickInput;

import net.minecraft.util.MovementInput;

/**
 * Replaces EntityPlayerSP.movementInput while high-resolution mode controls the player. Vanilla's tick
 * still reads movementInput: isSneaking() (sneak packets, rendering), sprint rules and the jump check.
 * This reports the engine's input at the tick boundary, so packets carry the same sneak state the
 * physics used. It reports jump as never pressed, so vanilla's once-per-tick jump never fires (the
 * engine applies jumps per substep). Any vanilla sprint/timer mutations made from these values are
 * undone by the move hook, which restores the engine's state.
 */
final class TactileMovementInput extends MovementInput
{
    private TickInput current;

    void set(TickInput in)
    {
        current = in;
    }

    @Override
    public void updatePlayerMoveState()
    {
        if (current == null)
        {
            moveStrafe = 0.0F;
            moveForward = 0.0F;
            jump = false;
            sneak = false;
            return;
        }
        moveStrafe = current.moveStrafe();
        moveForward = current.moveForward();
        jump = false;
        sneak = current.sneak;
    }
}
