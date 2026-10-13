package com.tactile.hook;

import net.minecraft.entity.EntityLivingBase;

/**
 * Target of the redirected call in EntityLivingBase.onLivingUpdate. It is side-neutral and runs for
 * every living entity on both the client and the integrated server. Unless the installed handler
 * claims the call, it makes exactly the original virtual call.
 */
public final class TactileHooks
{
    public interface MoveHandler
    {
        /** Return true if this call was handled and the vanilla move must not run. */
        boolean handleMove(EntityLivingBase entity, float strafe, float forward);
    }

    private static volatile MoveHandler handler;

    private TactileHooks()
    {
    }

    public static void setHandler(MoveHandler h)
    {
        handler = h;
    }

    public static void moveEntityWithHeading(EntityLivingBase entity, float strafe, float forward)
    {
        MoveHandler h = handler;
        if (h != null && h.handleMove(entity, strafe, forward)) return;
        entity.moveEntityWithHeading(strafe, forward);
    }
}
