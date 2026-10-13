package com.tactile.client;

import java.util.ArrayList;
import java.util.List;

import com.tactile.client.live.TactileConfig;
import com.tactile.core.TickInput;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Creative flight must not toggle at hand-overs (HRMOVE_FLIGHTPROBE=1 with HRMOVE_INPUT=SCRIPTED). In creative mode
 * the player holds forward and jump across alternating soul sand and stone, so control changes hands between the
 * engine and vanilla every block while jump stays held. Vanilla never toggles flight for a held key; the probe
 * reports ([tactile-flight]) whether flying was ever switched on, and how many hand-overs happened.
 */
public final class FlightProbe
{
    private static final int RUN_TICKS = 100;

    private final Minecraft mc = Minecraft.getMinecraft();
    private int phase, counter, handovers;
    private boolean everFlying, lastActive;

    public static boolean requested()
    {
        return "1".equals(System.getenv("HRMOVE_FLIGHTPROBE"));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTick(TickEvent.ClientTickEvent e)
    {
        if (e.phase != TickEvent.Phase.START) return;
        switch (phase)
        {
            case 0:
                if (mc.theWorld == null && mc.currentScreen instanceof GuiMainMenu)
                {
                    mc.gameSettings.pauseOnLostFocus = false;
                    mc.launchIntegratedServer("hrm_baseline", "hrm_baseline", new WorldSettings(0L, WorldSettings.GameType.SURVIVAL, false, false, WorldType.FLAT));
                    phase = 1;
                }
                break;
            case 1:
                if (TestWorld.ensurePlayerReady(mc) && ++counter > 60)
                {
                    ClientSetup.CONFIG.mode = TactileConfig.Mode.EXACT;
                    ++ClientSetup.CONFIG.generation;
                    build();
                    counter = 0;
                    phase = 2;
                }
                break;
            case 2:
                if (!TestWorld.ensurePlayerReady(mc)) break;
                if (++counter >= 60 && mc.thePlayer.onGround && mc.thePlayer.capabilities.isCreativeMode && ClientSetup.engine.isActive())
                {
                    List<TickInput> script = new ArrayList<TickInput>();
                    for (int i = 0; i < RUN_TICKS; ++i) script.add(TickInput.fromKeyString("W---J-R", 0.0F, 0.0F));
                    ClientSetup.engine.loadScript(script, 1);
                    counter = 0;
                    lastActive = true;
                    phase = 3;
                }
                break;
            case 3:
                // vanilla reads the key bindings while it has control
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), counter < RUN_TICKS);
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), counter < RUN_TICKS);
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), counter < RUN_TICKS); // sprint-jumping: hand-overs every few ticks
                boolean active = ClientSetup.engine.isActive();
                if (active != lastActive) ++handovers;
                lastActive = active;
                if (mc.thePlayer.capabilities.isFlying) everFlying = true;
                if (++counter >= RUN_TICKS + 5)
                {
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
                    System.out.println("[tactile-flight] hand-overs " + handovers + ", flying switched on: " + everFlying);
                    mc.shutdown();
                    phase = 9;
                }
                break;
            default:
                break;
        }
    }

    private void build()
    {
        final MinecraftServer server = mc.getIntegratedServer();
        TestWorld.protectPlayer(server);
        final WorldServer world = server.worldServerForDimension(0);
        BlockPos spawn = world.getSpawnPoint();
        final int x0 = spawn.getX() - 35, z0 = spawn.getZ() - 10;
        server.addScheduledTask(new Runnable()
        {
            public void run()
            {
                for (int x = -8; x <= 8; ++x)
                    for (int z = -6; z <= 64; ++z)
                    {
                        boolean floor = x >= -2 && x <= 2 && z >= -3 && z <= 60;
                        // alternate soul sand and stone every block from z 2 on: the first hand-over comes a few ticks
                        // after the jump press (as when jumping onto a ladder), within vanilla's 7-tick double-tap window
                        boolean sand = floor && z >= 2 && z % 2 == 0;
                        world.setBlockState(new BlockPos(x0 + x, 99, z0 + z),
                            sand ? Blocks.soul_sand.getDefaultState() : floor ? Blocks.stone.getDefaultState() : Blocks.air.getDefaultState(), 2);
                        for (int y = 100; y <= 103; ++y) world.setBlockToAir(new BlockPos(x0 + x, y, z0 + z));
                    }
                EntityPlayerMP mp = server.getConfigurationManager().playerEntityList.get(0);
                mp.setGameType(WorldSettings.GameType.CREATIVE);
                mp.capabilities.isFlying = false;
                mp.sendPlayerAbilities();
                mp.playerNetServerHandler.setPlayerLocation(x0 + 0.5D, 100.0D, z0 + 0.5D, 0.0F, 0.0F);
                server.getConfigurationManager().serverUpdateMountedMovingPlayer(mp);
            }
        });
    }
}
