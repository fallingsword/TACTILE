package com.tactile.client;

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
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Pillaring straight up (HRMOVE_PILLARPROBE=1 with HRMOVE_INPUT=SCRIPTED). Looking down, the player jumps and places
 * a block under itself once its feet clear the cell, lands on it, and jumps again, for {@value #BLOCKS} blocks.
 * <p>Jump strategies:
 * <ul>
 * <li>HOLD: space held throughout (vanilla re-jumps only when its 10-tick jump cooldown ends, a tick after landing);</li>
 * <li>EARLY: space released after each takeoff and pressed again 60 ms before the landing;</li>
 * <li>LATE: pressed again 20 ms after the landing.</li>
 * </ul>
 * Landing is committed 9 ticks after a takeoff (a 1-block jump). Presses happen at their real time: in the engine
 * they are injected as key events at that moment (frame resolution); in VANILLA they are seen at the next tick start,
 * exactly as vanilla reads keys. Blocks are placed at a tick start (where Minecraft handles clicks), at the first
 * moment the feet clear the cell (place EARLY) or at the last tick start before they would drop below it (place LATE).
 * <p>Reported per variant ([tactile-pillar]): blocks climbed, ticks from the first takeoff to standing on the last
 * block, mean ticks per block, presses that never produced a jump, ghost blocks (placed by the client, removed by the
 * server), and the engine's replays and server corrections.
 */
public final class PillarProbe
{
    private static final int BLOCKS = 10;
    private static final Object[][] MODES = {{"VANILLA", 960}, {"EXACT", 960}, {"EXACT", 20}, {"STEPPED", 960}};
    private static final String[] STRATEGIES = {"HOLD", "EARLY", "LATE"};
    private static final double[] PRESS_OFFSET = {Double.NaN, -1.2D, 0.4D}; // ticks relative to the landing commit
    private static final String[] PLACEMENT = {"EARLY", "LATE"};
    private static final double AIRTIME = 9.0D; // takeoff to landing commit on a block one higher

    private final Minecraft mc = Minecraft.getMinecraft();
    private int phase, counter, variant, ox, oz;
    // per variant
    private int support;            // feet level when standing (block top)
    private int tick;               // probe tick counter (VANILLA time base)
    private int placedClient, takeoffs, lostPresses, startTick, doneTick;
    private double lastTakeoff, firstTakeoff; // VANILLA: tick index of the jump; engine: engine tau
    private double pressTarget;     // when the next press is due (same time base), NaN if none
    private double pressedAt;       // time base of the outstanding press, NaN if none
    private boolean jumpDown, wasAirborne;
    private long replays0, corrections0;
    private double lastEngineJump;

    public static boolean requested()
    {
        return "1".equals(System.getenv("HRMOVE_PILLARPROBE"));
    }

    private String mode()
    {
        return (String)MODES[variant / (STRATEGIES.length * PLACEMENT.length)][0];
    }

    private int rate()
    {
        return (Integer)MODES[variant / (STRATEGIES.length * PLACEMENT.length)][1];
    }

    private int strategy()
    {
        return variant / PLACEMENT.length % STRATEGIES.length;
    }

    private boolean lateplace()
    {
        return variant % PLACEMENT.length == 1;
    }

    private boolean engine()
    {
        return !mode().equals("VANILLA");
    }

    private String label()
    {
        return String.format("%-8s %4d Hz  jump %-5s place %-5s", mode(), rate(), STRATEGIES[strategy()], PLACEMENT[variant % PLACEMENT.length]);
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
                    mc.gameSettings.limitFramerate = 260; // unlimited: presses are injected at frame resolution
                    mc.gameSettings.enableVsync = false;
                    mc.launchIntegratedServer("hrm_baseline", "hrm_baseline", new WorldSettings(0L, WorldSettings.GameType.SURVIVAL, false, false, WorldType.FLAT));
                    phase = 1;
                }
                break;
            case 1:
                if (TestWorld.ensurePlayerReady(mc) && ++counter > 60)
                {
                    TestWorld.protectPlayer(mc.getIntegratedServer());
                    startVariant();
                }
                break;
            case 2: // settle
                if (!TestWorld.ensurePlayerReady(mc)) break;
                look();
                if (++counter >= 40 && mc.thePlayer.onGround && Math.abs(mc.thePlayer.posY - 100.0D) < 1e-6 && engine() == ClientSetup.engine.isActive())
                {
                    support = 100;
                    tick = 0;
                    placedClient = takeoffs = lostPresses = 0;
                    startTick = doneTick = -1;
                    lastTakeoff = firstTakeoff = Double.NaN;
                    pressedAt = Double.NaN;
                    wasAirborne = false;
                    lastEngineJump = ClientSetup.engine.lastJumpTau();
                    replays0 = ClientSetup.engine.diag.replays;
                    corrections0 = ClientSetup.engine.diag.corrections;
                    pressTarget = engine() ? ClientSetup.engine.tauNow() : 0.0D; // first press now
                    counter = 0;
                    phase = 3;
                }
                break;
            case 3:
                climbTick();
                break;
            case 4: // let the server settle, then count ghosts
                if (++counter >= 20) finishVariant();
                break;
            default:
                break;
        }
    }

    private void look()
    {
        mc.thePlayer.rotationYaw = 0.0F;
        mc.thePlayer.rotationPitch = 90.0F;
    }

    /** Tick start: VANILLA key handling, takeoff detection (VANILLA), placement, progress. */
    private void climbTick()
    {
        EntityPlayerSP p = mc.thePlayer;
        look();
        if (!engine())
        {
            // takeoff: the previous tick's update jumped (the feet rose above the support)
            boolean airborne = p.posY > support + 1e-6D;
            if (airborne && !wasAirborne && p.motionY > 0.0D) onTakeoff(tick - 1);
            wasAirborne = airborne;
            // presses are seen at the first tick start at or after their real time
            if (!jumpDown && !Double.isNaN(pressTarget) && tick >= Math.ceil(pressTarget - 1e-9))
            {
                setJump(true);
                pressTarget = Double.NaN;
            }
        }
        place(p);

        if (support >= 100 + BLOCKS && p.onGround && Math.abs(p.posY - support) < 1e-6 && doneTick < 0)
        {
            doneTick = tick;
            setJump(false);
            counter = 0;
            phase = 4;
        }
        if (!Double.isNaN(pressedAt) && now() - pressedAt > 15.0D) // a press that never became a jump
        {
            ++lostPresses;
            pressedAt = Double.NaN;
        }
        if (++tick > 400 && phase == 3)
        {
            log(label() + ": timed out at support " + support);
            setJump(false);
            counter = 0;
            phase = 4;
        }
    }

    /** Engine modes: presses at frame resolution, takeoffs from the engine's jump record. */
    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent e)
    {
        if (phase != 3 || !engine() || !ClientSetup.engine.isActive()) return;
        double j = ClientSetup.engine.lastJumpTau();
        if (!Double.isNaN(j) && (Double.isNaN(lastEngineJump) || j != lastEngineJump))
        {
            lastEngineJump = j;
            onTakeoff(j);
        }
        if (!jumpDown && !Double.isNaN(pressTarget) && ClientSetup.engine.tauNow() >= pressTarget)
        {
            setJump(true);
            pressTarget = Double.NaN;
        }
    }

    private double now()
    {
        return engine() ? ClientSetup.engine.tauNow() : tick;
    }

    private void onTakeoff(double t)
    {
        ++takeoffs;
        if (startTick < 0) startTick = tick;
        if (Double.isNaN(firstTakeoff)) firstTakeoff = t;
        lastTakeoff = t;
        pressedAt = Double.NaN;
        if (STRATEGIES[strategy()].equals("HOLD")) return;
        setJump(false);
        // this jump places the block at the current support: press again only if more blocks follow
        if (support + 1 < 100 + BLOCKS) pressTarget = t + AIRTIME + PRESS_OFFSET[strategy()];
    }

    private void setJump(boolean down)
    {
        if (down == jumpDown) return;
        jumpDown = down;
        if (down) pressedAt = now();
        if (engine()) ClientSetup.engine.injectKeys(TickInput.fromKeyString(down ? "----J--" : "-------", 0.0F, 90.0F));
        else KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), down);
    }

    /** Places the block under the feet once they clear the cell (first or last eligible tick start). */
    private void place(EntityPlayerSP p)
    {
        if (support >= 100 + BLOCKS) return;
        BlockPos cell = new BlockPos(ox, support, oz);
        if (!mc.theWorld.isAirBlock(cell)) return;
        double feet = p.getEntityBoundingBox().minY;
        if (feet < support + 1.0D - 1e-9D) return;
        if (lateplace() && feet + p.motionY >= support + 1.0D) return; // still clear at the next tick start: wait
        ItemStack held = p.getHeldItem();
        if (held == null) return;
        mc.playerController.onPlayerRightClick(p, mc.theWorld, held, cell.down(), EnumFacing.UP, new Vec3(ox + 0.5D, support, oz + 0.5D));
        if (!mc.theWorld.isAirBlock(cell))
        {
            ++placedClient;
            ++support;
            if (support >= 100 + BLOCKS) setJump(false); // last block: stop holding space before the landing
        }
    }

    private void startVariant()
    {
        ClientSetup.CONFIG.applyForTest(TactileConfig.Mode.valueOf(mode()), rate());
        setJump(false);
        jumpDown = false;
        final MinecraftServer server = mc.getIntegratedServer();
        TestWorld.protectPlayer(server);
        final WorldServer world = server.worldServerForDimension(0);
        BlockPos spawn = world.getSpawnPoint();
        ox = spawn.getX() - 20 + 4 * (variant % 10); // near spawn; a fresh column per variant
        oz = spawn.getZ() + 20 + 4 * (variant / 10);
        final int x0 = ox, z0 = oz;
        server.addScheduledTask(new Runnable()
        {
            public void run()
            {
                for (int x = -1; x <= 1; ++x)
                    for (int z = -1; z <= 1; ++z)
                    {
                        world.setBlockState(new BlockPos(x0 + x, 99, z0 + z), Blocks.stone.getDefaultState(), 2);
                        for (int y = 100; y <= 100 + BLOCKS + 4; ++y) world.setBlockToAir(new BlockPos(x0 + x, y, z0 + z));
                    }
                EntityPlayerMP mp = server.getConfigurationManager().playerEntityList.get(0);
                mp.inventory.currentItem = 0;
                mp.inventory.setInventorySlotContents(0, new ItemStack(Blocks.stone, 64));
                mp.inventoryContainer.detectAndSendChanges();
                mp.setSprinting(false);
                mp.playerNetServerHandler.setPlayerLocation(x0 + 0.5D, 100.0D, z0 + 0.5D, 0.0F, 90.0F);
                server.getConfigurationManager().serverUpdateMountedMovingPlayer(mp);
            }
        });
        mc.thePlayer.inventory.currentItem = 0;
        counter = 0;
        phase = 2;
    }

    private void finishVariant()
    {
        int present = 0;
        for (int y = 100; y < 100 + BLOCKS; ++y) if (mc.theWorld.getBlockState(new BlockPos(ox, y, oz)).getBlock() == Blocks.stone) ++present;
        int climbed = support - 100;
        // first takeoff to the landing commit on the last block (its takeoff + AIRTIME), from the jump record in every mode
        double ticks = doneTick >= 0 ? lastTakeoff + AIRTIME - firstTakeoff : Double.NaN;
        String engineDiag = engine() ? String.format(", replays %d, corrections %d", ClientSetup.engine.diag.replays - replays0,
            ClientSetup.engine.diag.corrections - corrections0) : "";
        log(String.format("%s: climbed %d, ticks %.2f (%.2f per block), jumps %d, lost presses %d, ghosts %d%s, final y %.3f",
            label(), climbed, ticks, ticks / climbed, takeoffs, lostPresses, placedClient - present, engineDiag, mc.thePlayer.posY - 100.0D));
        if (++variant >= MODES.length * STRATEGIES.length * PLACEMENT.length)
        {
            log("done");
            mc.shutdown();
            phase = 9;
            return;
        }
        startVariant();
    }

    private static void log(String s)
    {
        System.out.println("[tactile-pillar] " + s);
    }
}
