package com.tactile.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGameOver;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.EnumDifficulty;

/**
 * Shared readiness checks for the automated in-game runners (baseline recorder, input probe, live
 * validation). The test world persists between runs, so a run can start with the player dead (the
 * death screen open), damaged or hungry. Each of those blocks or changes scripted movement.
 * Sprinting needs food above 6.
 */
public final class TestWorld
{
    private static int respawnCooldown;

    private TestWorld()
    {
    }

    /**
     * Call once per client tick. Returns true when the player exists, is alive and no screen is open.
     * Otherwise it takes the next recovery step (respawn, close the death screen) and returns false.
     */
    public static boolean ensurePlayerReady(Minecraft mc)
    {
        if (mc.thePlayer == null || mc.theWorld == null) return false;
        if (respawnCooldown > 0) --respawnCooldown;

        if (mc.currentScreen instanceof GuiGameOver && mc.thePlayer.getHealth() > 0.0F && !mc.thePlayer.isDead)
        {
            mc.displayGuiScreen(null); // respawned; the death screen is stale
            return false;
        }
        if (mc.thePlayer.getHealth() <= 0.0F || mc.thePlayer.isDead || mc.currentScreen instanceof GuiGameOver)
        {
            if (respawnCooldown == 0)
            {
                System.out.println("[tactile] test player is dead; respawning");
                mc.thePlayer.respawnPlayer();
                respawnCooldown = 40;
            }
            return false;
        }
        return mc.currentScreen == null;
    }

    /**
     * Server thread work, scheduled from the client: peaceful difficulty, full health and food, and
     * damage disabled. disableDamage is the survival-mode invulnerability flag; unlike creative it does
     * not enable flight, so movement physics stay those of survival.
     */
    public static void protectPlayer(final MinecraftServer server)
    {
        server.addScheduledTask(new Runnable()
        {
            public void run()
            {
                server.setDifficultyForAllWorlds(EnumDifficulty.PEACEFUL);
                for (EntityPlayerMP mp : server.getConfigurationManager().playerEntityList)
                {
                    if (mp.getHealth() <= 0.0F) continue; // respawn first; protect on a later call
                    // survival, not flying: a creative/flying state saved by an earlier probe must not leak into a run
                    if (mp.theItemInWorldManager.getGameType() != net.minecraft.world.WorldSettings.GameType.SURVIVAL)
                        mp.setGameType(net.minecraft.world.WorldSettings.GameType.SURVIVAL);
                    mp.capabilities.isFlying = false;
                    mp.capabilities.allowFlying = false;
                    mp.capabilities.disableDamage = true;
                    mp.sendPlayerAbilities();
                    mp.setHealth(mp.getMaxHealth());
                    mp.getFoodStats().addStats(20, 20.0F);
                    mp.fallDistance = 0.0F;
                    mp.extinguish();
                }
            }
        });
    }
}
