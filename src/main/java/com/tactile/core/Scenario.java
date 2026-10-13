package com.tactile.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A canonical movement test: block layout, start pose and a per-tick input script. Coordinates are
 * relative to an integer origin that the in-game runner chooses and records. The offline harness
 * rebuilds the course at the recorded origin, so floating-point positions match the client exactly.
 */
public final class Scenario
{
    public interface Layout
    {
        /** Adds cells relative to the origin; y = -1 is the floor layer, the floor surface is y = 0. */
        void build(Course course, int ox, int oy, int oz);
    }

    public final String name;
    public final String description;
    public final double startX, startY, startZ;
    public final float startYaw;
    /** Start in creative flight (allowFlying and isFlying), hovering at the start position. */
    public final boolean startFlying;
    /** Item in the hotbar's first slot during the run: "bread", "bow" (with arrows) or "sword"; null = nothing. */
    public final String holding;
    /** Food level for the run (eating needs less than 20); -1 = unchanged (full). */
    public final int food;
    public final Layout layout;
    /** Inclusive relative bounds cleared before building in-game. */
    public final int[] clearBounds;
    private final List<TickInput> script;

    private Scenario(Builder b)
    {
        this.name = b.name;
        this.description = b.description;
        this.startX = b.startX;
        this.startY = b.startY;
        this.startZ = b.startZ;
        this.startYaw = b.startYaw;
        this.startFlying = b.startFlying;
        this.holding = b.holding;
        this.food = b.food;
        this.layout = b.layout;
        this.clearBounds = b.clearBounds;
        this.script = Collections.unmodifiableList(new ArrayList<TickInput>(b.script));
    }

    public List<TickInput> script()
    {
        return script;
    }

    public Course course(int ox, int oy, int oz)
    {
        Course c = new Course();
        layout.build(c, ox, oy, oz);
        return c;
    }

    public static Builder builder(String name, String description)
    {
        return new Builder(name, description);
    }

    public static final class Builder
    {
        private final String name, description;
        private double startX = 0.5D, startY = 0.0D, startZ = 0.5D;
        private float startYaw;
        private boolean startFlying;
        private String holding;
        private int food = -1;
        private float yaw;
        private Layout layout;
        private int[] clearBounds = {-6, -6, -6, 6, 4, 6};
        private final List<TickInput> script = new ArrayList<TickInput>();

        private Builder(String name, String description)
        {
            this.name = name;
            this.description = description;
        }

        public Builder start(double x, double y, double z, float yaw)
        {
            this.startX = x;
            this.startY = y;
            this.startZ = z;
            this.startYaw = yaw;
            this.yaw = yaw;
            return this;
        }

        public Builder flying()
        {
            this.startFlying = true;
            return this;
        }

        /** Holds "bread", "bow" (arrows provided) or "sword" in the first hotbar slot. */
        public Builder holding(String item)
        {
            this.holding = item;
            return this;
        }

        public Builder food(int level)
        {
            this.food = level;
            return this;
        }

        public Builder layout(Layout layout, int x1, int y1, int z1, int x2, int y2, int z2)
        {
            this.layout = layout;
            this.clearBounds = new int[] {x1, y1, z1, x2, y2, z2};
            return this;
        }

        /** Holds the given keys ("WASDJNR" positions, '-' for up) for {@code ticks} ticks. */
        public Builder hold(int ticks, String keys)
        {
            for (int i = 0; i < ticks; ++i) script.add(TickInput.fromKeyString(keys, yaw, 0.0F));
            return this;
        }

        /** Holds keys while turning by {@code degreesPerTick} each tick. */
        public Builder holdTurning(int ticks, String keys, float degreesPerTick)
        {
            for (int i = 0; i < ticks; ++i)
            {
                yaw += degreesPerTick;
                script.add(TickInput.fromKeyString(keys, yaw, 0.0F));
            }
            return this;
        }

        public Scenario build()
        {
            return new Scenario(this);
        }
    }

    // ------------------------------------------------------------------ canonical corpus

    private static Layout floor(final int x1, final int z1, final int x2, final int z2)
    {
        return new Layout()
        {
            public void build(Course c, int ox, int oy, int oz)
            {
                c.fill(ox + x1, oy - 1, oz + z1, ox + x2, oy - 1, oz + z2, Course.Shape.FULL);
            }
        };
    }

    public static List<Scenario> corpus()
    {
        List<Scenario> list = new ArrayList<Scenario>();

        list.add(builder("stand", "Standing still on a flat floor")
            .layout(floor(-2, -2, 2, 2), -3, -2, -3, 3, 3, 3)
            .hold(30, "-------").build());

        list.add(builder("walk_stop", "Walk forward then release")
            .layout(floor(-2, -2, 2, 40), -3, -2, -3, 3, 3, 41)
            .hold(30, "W------").hold(20, "-------").build());

        list.add(builder("sprint_stop", "Sprint forward (sprint key) then release")
            .layout(floor(-2, -2, 2, 60), -3, -2, -3, 3, 3, 61)
            .hold(40, "W-----R").hold(20, "-------").build());

        list.add(builder("diag_strafe", "Forward+left at a non-axis yaw, then strafe only")
            .start(0.5D, 0.0D, 0.5D, 23.7F)
            .layout(floor(-25, -5, 25, 40), -26, -2, -6, 26, 3, 41)
            .hold(30, "WA-----").hold(10, "-A-----").hold(15, "-------").build());

        list.add(builder("jump_in_place", "Single jump tap in place")
            .layout(floor(-2, -2, 2, 2), -3, -2, -3, 3, 4, 3)
            .hold(2, "-------").hold(1, "----J--").hold(25, "-------").build());

        list.add(builder("sprint_jump_chain", "Hold forward+sprint+jump")
            .layout(floor(-2, -2, 2, 80), -3, -2, -3, 3, 4, 81)
            .hold(70, "W---J-R").hold(20, "-------").build());

        list.add(builder("walk_off_ledge", "Walk off a 4-block ledge and land")
            .layout(new Layout()
            {
                public void build(Course c, int ox, int oy, int oz)
                {
                    c.fill(ox - 2, oy - 1, oz - 2, ox + 2, oy - 1, oz + 2, Course.Shape.FULL);
                    c.fill(ox - 2, oy - 5, oz + 3, ox + 2, oy - 5, oz + 20, Course.Shape.FULL);
                }
            }, -3, -6, -3, 3, 3, 21)
            .hold(40, "W------").hold(10, "-------").build());

        list.add(builder("wall_scrape", "Sprint diagonally into a wall and slide along it")
            .start(0.5D, 0.0D, 0.5D, -30.0F)
            .layout(new Layout()
            {
                public void build(Course c, int ox, int oy, int oz)
                {
                    c.fill(ox - 5, oy - 1, oz - 2, ox + 5, oy - 1, oz + 30, Course.Shape.FULL);
                    c.fill(ox + 3, oy, oz - 2, ox + 3, oy + 2, oz + 30, Course.Shape.FULL);
                }
            }, -6, -2, -3, 6, 4, 31)
            .hold(40, "W-----R").hold(10, "-------").build());

        list.add(builder("slab_steps", "Walk up slab / block / slab-on-block steps")
            .layout(new Layout()
            {
                public void build(Course c, int ox, int oy, int oz)
                {
                    c.fill(ox - 2, oy - 1, oz - 2, ox + 2, oy - 1, oz + 20, Course.Shape.FULL);
                    c.fill(ox - 2, oy, oz + 4, ox + 2, oy, oz + 4, Course.Shape.BOTTOM_SLAB);
                    c.fill(ox - 2, oy, oz + 5, ox + 2, oy, oz + 20, Course.Shape.FULL);
                    c.fill(ox - 2, oy + 1, oz + 6, ox + 2, oy + 1, oz + 20, Course.Shape.BOTTOM_SLAB);
                }
            }, -3, -2, -3, 3, 4, 21)
            .hold(40, "W------").hold(10, "-------").build());

        list.add(builder("sneak_edge", "Sneak toward a platform edge, then sneak diagonally along it")
            .layout(floor(-2, -2, 2, 3), -3, -6, -3, 3, 3, 8)
            .hold(40, "W----N-").hold(25, "W--D-N-").hold(10, "-------").build());

        list.add(builder("jump_onto_block", "Sprint, then sprint-jump onto a 1-block ledge")
            .layout(new Layout()
            {
                public void build(Course c, int ox, int oy, int oz)
                {
                    c.fill(ox - 2, oy - 1, oz - 2, ox + 2, oy - 1, oz + 30, Course.Shape.FULL);
                    c.fill(ox - 2, oy, oz + 6, ox + 2, oy, oz + 30, Course.Shape.FULL);
                }
            }, -3, -2, -3, 3, 4, 31)
            .hold(4, "W-----R").hold(30, "W---J-R").hold(10, "-------").build());

        list.add(builder("head_hitter", "Sprint-jump under a ceiling 2 blocks above the floor")
            .layout(new Layout()
            {
                public void build(Course c, int ox, int oy, int oz)
                {
                    c.fill(ox - 2, oy - 1, oz - 2, ox + 2, oy - 1, oz + 60, Course.Shape.FULL);
                    c.fill(ox - 2, oy + 2, oz - 2, ox + 2, oy + 2, oz + 60, Course.Shape.FULL);
                }
            }, -3, -2, -3, 3, 4, 61)
            .hold(60, "W---J-R").hold(10, "-------").build());

        list.add(builder("turning_jumps", "Sprint-jump while turning, then forward with jump taps")
            .layout(floor(-30, -30, 30, 30), -31, -2, -31, 31, 4, 31)
            .holdTurning(40, "W---J-R", 3.0F)
            .hold(5, "W------").hold(1, "W---J--").hold(6, "W------").hold(1, "W---J--").hold(12, "W------")
            .hold(10, "-------").build());

        list.add(builder("double_tap_sprint", "Double-tap forward to trigger sprint")
            .layout(floor(-2, -2, 2, 40), -3, -2, -3, 3, 3, 41)
            .hold(1, "W------").hold(2, "-------").hold(25, "W------").hold(10, "-------").build());

        return list;
    }

    /** Every scenario with an offline model (the canonical, medium and climb corpora). */
    public static List<Scenario> all()
    {
        List<Scenario> list = corpus();
        list.addAll(mediumCorpus());
        list.addAll(climbCorpus());
        list.addAll(itemCorpus());
        return list;
    }

    /**
     * Using an item ('U': right mouse held; the recording stores the client's actual isUsingItem per tick). Food 14 lets
     * the player eat and still sprint (above 6). Bread uses stay under 32 ticks, so eating is never completed.
     */
    public static List<Scenario> itemCorpus()
    {
        List<Scenario> list = new ArrayList<Scenario>();
        list.add(builder("eat_walk", "Walk, eat while walking (input x0.2), stop eating, walk on")
            .layout(floor(-2, -2, 2, 40), -3, -2, -3, 3, 3, 41).holding("bread").food(14)
            .hold(10, "W------").hold(25, "W------U").hold(15, "W------").hold(10, "-------").build());
        list.add(builder("eat_sprint", "Sprint, start eating (sprint ends), stop eating with sprint held (sprint resumes)")
            .layout(floor(-2, -2, 2, 60), -3, -2, -3, 3, 3, 61).holding("bread").food(14)
            .hold(15, "W-----R").hold(20, "W-----RU").hold(20, "W-----R").hold(10, "-------").build());
        list.add(builder("eat_jump_doubletap", "Sprint-jump, eat in the air, double-tap forward while eating (no sprint), stop eating, double-tap")
            .layout(floor(-2, -2, 2, 60), -3, -2, -3, 3, 3, 61).holding("bread").food(14)
            .hold(8, "W-----R").hold(1, "W---J-R").hold(10, "W-----RU").hold(2, "-------U").hold(1, "W------U").hold(2, "-------U")
            .hold(10, "W------U").hold(3, "-------").hold(1, "W------").hold(2, "-------").hold(15, "W------").hold(10, "-------").build());
        list.add(builder("bow_strafe_shoot", "Strafe while drawing a bow, shoot, keep strafing; then jump while drawing")
            .layout(floor(-15, -2, 15, 40), -16, -2, -3, 16, 3, 41).holding("bow")
            .hold(5, "-A-----").hold(25, "-A-----U").hold(10, "-A-----").hold(1, "W---J-RU").hold(20, "W-----RU").hold(10, "-------").build());
        list.add(builder("sword_block_jump", "Sprint-jump chain while blocking with a sword, then without")
            .layout(floor(-2, -2, 2, 60), -3, -2, -3, 3, 3, 61).holding("sword")
            .hold(10, "W-----R").hold(30, "W---J-RU").hold(25, "W---J-R").hold(10, "-------").build());
        return list;
    }

    private static final int STAIRS = 12;

    /** A staircase of full blocks along +z: step i (z 2 + i) has its top i + 1 above the floor. Floor z -2..1. */
    private static Layout staircase()
    {
        return new Layout()
        {
            public void build(Course c, int ox, int oy, int oz)
            {
                c.fill(ox - 2, oy - 1, oz - 2, ox + 2, oy - 1, oz + 1, Course.Shape.FULL);
                for (int i = 0; i < STAIRS; ++i) c.fill(ox - 2, oy - 1, oz + 2 + i, ox + 2, oy + i, oz + 2 + i, Course.Shape.FULL);
            }
        };
    }

    /**
     * Space tapped again on every landing: the script presses jump for one tick exactly when vanilla is on the ground
     * at the tick start (from the second tick on), and releases it otherwise, so vanilla's jump cooldown never applies.
     * Generated by running VanillaModel on the course, so the recorded run follows the same script.
     */
    private static Scenario repress(String name, String description, String run, int ticks, int stopTicks)
    {
        Layout layout = staircase();
        Course c = new Course();
        layout.build(c, 0, 100, 0);
        PlayerState s = new PlayerState();
        s.box = Box.forPlayerAt(0.5D, 100.0D, 0.5D);
        s.onGround = true;
        s.motionY = -0.0784000015258789D;
        Builder b = builder(name, description).layout(layout, -3, -2, -3, 3, STAIRS + 4, STAIRS + 3);
        String jump = run.substring(0, 4) + "J" + run.substring(5);
        for (int t = 0; t < ticks; ++t)
        {
            String keys = t >= 2 && s.onGround ? jump : run;
            b.hold(1, keys);
            VanillaModel.tick(s, TickInput.fromKeyString(keys, 0.0F, 0.0F), c);
        }
        return b.hold(stopTicks, "-------").build();
    }

    /** Climbing: jumping up one block at a time, holding space or pressing it again on each landing. */
    public static List<Scenario> climbCorpus()
    {
        List<Scenario> list = new ArrayList<Scenario>();
        list.add(builder("staircase_hold", "Sprint up a 1-block staircase holding space (vanilla's jump cooldown sets the rhythm)")
            .layout(staircase(), -3, -2, -3, 3, STAIRS + 4, STAIRS + 3)
            .hold(70, "W---J-R").hold(10, "-------").build());
        list.add(repress("staircase_repress", "Sprint up a 1-block staircase, pressing space again on each landing", "W-----R", 70, 10));
        list.add(repress("staircase_walk_repress", "Walk up a 1-block staircase, pressing space again on each landing", "W------", 110, 10));
        return list;
    }

    /** True for scenarios that need water, lava or ladder physics (the DISCRETE mode does not model them). */
    public static boolean isMedium(String name)
    {
        for (Scenario s : mediumCorpus()) if (s.name.equals(name)) return true;
        return false;
    }

    /**
     * A pool of {@code liquid} sources, x -2..2, z 4..10, two deep (surface at the floor top), sunk into the floor
     * and walled with stone on every side so the flow vector is zero. Floor everywhere else, z -2..20.
     */
    private static Layout pool(final Course.Shape liquid)
    {
        return new Layout()
        {
            public void build(Course c, int ox, int oy, int oz)
            {
                c.fill(ox - 3, oy - 1, oz - 2, ox + 3, oy - 1, oz + 20, Course.Shape.FULL);
                c.fill(ox - 3, oy - 3, oz + 3, ox + 3, oy - 1, oz + 11, Course.Shape.FULL);
                c.fill(ox - 2, oy - 2, oz + 4, ox + 2, oy - 1, oz + 10, liquid);
            }
        };
    }

    /** A stone wall at z 3 (x -2..2, y 0..6) with a column of {@code climb} at z 2, x 0, y 0..5, on a floor. */
    private static Layout wall(final Course.Shape climb)
    {
        return new Layout()
        {
            public void build(Course c, int ox, int oy, int oz)
            {
                c.fill(ox - 3, oy - 1, oz - 2, ox + 3, oy - 1, oz + 4, Course.Shape.FULL);
                c.fill(ox - 2, oy, oz + 3, ox + 2, oy + 6, oz + 3, Course.Shape.FULL);
                c.fill(ox, oy, oz + 2, ox, oy + 5, oz + 2, climb);
            }
        };
    }

    /** Water, lava, ladders and vines: modeled by VanillaModel and the GRID contacts. */
    public static List<Scenario> mediumCorpus()
    {
        List<Scenario> list = new ArrayList<Scenario>();

        list.add(builder("water_swim", "Walk into a 2-deep pool, swim across, swim up and climb out over the far edge")
            .layout(pool(Course.Shape.WATER), -4, -4, -3, 4, 4, 21)
            .hold(20, "W------").hold(30, "W------").hold(50, "W---J--").hold(20, "W------").hold(10, "-------").build());

        list.add(builder("water_tread", "Into the pool, hold jump to rise and bob at the surface, then sink")
            .layout(pool(Course.Shape.WATER), -4, -4, -3, 4, 4, 21)
            .hold(16, "W------").hold(6, "-------").hold(45, "----J--").hold(30, "-------").build());

        list.add(builder("lava_swim", "Walk into a 2-deep lava pool, swim across, swim up and climb out")
            .layout(pool(Course.Shape.LAVA), -4, -4, -3, 4, 4, 21)
            .hold(20, "W------").hold(30, "W------").hold(60, "W---J--").hold(20, "W------").hold(10, "-------").build());

        list.add(builder("ladder_climb", "Climb a ladder, let go (slide), hold sneak, climb again, back off and fall")
            .layout(wall(Course.Shape.LADDER_NORTH), -4, -2, -3, 4, 8, 5)
            .hold(40, "W------").hold(12, "-------").hold(12, "-----N-").hold(10, "W----N-").hold(8, "--S----").hold(20, "-------").build());

        list.add(builder("ladder_jump", "Jump onto a ladder while walking into it, keep jumping, then release")
            .layout(wall(Course.Shape.LADDER_NORTH), -4, -2, -3, 4, 8, 5)
            .hold(25, "W---J--").hold(15, "W------").hold(25, "-------").build());

        list.add(builder("vine_climb", "Climb a vine column by walking into the wall behind it, then release")
            .layout(wall(Course.Shape.VINE), -4, -2, -3, 4, 8, 5)
            .hold(40, "W------").hold(20, "-------").build());

        list.add(builder("soulsand_walk", "Walk onto a soul sand strip, sprint and sprint-jump across it, stop")
            .layout(new Layout()
            {
                public void build(Course c, int ox, int oy, int oz)
                {
                    c.fill(ox - 2, oy - 1, oz - 2, ox + 2, oy - 1, oz + 30, Course.Shape.FULL);
                    c.fill(ox - 2, oy - 1, oz + 4, ox + 2, oy - 1, oz + 12, Course.Shape.SOUL_SAND);
                }
            }, -3, -2, -3, 3, 4, 31)
            .hold(20, "W------").hold(15, "W-----R").hold(20, "W---J-R").hold(10, "-------").build());

        // The platform edge is 1.5 blocks ahead: walking leaves it at tick 10 (8 ticks of walking stopped short).
        list.add(builder("slime_bounce", "Walk off a 4-high platform onto a slime floor, bounce, then walk while bouncing")
            .start(0.5D, 4.0D, 0.5D, 0.0F)
            .layout(new Layout()
            {
                public void build(Course c, int ox, int oy, int oz)
                {
                    c.fill(ox - 1, oy + 3, oz - 1, ox + 1, oy + 3, oz + 1, Course.Shape.FULL);
                    c.fill(ox - 3, oy - 1, oz + 2, ox + 3, oy - 1, oz + 20, Course.Shape.SLIME);
                }
            }, -4, -2, -2, 4, 8, 21)
            .hold(14, "W------").hold(50, "-------").hold(30, "W------").build());

        // Starts on a stone pad: standing on slime is not stationary (the idle bounce alternates every tick), so a
        // recording started on slime would depend on the phase it happened to start in.
        list.add(builder("slime_walk_jump", "From a stone pad, walk onto slime, jump on it, sneak-walk on it")
            .layout(new Layout()
            {
                public void build(Course c, int ox, int oy, int oz)
                {
                    c.fill(ox - 2, oy - 1, oz - 2, ox + 2, oy - 1, oz + 1, Course.Shape.FULL);
                    c.fill(ox - 2, oy - 1, oz + 2, ox + 2, oy - 1, oz + 30, Course.Shape.SLIME);
                }
            }, -3, -2, -3, 3, 4, 31)
            .hold(25, "W------").hold(25, "W---J--").hold(15, "W----N-").hold(10, "-------").build());

        // Sneak only once airborne (sneaking on the platform would stop at its edge), then land on slime sneaking.
        list.add(builder("slime_sneak_land", "Walk off a 3-high platform, sneak in the air, land on slime (no bounce), release sneak")
            .start(0.5D, 3.0D, 0.5D, 0.0F)
            .layout(new Layout()
            {
                public void build(Course c, int ox, int oy, int oz)
                {
                    c.fill(ox - 1, oy + 2, oz - 1, ox + 1, oy + 2, oz + 1, Course.Shape.FULL);
                    c.fill(ox - 3, oy - 1, oz + 2, ox + 3, oy - 1, oz + 12, Course.Shape.SLIME);
                }
            }, -4, -2, -2, 4, 7, 13)
            .hold(12, "W------").hold(30, "-----N-").hold(20, "-------").build());

        list.add(builder("web_walk", "Walk into cobwebs, jump inside them, walk out")
            .layout(new Layout()
            {
                public void build(Course c, int ox, int oy, int oz)
                {
                    c.fill(ox - 2, oy - 1, oz - 2, ox + 2, oy - 1, oz + 20, Course.Shape.FULL);
                    c.fill(ox - 1, oy, oz + 3, ox + 1, oy + 1, oz + 5, Course.Shape.WEB);
                }
            }, -3, -2, -3, 3, 4, 21)
            .hold(40, "W------").hold(20, "W---J--").hold(40, "W------").hold(10, "-------").build());

        list.add(builder("fly_basic", "Creative flight: forward, sprint, ascend, descend, land (flight ends), walk")
            .start(0.5D, 2.0D, 0.5D, 0.0F).flying()
            .layout(floor(-3, -3, 3, 60), -4, -2, -4, 4, 12, 61)
            .hold(15, "W------").hold(15, "W-----R").hold(10, "----J--").hold(6, "-----N-").hold(10, "W---J--")
            .hold(30, "-----N-").hold(10, "W------").build());

        return list;
    }

    /**
     * In-game only: scenarios that must make the live engine fall back to vanilla and resume. They use
     * blocks the offline model does not simulate, so they are not part of the offline gates.
     */
    public static List<Scenario> fallbackCorpus()
    {
        List<Scenario> list = new ArrayList<Scenario>();
        list.add(builder("fallback_water_soulsand", "Walk through a flush water pit, then across soul sand, then on stone")
            .layout(new Layout()
            {
                public void build(Course c, int ox, int oy, int oz)
                {
                    c.fill(ox - 3, oy - 1, oz - 2, ox + 3, oy - 1, oz + 40, Course.Shape.FULL);
                    // 1-deep pit at z 8..10, floor at y-2, water flush with the floor, walled on both sides
                    c.fill(ox - 2, oy - 2, oz + 8, ox + 2, oy - 2, oz + 10, Course.Shape.FULL);
                    c.fill(ox - 2, oy - 1, oz + 8, ox + 2, oy - 1, oz + 10, Course.Shape.WATER);
                    // soul sand strip at z 18..19, replacing the floor
                    c.fill(ox - 2, oy - 1, oz + 18, ox + 2, oy - 1, oz + 19, Course.Shape.SOUL_SAND);
                }
            }, -4, -3, -3, 4, 3, 41)
            .hold(110, "W---J--").hold(10, "-------").build()); // jump held: swims up out of the pit
        return list;
    }
}
