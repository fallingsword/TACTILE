package com.tactile.client;

import java.util.ArrayList;
import java.util.List;

import com.tactile.client.live.TactileConfig;
import com.tactile.core.TickInput;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Block-placement agreement between client and server (HRMOVE_PLACEPROBE=1 with HRMOVE_INPUT=SCRIPTED). The player
 * walks forward along a runway. Each time its back edge enters a new block, it places a block in the cell just
 * behind it, at the earliest moment the client allows. A "ghost" is a block that the client placed and the server
 * then removed, because the server still saw the player overlapping the cell. Run for VANILLA and EXACT; the
 * result goes to the log ([tactile-place]).
 */
public final class PlaceProbe
{
    private static final String[] MODES = {"VANILLA", "EXACT"};
    private static final int WALK_TICKS = 260;

    private final Minecraft mc = Minecraft.getMinecraft();
    private int phase, counter, modeIndex, ox, oz;
    private int lastCell;
    private final List<int[]> pending = new ArrayList<int[]>(); // {cellZ, checkAtCounter, clientPlaced}
    private int attempts, placed, ghosts, refusedByClient;

    public static boolean requested()
    {
        return "1".equals(System.getenv("HRMOVE_PLACEPROBE"));
    }

    /** At tick START after the engine's catch-up, where Minecraft.runTick processes real clicks (before the player update). */
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
                    TestWorld.protectPlayer(mc.getIntegratedServer());
                    startMode();
                }
                break;
            case 2: // settle after teleport
                if (!TestWorld.ensurePlayerReady(mc)) break; // respawns a player who died in an earlier fall
                if (counter % 100 == 99)
                    log(String.format("settling: pos %.3f %.3f %.3f onGround %b, chunk loaded %b, block below %s", mc.thePlayer.posX, mc.thePlayer.posY,
                        mc.thePlayer.posZ, mc.thePlayer.onGround, mc.theWorld.getChunkFromBlockCoords(new BlockPos(mc.thePlayer)).isLoaded(),
                        mc.theWorld.getBlockState(new BlockPos(mc.thePlayer).down()).getBlock().getUnlocalizedName()));
                if (++counter >= 40 && mc.thePlayer.onGround)
                {
                    counter = 0;
                    lastCell = cellBehind();
                    if (ClientSetup.engine.isActive())
                    {
                        List<TickInput> script = new ArrayList<TickInput>();
                        for (int i = 0; i < WALK_TICKS; ++i) script.add(TickInput.fromKeyString("W------", 0.0F, 0.0F));
                        ClientSetup.engine.loadScript(script, 1);
                    }
                    phase = 3;
                }
                break;
            case 3: // walking
                walkTick();
                break;
            default:
                break;
        }
    }

    private void startMode()
    {
        ClientSetup.CONFIG.mode = TactileConfig.Mode.valueOf(MODES[modeIndex]);
        ++ClientSetup.CONFIG.generation;
        attempts = placed = ghosts = refusedByClient = 0;
        pending.clear();
        final MinecraftServer server = mc.getIntegratedServer();
        TestWorld.protectPlayer(server); // every variant: a previous run may have ended in a fall
        final WorldServer world = server.worldServerForDimension(0);
        BlockPos spawn = world.getSpawnPoint();
        ox = spawn.getX() - 40 - 10 * modeIndex; // near spawn: far-away chunks may never reach the client
        oz = spawn.getZ();
        final int x0 = ox, z0 = oz;
        server.addScheduledTask(new Runnable()
        {
            public void run()
            {
                for (int x = -2; x <= 2; ++x)
                    for (int z = -3; z <= 80; ++z)
                    {
                        world.setBlockState(new BlockPos(x0 + x, 99, z0 + z), Blocks.stone.getDefaultState(), 2);
                        for (int y = 100; y <= 103; ++y) world.setBlockToAir(new BlockPos(x0 + x, y, z0 + z));
                    }
                EntityPlayerMP mp = server.getConfigurationManager().playerEntityList.get(0);
                mp.inventory.currentItem = 0;
                mp.inventory.setInventorySlotContents(0, new ItemStack(Blocks.stone, 64));
                mp.inventoryContainer.detectAndSendChanges();
                mp.setSprinting(false);
                mp.playerNetServerHandler.setPlayerLocation(x0 + 0.5D, 100.0D, z0 + 0.5D, 0.0F, 0.0F);
                server.getConfigurationManager().serverUpdateMountedMovingPlayer(mp); // send the destination chunks now; a player frozen in an unloaded chunk never asks
            }
        });
        mc.thePlayer.inventory.currentItem = 0;
        log("mode " + MODES[modeIndex]);
        counter = 0;
        phase = 2;
    }

    /** Cell (z) whose far face the player's back edge has most recently passed. */
    private int cellBehind()
    {
        return MathHelper.floor_double(mc.thePlayer.getEntityBoundingBox().minZ) - 1;
    }

    private void walkTick()
    {
        EntityPlayerSP p = mc.thePlayer;
        if (!ClientSetup.engine.isActive()) KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), counter < WALK_TICKS);
        p.rotationYaw = 0.0F;
        p.rotationPitch = 0.0F;

        // resolve earlier attempts
        for (int i = pending.size() - 1; i >= 0; --i)
        {
            int[] a = pending.get(i);
            if (counter < a[1]) continue;
            boolean now = mc.theWorld.getBlockState(new BlockPos(ox, 100, a[0])).getBlock() == Blocks.stone;
            if (now) ++placed;
            else if (a[2] == 1) ++ghosts;
            else ++refusedByClient;
            pending.remove(i);
        }

        int cell = cellBehind();
        if (counter < WALK_TICKS && cell > lastCell)
        {
            lastCell = cell;
            ItemStack held = p.getHeldItem();
            if (held != null)
            {
                BlockPos floor = new BlockPos(ox, 99, cell);
                mc.playerController.onPlayerRightClick(p, mc.theWorld, held, floor, EnumFacing.UP, new Vec3(ox + 0.5D, 100.0D, cell + 0.5D));
                boolean clientPlaced = mc.theWorld.getBlockState(floor.up()).getBlock() == Blocks.stone;
                pending.add(new int[] {cell, counter + 10, clientPlaced ? 1 : 0});
                ++attempts;
            }
        }

        if (++counter >= WALK_TICKS + 15)
        {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
            log(String.format("%s: attempts %d, placed %d, ghosts %d (client placed, server removed), refused by client %d",
                MODES[modeIndex], attempts, placed, ghosts, refusedByClient));
            if (++modeIndex >= MODES.length)
            {
                log("done");
                mc.shutdown();
                phase = 9;
                return;
            }
            startMode();
        }
    }

    private static void log(String s)
    {
        System.out.println("[tactile-place] " + s);
    }
}
