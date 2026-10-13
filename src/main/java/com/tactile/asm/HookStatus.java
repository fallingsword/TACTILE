package com.tactile.asm;

/**
 * Hook installation status, written by the transformer and read by the mod. It has no Minecraft
 * references, so the transformer can touch it without loading game classes.
 */
public final class HookStatus
{
    /** True only if the move call site in EntityLivingBase.onLivingUpdate was found exactly once and redirected. */
    public static volatile boolean moveHookInstalled;
    public static volatile String detail = "transformer did not run (coremod not loaded)";

    private HookStatus()
    {
    }
}
