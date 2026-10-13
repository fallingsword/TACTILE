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
 * Backward bridging (HRMOVE_BRIDGEPROBE=1 with HRMOVE_INPUT=SCRIPTED). The player walks backwards (S) off a
 * platform end. Whenever the cell under its back edge is air, it places a block there against the side of the
 * last block, at the moment a real player would (tick start, before the player update). Reported per mode
 * ([tactile-bridge]): blocks placed, backward snaps (the position jumped against the direction of travel, i.e.
 * server corrections), stalled ticks (no progress while walking), falls, and distance covered.
 */
public final class BridgeProbe
{
    private static final String[] MODES = {"VANILLA", "EXACT"};
    /** Place when the player's trailing edge is this far (blocks) beyond the bridge end; negative = already past it (falling). */
    private static final double[] LEAD = {0.3D, 0.1D, 0.03D, 0.0D, -0.03D, -0.08D};
    private int edgeCell;
    private static final int WALK_TICKS = 240;

    private final Minecraft mc = Minecraft.getMinecraft();
    private int phase, counter, modeIndex, ox, oz;
    private int placed, snaps, stalls, attempts;
    private double lastZ, startZ, maxSnap;
    private boolean fell;

    public static boolean requested()
    {
        return "1".equals(System.getenv("HRMOVE_BRIDGEPROBE"));
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
                    TestWorld.protectPlayer(mc.getIntegratedServer());
                    startMode();
                }
                break;
            case 2:
                if (!TestWorld.ensurePlayerReady(mc)) break; // respawns a player who died in an earlier fall
                if (counter % 100 == 99) log(String.format("settling: %.3f %.3f %.3f onGround %b", mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, mc.thePlayer.onGround));
                boolean loaded = mc.theWorld.getChunkFromBlockCoords(new BlockPos(mc.thePlayer)).isLoaded();
                if (++counter >= 40 && loaded && mc.thePlayer.onGround)
                {
                    counter = 0;
                    startZ = lastZ = mc.thePlayer.posZ;
                    if (ClientSetup.engine.isActive())
                    {
                        List<TickInput> script = new ArrayList<TickInput>();
                        for (int i = 0; i < WALK_TICKS; ++i) script.add(TickInput.fromKeyString("--S----", 0.0F, 0.0F));
                        ClientSetup.engine.loadScript(script, 1);
                    }
                    phase = 3;
                }
                break;
            case 3:
                walkTick();
                break;
            default:
                break;
        }
    }

    private void startMode()
    {
        ClientSetup.CONFIG.mode = TactileConfig.Mode.valueOf(MODES[modeIndex % MODES.length]);
        ++ClientSetup.CONFIG.generation;
        placed = snaps = stalls = attempts = 0;
        maxSnap = 0.0D;
        fell = false;
        final MinecraftServer server = mc.getIntegratedServer();
        TestWorld.protectPlayer(server); // every variant: a previous run may have ended in a fall
        final WorldServer world = server.worldServerForDimension(0);
        BlockPos spawn = world.getSpawnPoint();
        ox = spawn.getX() + 40 + 6 * modeIndex; // near spawn: far-away chunks may never reach the client
        oz = spawn.getZ();
        final int x0 = ox, z0 = oz;
        server.addScheduledTask(new Runnable()
        {
            public void run()
            {
                for (int x = -2; x <= 2; ++x)
                    for (int z = -70; z <= 22; ++z)
                        for (int y = 95; y <= 103; ++y)
                            world.setBlockState(new BlockPos(x0 + x, y, z0 + z),
                                y == 99 && z >= 0 && z <= 20 ? Blocks.stone.getDefaultState() : Blocks.air.getDefaultState(), 2);
                EntityPlayerMP mp = server.getConfigurationManager().playerEntityList.get(0);
                mp.inventory.currentItem = 0;
                mp.inventory.setInventorySlotContents(0, new ItemStack(Blocks.stone, 64));
                mp.inventoryContainer.detectAndSendChanges();
                mp.setSprinting(false);
                mp.playerNetServerHandler.setPlayerLocation(x0 + 0.5D, 100.0D, z0 + 18.5D, 0.0F, 0.0F);
                server.getConfigurationManager().serverUpdateMountedMovingPlayer(mp); // send the destination chunks now; a player frozen in an unloaded chunk never asks
            }
        });
        mc.thePlayer.inventory.currentItem = 0;
        edgeCell = oz;
        log("mode " + label());
        counter = 0;
        phase = 2;
    }

    private void walkTick()
    {
        EntityPlayerSP p = mc.thePlayer;
        if (!ClientSetup.engine.isActive()) KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), counter < WALK_TICKS);
        p.rotationYaw = 0.0F;
        p.rotationPitch = 0.0F;

        // progress (travel is towards -z): a snap is a jump back towards +z
        double z = p.posZ, dz = z - lastZ;
        if (counter > 2 && counter < WALK_TICKS)
        {
            if (dz > 0.01D)
            {
                ++snaps;
                maxSnap = Math.max(maxSnap, dz);
            }
            else if (dz > -0.01D) ++stalls;
        }
        lastZ = z;
        if (p.posY < 98.0D) fell = true;

        // extend the bridge once the trailing edge (maxZ) is within LEAD of the bridge end (the face at z = edgeCell)
        double lead = p.getEntityBoundingBox().maxZ - edgeCell;
        if (!fell && counter < WALK_TICKS && lead < LEAD[modeIndex / MODES.length])
        {
            BlockPos against = new BlockPos(ox, 99, edgeCell), target = against.north();
            ItemStack held = p.getHeldItem();
            if (held != null && mc.theWorld.isAirBlock(target))
            {
                ++attempts;
                mc.playerController.onPlayerRightClick(p, mc.theWorld, held, against, EnumFacing.NORTH, new Vec3(ox + 0.5D, 99.5D, edgeCell));
                if (!mc.theWorld.isAirBlock(target)) --edgeCell;
            }
        }

        if (++counter >= WALK_TICKS + 15 || fell)
        {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), false);
            int bridge = 0;
            for (int zz = -1; zz >= -70; --zz) if (mc.theWorld.getBlockState(new BlockPos(ox, 99, oz + zz)).getBlock() == Blocks.stone) ++bridge;
            log(String.format("%s: placed %d (attempts %d), backward snaps %d (max %.3f), stalled ticks %d, fell %b, distance %.2f",
                label(), bridge, attempts, snaps, maxSnap, stalls, fell, startZ - p.posZ));
            if (++modeIndex >= MODES.length * LEAD.length)
            {
                log("done");
                mc.shutdown();
                phase = 9;
                return;
            }
            startMode();
        }
    }

    private String label()
    {
        return String.format("%-7s lead %+.2f", MODES[modeIndex % MODES.length], LEAD[modeIndex / MODES.length]);
    }

    private static void log(String s)
    {
        System.out.println("[tactile-bridge] " + s);
    }
}
