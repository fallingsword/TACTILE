package com.tactile.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Fall damage (HRMOVE_FALLPROBE=1). The server works out fall damage from the positions and the onGround flag the client
 * sends, so the packet flag (1.1.0: vanilla's rule from the movement since the last packet) must give vanilla's damage.
 * For each mode (VANILLA, EXACT, EXACT with the server-safe option) and height (8, 12, 16, 20 blocks) the player stands on a
 * platform, then walks off its edge onto stone, with damage on; the health lost is read on the server. The player is
 * placed on the platform and left to settle first, so the fall itself involves no teleport. Result: [tactile-fall].
 */
public final class FallProbe
{
    private static final int[] HEIGHTS = {8, 12, 16, 20};
    private static final String[] MODES = {"VANILLA", "EXACT", "EXACT+SAFE"};

    private final Minecraft mc = Minecraft.getMinecraft();
    private int phase, counter, variant;
    private int x0, z0;

    public static boolean requested()
    {
        return "1".equals(System.getenv("HRMOVE_FALLPROBE"));
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e)
    {
        if (e.phase != TickEvent.Phase.END) return;
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
                    MinecraftServer server = mc.getIntegratedServer();
                    BlockPos spawn = server.worldServerForDimension(0).getSpawnPoint();
                    x0 = spawn.getX() + 80;
                    z0 = spawn.getZ() + 80;
                    counter = 0;
                    phase = 2;
                }
                break;
            case 2: // mode, platform, place the player on it (teleport), damage still off
                if (++counter < 10) break;
                String mode = MODES[variant / HEIGHTS.length];
                ClientSetup.CONFIG.serverSafeSlime = mode.endsWith("+SAFE");
                ClientSetup.CONFIG.applyForTest(mode.startsWith("VANILLA") ? TactileConfig.Mode.VANILLA : TactileConfig.Mode.EXACT, 960);
                setUp(HEIGHTS[variant % HEIGHTS.length]);
                counter = 0;
                phase = 3;
                break;
            case 3: // settle on the platform, then heal and enable damage
                mc.thePlayer.rotationYaw = 0.0F;
                if (++counter == 40) arm();
                if (counter < 50) break;
                // walk forward (+z) off the platform edge: the engine from a script (scripted input), vanilla from the binding
                List<TickInput> script = new ArrayList<TickInput>();
                for (int i = 0; i < 400; ++i) script.add(TickInput.fromKeyString(i < 25 ? "W------" : "-------", 0.0F, 0.0F)); // walk 25 ticks, then stop
                ClientSetup.engine.loadScript(script, 1);
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), true);
                counter = 0;
                phase = 4;
                break;
            case 4: // fall and land (20 blocks: about 35 ticks after leaving the edge)
                mc.thePlayer.rotationYaw = 0.0F;
                if (counter == 25) KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
                if (++counter < 80) break;
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
                EntityPlayerMP mp = mc.getIntegratedServer().getConfigurationManager().playerEntityList.get(0);
                log(String.format(Locale.ROOT, "%-10s walk off %2d blocks: health lost %.1f (now at y %.2f, on ground %b)",
                    MODES[variant / HEIGHTS.length], HEIGHTS[variant % HEIGHTS.length], 20.0F - mp.getHealth(), mp.posY, mp.onGround));
                if (++variant >= MODES.length * HEIGHTS.length)
                {
                    log("done");
                    ClientSetup.CONFIG.serverSafeSlime = false;
                    mc.shutdown();
                    phase = 9;
                    break;
                }
                counter = 0;
                phase = 2;
                break;
            default:
                break;
        }
    }

    /** A 3 x 3 platform at height h above a stone floor at y 99 that runs on past it; the player on the platform. */
    private void setUp(final int h)
    {
        final MinecraftServer server = mc.getIntegratedServer();
        final WorldServer world = server.worldServerForDimension(0);
        server.addScheduledTask(new Runnable()
        {
            public void run()
            {
                for (int x = -2; x <= 2; ++x)
                    for (int z = -2; z <= 40; ++z)
                        for (int y = 99; y <= 125; ++y)
                            world.setBlockState(new BlockPos(x0 + x, y, z0 + z), y == 99 ? Blocks.stone.getDefaultState() : Blocks.air.getDefaultState(), 2);
                for (int x = -1; x <= 1; ++x)
                    for (int z = -1; z <= 1; ++z)
                        world.setBlockState(new BlockPos(x0 + x, 99 + h, z0 + z), Blocks.stone.getDefaultState(), 2);
                EntityPlayerMP mp = server.getConfigurationManager().playerEntityList.get(0);
                mp.capabilities.disableDamage = true;
                mp.capabilities.isFlying = false;
                mp.sendPlayerAbilities();
                mp.playerNetServerHandler.setPlayerLocation(x0 + 0.5D, 100.0D + h, z0 + 0.5D, 0.0F, 0.0F);
                server.getConfigurationManager().serverUpdateMountedMovingPlayer(mp);
            }
        });
    }

    private void arm()
    {
        final MinecraftServer server = mc.getIntegratedServer();
        server.addScheduledTask(new Runnable()
        {
            public void run()
            {
                EntityPlayerMP mp = server.getConfigurationManager().playerEntityList.get(0);
                mp.setHealth(20.0F);
                mp.fallDistance = 0.0F;
                mp.capabilities.disableDamage = false;
                mp.sendPlayerAbilities();
            }
        });
    }

    private static void log(String s)
    {
        System.out.println("[tactile-fall] " + s);
    }
}
