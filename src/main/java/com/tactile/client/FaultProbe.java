package com.tactile.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.sun.jna.Pointer;
import com.tactile.client.input.Win32;
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
 * Fail-safe check (HRMOVE_FAULTTEST=tick|frame|move|keys). The engine runs EXACT while the player walks forward.
 * tick/frame/move: LiveEngine throws once in that entry point; the game must keep running with the engine off and the
 * player still walking under vanilla. keys: one F13 press (injected only while the game window is in front) makes the
 * key-event thread throw; the engine must stay on and read keys once per frame. Result in the log ([tactile-fault]).
 */
public final class FaultProbe
{
    private final Minecraft mc = Minecraft.getMinecraft();
    private final String kind = System.getenv("HRMOVE_FAULTTEST");
    private int phase, counter;
    private double zAt120;

    public static boolean requested()
    {
        String k = System.getenv("HRMOVE_FAULTTEST");
        return k != null && !k.trim().isEmpty();
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
                    TestWorld.protectPlayer(mc.getIntegratedServer());
                    build();
                    ClientSetup.CONFIG.applyForTest(TactileConfig.Mode.EXACT, 960);
                    counter = 0;
                    phase = 2;
                }
                break;
            case 2: // walk forward: the engine from a script (scripted runs), vanilla from the key binding
                if (++counter == 20)
                {
                    List<TickInput> script = new ArrayList<TickInput>();
                    for (int i = 0; i < 2000; ++i) script.add(TickInput.fromKeyString("W------", 0.0F, 0.0F));
                    if (!kind.equals("keys")) ClientSetup.engine.loadScript(script, 1);
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), true);
                }
                if (kind.equals("keys") && counter == 60) injectF13();
                mc.thePlayer.rotationYaw = 0.0F;
                if (counter == 120) zAt120 = mc.thePlayer.posZ;
                if (counter == 180)
                {
                    double walked = mc.thePlayer.posZ - zAt120;
                    log(String.format(Locale.ROOT, "%s: game running, engine faulted %b, active %b, input '%s', walked %.2f blocks in the last 3 s, status '%s'",
                        kind, ClientSetup.engine.isFaulted(), ClientSetup.engine.isActive(), ClientSetup.engine.inputDescription(), walked,
                        ClientSetup.engine.statusLine().replaceAll("§.", "")));
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
                    mc.shutdown();
                    phase = 9;
                }
                break;
            default:
                break;
        }
    }

    private void injectF13()
    {
        Win32.User32 u = Win32.get();
        if (u == null) return;
        Pointer fg = u.GetForegroundWindow();
        if (!org.lwjgl.opengl.Display.isActive() || fg == null)
        {
            log("keys: game window not in front; F13 not injected");
            return;
        }
        byte scan = (byte)u.MapVirtualKeyA(0x7C, 0);
        u.keybd_event((byte)0x7C, scan, 0, null);
        u.keybd_event((byte)0x7C, scan, Win32.KEYEVENTF_KEYUP, null);
    }

    private void build()
    {
        final MinecraftServer server = mc.getIntegratedServer();
        final WorldServer world = server.worldServerForDimension(0);
        BlockPos spawn = world.getSpawnPoint();
        final int x0 = spawn.getX() + 60, z0 = spawn.getZ() - 60;
        server.addScheduledTask(new Runnable()
        {
            public void run()
            {
                for (int x = -2; x <= 2; ++x)
                    for (int z = -2; z <= 120; ++z)
                    {
                        world.setBlockState(new BlockPos(x0 + x, 99, z0 + z), Blocks.stone.getDefaultState(), 2);
                        for (int y = 100; y <= 102; ++y) world.setBlockToAir(new BlockPos(x0 + x, y, z0 + z));
                    }
                EntityPlayerMP mp = server.getConfigurationManager().playerEntityList.get(0);
                mp.playerNetServerHandler.setPlayerLocation(x0 + 0.5D, 100.0D, z0 + 0.5D, 0.0F, 0.0F);
                server.getConfigurationManager().serverUpdateMountedMovingPlayer(mp);
            }
        });
    }

    private static void log(String s)
    {
        System.out.println("[tactile-fault] " + s);
    }
}
