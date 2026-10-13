package com.tactile.client;

import java.util.Locale;

import com.tactile.client.live.TactileConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.DamageSource;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Getting hit (HRMOVE_HITPROBE=1 with HRMOVE_INPUT=SCRIPTED). The player stands still on a stone floor and is hit by
 * vanilla's own damage code on the server: a zombie (mob damage), a player (a FakePlayer's attack, standing), and a
 * sprinting player (extra knockback). The knockback reaches the client as a velocity packet. Per trial
 * ([tactile-hit]): the distance pushed (at rest) and the peak height (tick-end positions, so every mode is sampled alike), and the delay from
 * the packet's arrival to the first drawn movement. Modes: VANILLA, EXACT 960, STEPPED 20.
 */
public final class HitProbe
{
    private static final Object[][] MODES = {{"VANILLA", 960}, {"EXACT", 960}, {"STEPPED", 20}};
    private static final String[] HITS = {"zombie", "player", "sprinting player"};
    private static final int TRIALS = 5;

    private final Minecraft mc = Minecraft.getMinecraft();
    private int phase, counter, variant, trial, ox, oz;
    private double startY, startZ, peakY, restZ;
    private long hitRequestNanos, arrivalNanos, firstMoveNanos;
    private double lastMotionY, lastDrawnY, lastDrawnZ;
    private boolean armed;
    private final StringBuilder summary = new StringBuilder();
    private double sumDist, sumPeak, sumDelay, minDist = Double.MAX_VALUE, maxDist;

    public static boolean requested()
    {
        return "1".equals(System.getenv("HRMOVE_HITPROBE"));
    }

    private String mode()
    {
        return (String)MODES[variant / HITS.length][0];
    }

    private String hit()
    {
        return HITS[variant % HITS.length];
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
                    mc.gameSettings.limitFramerate = 260;
                    mc.gameSettings.enableVsync = false;
                    mc.launchIntegratedServer("hrm_baseline", "hrm_baseline", new WorldSettings(0L, WorldSettings.GameType.SURVIVAL, false, false, WorldType.FLAT));
                    phase = 1;
                }
                break;
            case 1:
                if (TestWorld.ensurePlayerReady(mc) && ++counter > 60)
                {
                    TestWorld.protectPlayer(mc.getIntegratedServer());
                    build();
                    startVariant();
                }
                break;
            case 2: // settle standing still, then request a hit at a random moment
                if (!TestWorld.ensurePlayerReady(mc)) break;
                if (++counter >= 40 && mc.thePlayer.onGround && mc.thePlayer.motionY <= 0.0D && Math.abs(mc.thePlayer.motionZ) < 1e-6D
                    && ClientSetup.engine.isActive() == !mode().equals("VANILLA"))
                {
                    startY = mc.thePlayer.posY;
                    startZ = mc.thePlayer.posZ;
                    peakY = startY;
                    arrivalNanos = firstMoveNanos = 0L;
                    lastMotionY = mc.thePlayer.motionY;
                    lastDrawnY = Double.NaN;
                    armed = true;
                    requestHit();
                    counter = 0;
                    phase = 3;
                }
                break;
            case 3: // the knockback plays out; finish when at rest
                if (mc.thePlayer.posY > peakY) peakY = mc.thePlayer.posY;
                if (++counter >= 50 && mc.thePlayer.onGround && Math.abs(mc.thePlayer.motionZ) < 1e-4D)
                {
                    armed = false;
                    restZ = mc.thePlayer.posZ;
                    report();
                    counter = 0;
                    if (++trial >= TRIALS) nextVariant();
                    else
                    {
                        teleport();
                        phase = 2;
                    }
                }
                break;
            default:
                break;
        }
    }

    /** Per frame: the packet's arrival (the entity's motion changed outside a tick) and the first drawn movement. */
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e)
    {
        if (!armed || e.phase != TickEvent.Phase.START || mc.thePlayer == null) return;
        // RenderTickEvent START comes right after the game loop ran scheduled tasks (packets) and any ticks
        if (arrivalNanos == 0L && mc.thePlayer.motionY > 0.2D && lastMotionY <= 0.2D) arrivalNanos = System.nanoTime();
        lastMotionY = mc.thePlayer.motionY;
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent e)
    {
        if (!armed) return;
        EntityPlayerSP p = mc.thePlayer;
        double y = p.prevPosY + (p.posY - p.prevPosY) * e.partialTicks, z = p.prevPosZ + (p.posZ - p.prevPosZ) * e.partialTicks;
        if (firstMoveNanos == 0L && arrivalNanos != 0L && !Double.isNaN(lastDrawnY) && (y > lastDrawnY + 1e-4D || Math.abs(z - lastDrawnZ) > 1e-4D))
            firstMoveNanos = System.nanoTime();
        lastDrawnY = y;
        lastDrawnZ = z;
    }

    private void requestHit()
    {
        final MinecraftServer server = mc.getIntegratedServer();
        final WorldServer world = server.worldServerForDimension(0);
        final String kind = hit();
        hitRequestNanos = System.nanoTime();
        server.addScheduledTask(new Runnable()
        {
            public void run()
            {
                EntityPlayerMP mp = server.getConfigurationManager().playerEntityList.get(0);
                mp.capabilities.disableDamage = false; // damage (and so knockback) must apply
                mp.setHealth(mp.getMaxHealth());
                mp.hurtResistantTime = 0;
                if (kind.equals("zombie"))
                {
                    EntityZombie z = new EntityZombie(world);
                    z.setPosition(mp.posX, mp.posY, mp.posZ - 1.5D);
                    mp.attackEntityFrom(DamageSource.causeMobDamage(z), 1.0F);
                }
                else
                {
                    FakePlayer f = FakePlayerFactory.getMinecraft(world);
                    f.setPositionAndRotation(mp.posX, mp.posY, mp.posZ - 1.5D, 0.0F, 0.0F); // facing +z, at the player
                    f.setSprinting(kind.equals("sprinting player"));
                    f.attackTargetEntityWithCurrentItem(mp);
                }
                mp.capabilities.disableDamage = true;
            }
        });
    }

    private void report()
    {
        double dist = restZ - startZ, peak = peakY - startY;
        double delay = arrivalNanos != 0L && firstMoveNanos != 0L ? (firstMoveNanos - arrivalNanos) / 1e6 : Double.NaN;
        log(String.format(Locale.ROOT, "%-8s %-17s trial %d: pushed %.6f blocks, peak %.6f, arrival to first drawn movement %.1f ms", mode(), hit(), trial,
            dist, peak, delay));
        sumDist += dist;
        sumPeak += peak;
        sumDelay += Double.isNaN(delay) ? 0.0D : delay;
        minDist = Math.min(minDist, dist);
        maxDist = Math.max(maxDist, dist);
    }

    private void nextVariant()
    {
        summary.append(String.format(Locale.ROOT, "%-8s %-17s pushed mean %.6f (min %.6f, max %.6f), peak mean %.6f, first drawn movement %.1f ms after arrival%n",
            mode(), hit(), sumDist / TRIALS, minDist, maxDist, sumPeak / TRIALS, sumDelay / TRIALS));
        sumDist = sumPeak = sumDelay = maxDist = 0.0D;
        minDist = Double.MAX_VALUE;
        trial = 0;
        if (++variant >= MODES.length * HITS.length)
        {
            for (String line : summary.toString().split("\n")) log("summary: " + line.trim());
            mc.shutdown();
            phase = 9;
            return;
        }
        startVariant();
    }

    private void startVariant()
    {
        ClientSetup.CONFIG.applyForTest(TactileConfig.Mode.valueOf(mode()), (Integer)MODES[variant / HITS.length][1]);
        teleport();
        phase = 2;
        counter = 0;
    }

    private void build()
    {
        final MinecraftServer server = mc.getIntegratedServer();
        final WorldServer world = server.worldServerForDimension(0);
        BlockPos spawn = world.getSpawnPoint();
        ox = spawn.getX() - 30;
        oz = spawn.getZ() - 30;
        final int x0 = ox, z0 = oz;
        server.addScheduledTask(new Runnable()
        {
            public void run()
            {
                world.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
                server.setDifficultyForAllWorlds(EnumDifficulty.EASY); // peaceful turns mob damage to 0 (no hit, no knockback)
                for (int x = -3; x <= 3; ++x)
                    for (int z = -3; z <= 12; ++z)
                    {
                        world.setBlockState(new BlockPos(x0 + x, 99, z0 + z), Blocks.stone.getDefaultState(), 2);
                        for (int y = 100; y <= 104; ++y) world.setBlockToAir(new BlockPos(x0 + x, y, z0 + z));
                    }
            }
        });
    }

    private void teleport()
    {
        final MinecraftServer server = mc.getIntegratedServer();
        final double x = ox + 0.5D, z = oz + 0.5D;
        server.addScheduledTask(new Runnable()
        {
            public void run()
            {
                EntityPlayerMP mp = server.getConfigurationManager().playerEntityList.get(0);
                mp.playerNetServerHandler.setPlayerLocation(x, 100.0D, z, 0.0F, 0.0F);
                server.getConfigurationManager().serverUpdateMountedMovingPlayer(mp);
            }
        });
    }

    private static void log(String s)
    {
        System.out.println("[tactile-hit] " + s);
    }
}
