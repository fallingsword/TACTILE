package com.tactile.client;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.tactile.core.Box;
import com.tactile.core.Course;
import com.tactile.core.PlayerState;
import com.tactile.core.Scenario;
import com.tactile.core.TickInput;
import com.tactile.core.Trace;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/**
 * Phase 1 baseline harness. Unmodified vanilla movement is recorded here, with no movement hook
 * installed. The runner creates a superflat survival world, builds each canonical course from
 * {@link Scenario#corpus()}, drives the key bindings tick by tick through the ordinary KeyBinding
 * state, records the player before (ClientTickEvent START) and after (END) each tick, writes one CSV
 * per scenario to run/hrm-baseline, and then shuts the client down.
 *
 * Enabled only when the environment variable HRMOVE_BASELINE=1 is set. Do not touch mouse or keyboard
 * while it runs: real input events would override the scripted key states (rows are flagged).
 */
public final class BaselineRunner
{
    private enum Phase { WAIT_MENU, LOADING, BUILD, SETTLE, RUN, DONE }

    private static final String WORLD_FOLDER = "hrm_baseline";
    private static final int SETTLE_MIN_TICKS = 30;
    private static final int SETTLE_TIMEOUT = 400;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final List<Scenario> scenarios;
    private final File outDir;
    private final Field jumpTicksField = ReflectionHelper.findField(EntityLivingBase.class, "jumpTicks", "field_70773_bE");
    private final Field sprintToggleField = ReflectionHelper.findField(EntityPlayerSP.class, "sprintToggleTimer", "field_71156_d");

    private Phase phase = Phase.WAIT_MENU;
    private int index;
    private int counter;
    private int stableTicks;
    private int teleportCountdown;
    private int originX, originY, originZ;
    private Trace trace;
    private Trace.Row pending;
    private PlayerState lastPost;
    private final List<String> summary = new ArrayList<String>();

    public BaselineRunner()
    {
        String set = System.getenv("HRMOVE_BASELINE_SET");
        List<Scenario> all = "fallback".equalsIgnoreCase(set) ? Scenario.fallbackCorpus()
            : "medium".equalsIgnoreCase(set) ? Scenario.mediumCorpus() : "climb".equalsIgnoreCase(set) ? Scenario.climbCorpus() : "items".equalsIgnoreCase(set) ? Scenario.itemCorpus()
            : "all".equalsIgnoreCase(set) ? Scenario.all() : Scenario.corpus();
        String only = System.getenv("HRMOVE_BASELINE_ONLY");
        if (only != null && !only.trim().isEmpty())
        {
            List<String> names = Arrays.asList(only.split(","));
            List<Scenario> filtered = new ArrayList<Scenario>();
            for (Scenario s : all) if (names.contains(s.name)) filtered.add(s);
            all = filtered;
        }
        this.scenarios = all;
        String dir = System.getenv("HRMOVE_BASELINE_DIR"); // keep reference traces untouched in verification runs
        this.outDir = new File(mc.mcDataDir, dir == null || dir.trim().isEmpty() ? "hrm-baseline" : dir.trim());
    }

    public static boolean requested()
    {
        return "1".equals(System.getenv("HRMOVE_BASELINE"));
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event)
    {
        try
        {
            if (event.phase == TickEvent.Phase.START) onStart();
            else onEnd();
        }
        catch (Exception e)
        {
            log("FAILED: " + e);
            e.printStackTrace();
            releaseKeys();
            phase = Phase.DONE;
            finish();
        }
    }

    private void onStart() throws Exception
    {
        switch (phase)
        {
            case WAIT_MENU:
                if (mc.theWorld == null && mc.currentScreen instanceof GuiMainMenu)
                {
                    mc.gameSettings.pauseOnLostFocus = false;
                    WorldSettings settings = new WorldSettings(0L, WorldSettings.GameType.SURVIVAL, false, false, WorldType.FLAT);
                    log("launching integrated server '" + WORLD_FOLDER + "' with " + scenarios.size() + " scenarios");
                    mc.launchIntegratedServer(WORLD_FOLDER, WORLD_FOLDER, settings);
                    phase = Phase.LOADING;
                    counter = 0;
                }
                break;

            case LOADING:
                if (mc.getIntegratedServer() != null && TestWorld.ensurePlayerReady(mc) && ++counter > 40)
                {
                    mc.gameSettings.pauseOnLostFocus = false;
                    String fps = System.getenv("HRMOVE_FPS"); // e.g. 260 = unlimited, for profiling
                    if (fps != null && !fps.trim().isEmpty())
                    {
                        mc.gameSettings.limitFramerate = Integer.parseInt(fps.trim());
                        mc.gameSettings.enableVsync = false;
                        org.lwjgl.opengl.Display.setVSyncEnabled(false);
                    }
                    TestWorld.protectPlayer(mc.getIntegratedServer());
                    if (ClientSetup.engine != null) ClientSetup.engine.diag.reset();
                    phase = Phase.BUILD;
                }
                break;

            case BUILD:
                if (index >= scenarios.size())
                {
                    phase = Phase.DONE;
                    finish();
                    return;
                }
                if (!TestWorld.ensurePlayerReady(mc)) break;
                TestWorld.protectPlayer(mc.getIntegratedServer());
                buildScenario(scenarios.get(index));
                teleportCountdown = 0;
                phase = Phase.SETTLE;
                counter = 0;
                stableTicks = 0;
                break;

            case SETTLE:
            {
                releaseKeys();
                if (!TestWorld.ensurePlayerReady(mc)) break;
                Scenario s = scenarios.get(index);
                if (++teleportCountdown == 5) teleportToStart(s);
                if (teleportCountdown <= 5) break;
                EntityPlayerSP p = mc.thePlayer;
                p.rotationYaw = s.startYaw;
                p.rotationPitch = 0.0F;
                double dx = p.posX - (originX + s.startX);
                double dz = p.posZ - (originZ + s.startZ);
                boolean atStart = Math.abs(dx) < 1.0E-6 && Math.abs(dz) < 1.0E-6 && Math.abs(p.posY - (originY + s.startY)) < 1.0E-6;
                // a flying scenario settles hovering (flying, no motion); the others standing on the ground
                // On foot, atStart (exact start height) already means standing: on slime vanilla's onGround and motionY
                // alternate every tick (a tiny bounce), so neither can be required.
                boolean still = (!s.startFlying || p.capabilities.isFlying && p.motionY == 0.0D)
                    && p.motionX == 0.0D && p.motionZ == 0.0D && !p.isSprinting();
                stableTicks = atStart && still ? stableTicks + 1 : 0;

                if (stableTicks >= 10 && ++counter >= SETTLE_MIN_TICKS)
                {
                    trace = new Trace();
                    trace.scenario = s.name;
                    trace.originX = originX;
                    trace.originY = originY;
                    trace.originZ = originZ;
                    lastPost = null;
                    counter = 0;
                    phase = Phase.RUN;
                    // fall through to apply tick 0 in this same START
                    runStart(s);
                }
                else if (counter++ > SETTLE_TIMEOUT)
                {
                    summary.add(s.name + ": SETTLE TIMEOUT at " + p.posX + "," + p.posY + "," + p.posZ);
                    log("scenario " + s.name + " did not settle; skipped");
                    ++index;
                    phase = Phase.BUILD;
                }
                break;
            }

            case RUN:
                runStart(scenarios.get(index));
                break;

            default:
                break;
        }
    }

    private void runStart(Scenario s)
    {
        com.tactile.client.live.LiveEngine engine = ClientSetup.engine;
        if (counter == 0 && engine != null && engine.isActive())
        {
            // High-resolution validation run: the engine reads inputs from the script, not from KeyBinding.
            engine.loadScript(s.script(), engine.isTickDriven() ? 0 : 1);
        }
        TickInput in = s.script().get(counter);
        GameSettings gs = mc.gameSettings;
        KeyBinding.setKeyBindState(gs.keyBindForward.getKeyCode(), in.forward);
        KeyBinding.setKeyBindState(gs.keyBindBack.getKeyCode(), in.back);
        KeyBinding.setKeyBindState(gs.keyBindLeft.getKeyCode(), in.left);
        KeyBinding.setKeyBindState(gs.keyBindRight.getKeyCode(), in.right);
        KeyBinding.setKeyBindState(gs.keyBindJump.getKeyCode(), in.jump);
        KeyBinding.setKeyBindState(gs.keyBindSneak.getKeyCode(), in.sneak);
        KeyBinding.setKeyBindState(gs.keyBindSprint.getKeyCode(), in.sprint);
        KeyBinding.setKeyBindState(gs.keyBindUseItem.getKeyCode(), in.use); // right mouse: vanilla handles the item use
        mc.thePlayer.rotationYaw = in.yaw;
        mc.thePlayer.rotationPitch = in.pitch;

        pending = new Trace.Row();
        pending.tick = counter;
        pending.input = in;
        pending.pre = snapshot(mc.thePlayer);
        pending.externalChange = lastPost != null && !sameState(lastPost, pending.pre);
    }

    private void onEnd() throws Exception
    {
        if (phase != Phase.RUN || pending == null) return;
        EntityPlayerSP p = mc.thePlayer;
        GameSettings gs = mc.gameSettings;
        TickInput in = pending.input;
        boolean keysIntact = gs.keyBindForward.isKeyDown() == in.forward && gs.keyBindBack.isKeyDown() == in.back
            && gs.keyBindLeft.isKeyDown() == in.left && gs.keyBindRight.isKeyDown() == in.right
            && gs.keyBindJump.isKeyDown() == in.jump && gs.keyBindSneak.isKeyDown() == in.sneak
            && gs.keyBindSprint.isKeyDown() == in.sprint;
        pending.post = snapshot(p);
        // The model input is the item use the client actually had during this tick's move (started/stopped by the
        // click handling before it; finished eating arrives as a packet before the tick), not the scripted button.
        if (p.isUsingItem() != in.use) ++useMismatches;
        pending.input = in.withUse(p.isUsingItem());
        pending.contaminated = p.rotationYaw != in.yaw || mc.currentScreen != null || !keysIntact;
        trace.rows.add(pending);
        lastPost = pending.post;
        pending = null;

        if (++counter >= scenarios.get(index).script().size())
        {
            releaseKeys();
            File file = new File(outDir, trace.scenario + ".csv");
            trace.write(file);
            int ext = 0, bad = 0;
            for (Trace.Row r : trace.rows)
            {
                if (r.externalChange) ++ext;
                if (r.contaminated) ++bad;
            }
            String line = trace.scenario + ": " + trace.rows.size() + " ticks, externalChanges=" + ext + ", contaminated=" + bad
                + (useMismatches > 0 ? ", item use differs from the script on " + useMismatches + " ticks" : "");
            useMismatches = 0;
            summary.add(line);
            log("recorded " + line + " -> " + file.getName());
            ++index;
            phase = Phase.BUILD;
        }
    }

    private void buildScenario(final Scenario s)
    {
        final MinecraftServer server = mc.getIntegratedServer();
        final WorldServer world = server.worldServerForDimension(0);
        BlockPos spawn = world.getSpawnPoint();
        originX = spawn.getX() + 40 * index;
        originY = 100;
        originZ = spawn.getZ();
        final int ox = originX, oy = originY, oz = originZ;
        final Course course = s.course(ox, oy, oz);

        // Blocks first; the teleport follows a few ticks later (see SETTLE). Teleporting in the same
        // task let the client stand briefly over a platform it had not received yet, and the server
        // then corrected the resulting fall.
        server.addScheduledTask(new Runnable()
        {
            public void run()
            {
                int[] c = s.clearBounds;
                for (int x = c[0]; x <= c[3]; ++x)
                    for (int y = c[1]; y <= c[4]; ++y)
                        for (int z = c[2]; z <= c[5]; ++z)
                            world.setBlockToAir(new BlockPos(ox + x, oy + y, oz + z));

                for (Course.Cell cell : course.cells())
                {
                    BlockPos pos = new BlockPos(cell.x, cell.y, cell.z);
                    switch (cell.shape)
                    {
                        case FULL: world.setBlockState(pos, Blocks.stone.getDefaultState(), 2); break;
                        case BOTTOM_SLAB: world.setBlockState(pos, Blocks.stone_slab.getDefaultState(), 2); break;
                        case WATER: world.setBlockState(pos, Blocks.water.getDefaultState(), 2); break;
                        case LAVA: world.setBlockState(pos, Blocks.lava.getDefaultState(), 2); break;
                        case SOUL_SAND: world.setBlockState(pos, Blocks.soul_sand.getDefaultState(), 2); break;
                        case SLIME: world.setBlockState(pos, Blocks.slime_block.getDefaultState(), 2); break;
                        case WEB: world.setBlockState(pos, Blocks.web.getDefaultState(), 2); break;
                        case LADDER_NORTH: world.setBlockState(pos, Blocks.ladder.getDefaultState().withProperty(net.minecraft.block.BlockLadder.FACING, net.minecraft.util.EnumFacing.NORTH), 2); break;
                        case LADDER_SOUTH: world.setBlockState(pos, Blocks.ladder.getDefaultState().withProperty(net.minecraft.block.BlockLadder.FACING, net.minecraft.util.EnumFacing.SOUTH), 2); break;
                        case LADDER_WEST: world.setBlockState(pos, Blocks.ladder.getDefaultState().withProperty(net.minecraft.block.BlockLadder.FACING, net.minecraft.util.EnumFacing.WEST), 2); break;
                        case LADDER_EAST: world.setBlockState(pos, Blocks.ladder.getDefaultState().withProperty(net.minecraft.block.BlockLadder.FACING, net.minecraft.util.EnumFacing.EAST), 2); break;
                        // corpus convention: vines hang on the wall to their south (+z)
                        case VINE: world.setBlockState(pos, Blocks.vine.getDefaultState().withProperty(net.minecraft.block.BlockVine.SOUTH, true), 2); break;
                        default: break;
                    }
                }
            }
        });
        log("building " + s.name + " at origin " + ox + "," + oy + "," + oz);
    }

    /**
     * Optional conditions for a recording: HRMOVE_BASELINE_EFFECT=potionId:amplifier[,...] (e.g. 1:1 = Speed II,
     * 2:0 = Slowness, 8:1 = Jump Boost II, 15:0 = Blindness, 16:0 = Night Vision) and HRMOVE_BASELINE_FOOD=level.
     * Applied on the server before every scenario, long enough to cover it.
     */
    private static void applyConditions(EntityPlayerMP mp)
    {
        String effects = System.getenv("HRMOVE_BASELINE_EFFECT");
        mp.clearActivePotions(); // effects are saved with the player: never inherit them from an earlier recording
        if (effects != null && !effects.trim().isEmpty())
        {
            for (String e : effects.split(","))
            {
                String[] p = e.trim().split(":");
                mp.addPotionEffect(new net.minecraft.potion.PotionEffect(Integer.parseInt(p[0]), 20 * 60, p.length > 1 ? Integer.parseInt(p[1]) : 0));
            }
        }
        String food = System.getenv("HRMOVE_BASELINE_FOOD");
        boolean setFood = food != null && !food.trim().isEmpty();
        // peaceful refills food every 10 ticks unless natural regeneration is off
        mp.worldObj.getGameRules().setOrCreateGameRule("naturalRegeneration", setFood ? "false" : "true");
        if (setFood) mp.getFoodStats().setFoodLevel(Integer.parseInt(food.trim()));
    }

    private int useMismatches;

    private void teleportToStart(final Scenario s)
    {
        mc.thePlayer.inventory.currentItem = 0;
        final MinecraftServer server = mc.getIntegratedServer();
        final int ox = originX, oy = originY, oz = originZ;
        server.addScheduledTask(new Runnable()
        {
            public void run()
            {
                EntityPlayerMP mp = server.getConfigurationManager().playerEntityList.get(0);
                mp.fallDistance = 0.0F;
                mp.setSprinting(false);
                applyConditions(mp);
                equip(mp, s);
                // flying scenarios hover at the start; all others start on foot without flight
                mp.capabilities.allowFlying = s.startFlying;
                mp.capabilities.isFlying = s.startFlying;
                mp.sendPlayerAbilities();
                mp.playerNetServerHandler.setPlayerLocation(ox + s.startX, oy + s.startY, oz + s.startZ, s.startYaw, 0.0F);
            }
        });
    }

    /** The scenario's held item in the first hotbar slot (arrows in the second for a bow) and its food level. */
    private static void equip(EntityPlayerMP mp, Scenario s)
    {
        mp.inventory.clear();
        if ("bread".equals(s.holding)) mp.inventory.setInventorySlotContents(0, new net.minecraft.item.ItemStack(net.minecraft.init.Items.bread, 64));
        else if ("sword".equals(s.holding)) mp.inventory.setInventorySlotContents(0, new net.minecraft.item.ItemStack(net.minecraft.init.Items.diamond_sword));
        else if ("bow".equals(s.holding))
        {
            mp.inventory.setInventorySlotContents(0, new net.minecraft.item.ItemStack(net.minecraft.init.Items.bow));
            mp.inventory.setInventorySlotContents(1, new net.minecraft.item.ItemStack(net.minecraft.init.Items.arrow, 64));
        }
        mp.inventory.currentItem = 0;
        mp.inventoryContainer.detectAndSendChanges();
        // EntityPlayer.canEat refuses while damage is disabled (the test world's invulnerability): eating scenarios run
        // without it (peaceful, a flat floor: nothing can hurt the player); every other scenario keeps it.
        mp.capabilities.disableDamage = !"bread".equals(s.holding);
        mp.sendPlayerAbilities();
        if (s.food >= 0)
        {
            mp.worldObj.getGameRules().setOrCreateGameRule("naturalRegeneration", "false"); // peaceful would refill it
            mp.getFoodStats().setFoodLevel(s.food);
        }
    }

    private PlayerState snapshot(EntityPlayerSP p)
    {
        PlayerState s = new PlayerState();
        AxisAlignedBB b = p.getEntityBoundingBox();
        s.box = new Box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
        s.motionX = p.motionX;
        s.motionY = p.motionY;
        s.motionZ = p.motionZ;
        s.onGround = p.onGround;
        s.collidedHorizontally = p.isCollidedHorizontally;
        s.collidedVertically = p.isCollidedVertically;
        s.sprinting = p.isSprinting();
        s.sprintingTicksLeft = p.sprintingTicksLeft;
        s.jumpMovementFactor = p.jumpMovementFactor;
        s.yaw = p.rotationYaw;
        try
        {
            s.jumpTicks = jumpTicksField.getInt(p);
            s.sprintToggleTimer = sprintToggleField.getInt(p);
            s.inWeb = inWebField.getBoolean(p);
        }
        catch (IllegalAccessException e)
        {
            throw new RuntimeException(e);
        }
        s.flying = p.capabilities.isFlying;
        s.flySpeed = p.capabilities.getFlySpeed();
        return s;
    }

    private final Field inWebField = ReflectionHelper.findField(net.minecraft.entity.Entity.class, "isInWeb", "field_70134_J");

    private static boolean sameState(PlayerState a, PlayerState b)
    {
        return a.box.minX == b.box.minX && a.box.minY == b.box.minY && a.box.minZ == b.box.minZ
            && a.motionX == b.motionX && a.motionY == b.motionY && a.motionZ == b.motionZ
            && a.onGround == b.onGround;
    }

    private void releaseKeys()
    {
        GameSettings gs = mc.gameSettings;
        KeyBinding[] keys = {gs.keyBindForward, gs.keyBindBack, gs.keyBindLeft, gs.keyBindRight, gs.keyBindJump, gs.keyBindSneak, gs.keyBindSprint, gs.keyBindUseItem};
        for (KeyBinding k : keys) KeyBinding.setKeyBindState(k.getKeyCode(), false);
    }

    private void finish()
    {
        try
        {
            outDir.mkdirs();
            java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileWriter(new File(outDir, "_summary.txt")));
            for (String line : summary) w.println(line);
            if (ClientSetup.engine != null)
            {
                w.println("# engine diagnostics (limitFramerate=" + mc.gameSettings.limitFramerate + ", vsync=" + mc.gameSettings.enableVsync + ")");
                for (String line : ClientSetup.engine.diag.lines(ClientSetup.engine)) w.println("# " + line);
            }
            w.close();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        log("done; " + summary.size() + " scenarios. Shutting down client.");
        mc.shutdown();
    }

    private static void log(String msg)
    {
        System.out.println("[tactile-baseline] " + msg);
    }
}
