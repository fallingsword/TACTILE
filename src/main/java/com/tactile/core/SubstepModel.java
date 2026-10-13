package com.tactile.core;

/**
 * Full-substep player movement operator with step h = 1/N vanilla ticks (physics rate H = 20N Hz).
 *
 * <p><b>Motion.</b> Per axis, one vanilla tick is the affine map v' = f(v + aPre + aPost) with
 * displacement D = v + aPre. Horizontal: aPre = moveFlying acceleration, aPost = 0, f = friction.
 * Vertical: aPre = 0, aPost = -0.08, f = 0.98. A substep uses the exact time-t flow of the linear ODE
 * whose time-1 map is that tick map:
 * <pre>
 *   v* = f(aPre + aPost)/(1 - f),  E = f^t,  S = (1 - E)/(1 - f)
 *   D_t = t(aPre + v*) + (v - v*)S,   v_t = v* + (v - v*)E
 * </pre>
 * With constant input, N substeps compose to exactly one vanilla tick (gate C).
 *
 * <p><b>Input.</b> Every substep reads its own input sample and runs the input-driven state machines in
 * vanilla's order: sprint rules against the previous substep's input, then the jump check, then (after
 * the move) the air acceleration from the current sprint state. Timers (double-tap sprint window:
 * 7 ticks; jump cooldown: 10 ticks) count normalized time.
 *
 * <p><b>Contacts.</b> Two modes:
 * <ul>
 * <li>{@link Contacts#GRID} (live default): collisions follow vanilla's per-tick rules on a grid of
 *     physics ticks, while motion and input stay at substep resolution; see {@link #substepGrid}. Every
 *     position is collision-safe and each grid tick ends exactly where vanilla's move would. A jump
 *     restarts the grid at its real instant. The result does not depend on the substep rate.</li>
 * <li>{@link Contacts#DISCRETE}: vanilla's whole move per substep, with contact state changing at
 *     substep boundaries. Used for the 20 Hz vanilla-identity gates (B, L).</li>
 * </ul>
 * Both are bit-identical to vanilla at N = 1 with tick-aligned input (gate B).
 */
public final class SubstepModel
{
    public enum Contacts { DISCRETE, GRID }

    private static final double GRAVITY = 0.08D;
    private static final double VERTICAL_DRAG = 0.9800000190734863D;
    private static final double EPS = 1.0E-9D;

    public final int stepsPerTick;
    public final double h;
    public final Contacts contacts;

    /** Diagnostics. */
    public int jumpImpulses;
    /** Diagnostics: when the most recent jump happened, in ticks after the start of its game tick. */
    public double lastJumpPhase;
    /** Time since the last game-tick boundary, at the end / start of the current piece (grid phase on (re)initialization). */
    private double tickPhase, pieceStartPhase;
    /** Length of the previous piece (h for uniform substeps): the time the next piece's timers count down. */
    private double prevPieceDt;

    public SubstepModel(int stepsPerTick)
    {
        this(stepsPerTick, Contacts.DISCRETE);
    }

    public SubstepModel(int stepsPerTick, Contacts contacts)
    {
        this(stepsPerTick, contacts, false);
    }

    /**
     * {@code exact}: EXACT input timing semantics (GRID only), so results do not depend on where substep
     * boundaries fall:
     * <ul>
     * <li>The air acceleration follows the sprint state from the instant it changes. In per-substep mode it
     *     lags one substep; in vanilla it lags one tick.</li>
     * <li>The sprint rules also run at grid-tick starts inside a piece, where vanilla runs them each tick, so a
     *     contact committed there (wall hit, landing) acts at that instant, not at the next substep start.</li>
     * </ul>
     * Feed it with {@link #substep(PlayerState, CollisionWorld, TickInput[], double[], int, boolean)} or
     * {@link #tickExact}.
     */
    public SubstepModel(int stepsPerTick, Contacts contacts, boolean exact)
    {
        if (stepsPerTick < 1) throw new IllegalArgumentException("stepsPerTick must be >= 1");
        this.stepsPerTick = stepsPerTick;
        this.h = 1.0D / stepsPerTick;
        this.contacts = contacts;
        this.exact = exact;
        this.prevPieceDt = h;
    }

    public final boolean exact;

    public int rateHz()
    {
        return 20 * stepsPerTick;
    }

    /** One vanilla tick with a single input held for all substeps (vanilla's KeyBinding cadence). */
    public void tick(PlayerState s, TickInput in, CollisionWorld world)
    {
        s.usingItem = in.use;
        for (int k = 0; k < stepsPerTick; ++k) substep(s, world, in, k == 0);
    }

    /**
     * Vanilla tick {@code tickIndex}: each substep takes the timeline input at its own start time, with
     * press edges since the previous substep latched (see {@link InputTimeline#atLatched}).
     */
    public void tick(PlayerState s, InputTimeline timeline, long tickIndex, CollisionWorld world)
    {
        s.usingItem = timeline.at((double)tickIndex).use; // a per-tick state (vanilla changes it at tick starts)
        for (int k = 0; k < stepsPerTick; ++k)
        {
            double tau = substepTau(tickIndex, k);
            double prevTau = (double)(tickIndex * stepsPerTick + k - 1) / (double)stepsPerTick; // same rounding as tau
            substep(s, world, timeline.atLatched(prevTau, tau), k == 0);
        }
    }

    /**
     * EXACT input timing over vanilla tick {@code tickIndex}: each substep takes the timeline input at its start
     * and is split at every timeline sample inside it (see {@link #substep(PlayerState, CollisionWorld, TickInput[], double[], int, boolean)}).
     */
    public void tickExact(PlayerState s, InputTimeline timeline, long tickIndex, CollisionWorld world)
    {
        s.usingItem = timeline.at((double)tickIndex).use; // a per-tick state (vanilla changes it at tick starts)
        for (int k = 0; k < stepsPerTick; ++k)
        {
            double tau = substepTau(tickIndex, k);
            int m = timeline.samplesInside(tau, substepTau(tickIndex, k + 1), scratchTaus);
            fillPieces(m + 1);
            pieceOffsets[0] = 0.0D;
            pieceInputs[0] = timeline.at(tau);
            for (int i = 0; i < m; ++i)
            {
                pieceOffsets[i + 1] = scratchTaus[0][i] - tau;
                pieceInputs[i + 1] = timeline.at(scratchTaus[0][i]);
            }
            substep(s, world, pieceInputs, pieceOffsets, m + 1, k == 0);
        }
    }

    /**
     * STEPPED over vanilla tick {@code tickIndex} (EXACT physics): each substep is split at the step boundaries inside it,
     * and each piece takes the keys in effect under STEPPED ({@link InputTimeline#stepped}). The live engine does the same.
     */
    public void tickStepped(PlayerState s, InputTimeline timeline, long tickIndex, CollisionWorld world, double stepTicks, boolean latch)
    {
        s.usingItem = timeline.at((double)tickIndex).use;
        for (int k = 0; k < stepsPerTick; ++k)
        {
            double tau = substepTau(tickIndex, k);
            int m = InputTimeline.stepsInside(tau, substepTau(tickIndex, k + 1), stepTicks, scratchTaus);
            fillPieces(m + 1);
            pieceOffsets[0] = 0.0D;
            pieceInputs[0] = timeline.stepped(tau, stepTicks, latch);
            for (int i = 0; i < m; ++i)
            {
                pieceOffsets[i + 1] = scratchTaus[0][i] - tau;
                pieceInputs[i + 1] = timeline.stepped(scratchTaus[0][i], stepTicks, latch);
            }
            substep(s, world, pieceInputs, pieceOffsets, m + 1, k == 0);
        }
    }

    private final double[][] scratchTaus = {new double[4]};
    private double[] pieceOffsets = new double[4];
    private TickInput[] pieceInputs = new TickInput[4];

    private void fillPieces(int n)
    {
        if (pieceOffsets.length >= n) return;
        pieceOffsets = new double[2 * n];
        pieceInputs = new TickInput[2 * n];
    }

    /** Start time (in ticks) of substep k of tick n. */
    public double substepTau(long tickIndex, int k)
    {
        return (double)(tickIndex * stepsPerTick + k) / (double)stepsPerTick;
    }

    public void substep(PlayerState s, CollisionWorld world, TickInput in, boolean tickBoundary)
    {
        piece(s, world, in, tickBoundary, h);
    }

    /**
     * EXACT input timing: substep [tau, tau + h) split at every input change inside it. Piece i starts
     * {@code offsets[i]} ticks after the substep start (offsets[0] = 0, increasing, below h) and uses
     * {@code inputs[i]}. Every piece runs the full input logic (sprint rules, jump check) at its own start
     * time and moves with the exact flow for its own duration. A key change therefore acts at its timestamp,
     * whatever the physics rate. The substep rate then only sets how often contacts and the view are evaluated
     * (and yaw, which the caller holds per substep).
     */
    public void substep(PlayerState s, CollisionWorld world, TickInput[] inputs, double[] offsets, int count, boolean tickBoundary)
    {
        substep(s, world, inputs, offsets, count, tickBoundary, h);
    }

    /**
     * As above, but over {@code span} ticks from the substep start (span <= h). A span shorter than h advances a
     * copy to an arbitrary instant, for example the current frame time for rendering. Callers then discard that
     * state and restore the model with {@link #restore}.
     */
    public void substep(PlayerState s, CollisionWorld world, TickInput[] inputs, double[] offsets, int count, boolean tickBoundary, double span)
    {
        substep(s, world, inputs, offsets, count, tickBoundary, span, null);
    }

    /**
     * As above; {@code velocities[i]}, if not null, is a velocity set from outside (a server velocity packet: knockback)
     * at the start of piece i. See {@link #overrideVelocity}.
     */
    public void substep(PlayerState s, CollisionWorld world, TickInput[] inputs, double[] offsets, int count, boolean tickBoundary, double span,
        double[][] velocities)
    {
        boolean boundary = tickBoundary;
        for (int i = 0; i < count; ++i)
        {
            if (velocities != null && velocities[i] != null) overrideVelocity(s, world, inputs[i], velocities[i][0], velocities[i][1], velocities[i][2]);
            double end = i + 1 < count ? offsets[i + 1] : span;
            double dt = end - offsets[i];
            if (dt <= EPS) continue; // superseded at the same instant
            piece(s, world, inputs[i], boundary, dt);
            boundary = false;
        }
    }

    /**
     * A velocity set from outside (S12 velocity packet: knockback from a hit, an arrow, ...). Vanilla sets it between two
     * ticks; the next tick moves with it, with that tick's friction (ground friction if the last move ended on the
     * ground). Treated like a jump: the grid tick in progress ends now (its contacts committed), the velocity is set,
     * and a full grid tick starts at this instant, so the motion that follows is vanilla's, started when it arrived.
     * (Before 0.8.1 the grid tick restarted only up to the next game-tick boundary, which skipped vanilla's ground
     * friction on the first knockback tick: measured 35 % more knockback distance.)
     */
    public void overrideVelocity(PlayerState s, CollisionWorld world, TickInput in, double vx, double vy, double vz)
    {
        boolean grid = contacts == Contacts.GRID && s.gridStartBox != null;
        if (grid && s.gridElapsed > EPS) finalizeGrid(s, world, in);
        s.motionX = vx;
        s.motionY = vy;
        s.motionZ = vz;
        if (grid) beginGrid(s, world, true);
    }

    /** One stretch of {@code dt} ticks with constant input. */
    private void piece(PlayerState s, CollisionWorld world, TickInput in, boolean tickBoundary, double dt)
    {
        if (tickBoundary) tickPhase = 0.0D;
        pieceStartPhase = tickPhase;
        tickPhase += dt;
        // Timers count the time since the previous check (vanilla: one tick since the previous tick start). With
        // uniform substeps that is h; with EXACT pieces it is the previous piece's length, not this one's.
        double elapsed = prevPieceDt;
        prevPieceDt = dt;
        s.yaw = in.yaw;

        // EntityPlayerSP.onLivingUpdate
        if (tickBoundary && s.sprintingTicksLeft > 0)
        {
            --s.sprintingTicksLeft;
            if (s.sprintingTicksLeft == 0) s.setSprinting(false);
        }
        if (s.sprintToggleTimer > 0)
        {
            s.sprintToggleTimer -= elapsed;
            if (s.sprintToggleTimer <= EPS) s.sprintToggleTimer = 0;
        }

        float use = VanillaModel.itemUseFactor(s); // s.usingItem is set at tick starts by the driver (vanilla: click handling)
        float moveForward = in.moveForward() * use;
        float moveStrafe = in.moveStrafe() * use;
        boolean pushEdge = (in.jump && !s.prevJump) || (in.sneak && !s.prevSneak); // a new fly push (flying)
        VanillaModel.updateSprint(s, in, moveForward, s.prevSneak, s.prevForwardHigh);
        s.prevJump = in.jump;
        s.prevSneak = in.sneak;
        s.prevForwardHigh = moveForward >= 0.8F;
        if (exact) s.jumpMovementFactor = VanillaModel.airAcceleration(s.sprinting);

        if (contacts == Contacts.GRID)
        {
            substepGrid(s, world, in, moveForward, moveStrafe, dt, elapsed, pushEdge);
            s.jumpMovementFactor = VanillaModel.airAcceleration(s.sprinting);
            return;
        }

        // EntityLivingBase.onLivingUpdate
        if (tickBoundary) VanillaModel.applyMotionCutoff(s);

        if (in.jump)
        {
            s.jumpTicks = Math.max(0.0D, s.jumpTicks - elapsed);
            if (s.jumpTicks <= EPS) s.jumpTicks = 0.0D;
            if (s.onGround && s.jumpTicks == 0.0D)
            {
                VanillaModel.jump(s);
                s.jumpTicks = 10.0D;
                ++jumpImpulses;
                lastJumpPhase = pieceStartPhase;
            }
        }
        else
        {
            s.jumpTicks = 0.0D;
        }

        moveSegment(s, world, in.sneak, moveStrafe * 0.98F, moveForward * 0.98F, dt, s.onGround);

        // EntityPlayer.onLivingUpdate, after the move: air acceleration for the next step.
        s.jumpMovementFactor = VanillaModel.airAcceleration(s.sprinting);
    }

    // ------------------------------------------------------------------ GRID contacts

    /**
     * GRID mode: motion is integrated exactly at substep resolution (inputs, yaw, acceleration, friction,
     * gravity), but collisions use vanilla's own per-tick rules. Every substep, the player's box is
     * vanilla's collider (Y, then X, then Z; sneak guard; step-up) applied to the free displacement
     * accumulated since the start of the current grid tick, from that tick's start box. So every position
     * is collision-safe, and at the end of each grid tick the result is exactly vanilla's move. Contact
     * state (onGround, collision flags, clipped-axis velocity responses) is committed at grid-tick ends,
     * as vanilla commits it at tick ends. A jump restarts the grid at its real instant, so every jump arc
     * is vanilla's arc whenever it starts. Collisions do not depend on how time is split into substeps,
     * so all rates give the same result for the same input timeline.
     */
    private void substepGrid(PlayerState s, CollisionWorld world, TickInput in, float moveForward, float moveStrafe, double span, double elapsed,
        boolean pushEdge)
    {
        if (s.gridStartBox == null)
        {
            // (Re)initialized mid-tick (activation, teleport, server correction): the first grid tick is partial
            // and ends at the next game-tick boundary, so the grid keeps the game's tick phase. Only a jump
            // deliberately re-phases it.
            beginGrid(s, world, true);
            s.gridElapsed = Math.min(pieceStartPhase, 1.0D - span);
        }
        applyPendingCutoff(s, in);
        float strafe = moveStrafe * 0.98F;
        float forward = moveForward * 0.98F;

        // Input-driven jump at the substep start. Vanilla allows a jump only at tick starts, with onGround from
        // the previous move. A press inside a grid tick therefore needs both: grounded when this grid tick began
        // (vanilla would have allowed it then) AND still grounded at the current position. The first alone gave
        // up to a tick of extra coyote time after sliding off an edge; the second alone let a fresh press fire at
        // the instant of touchdown, faster than vanilla's jump cadence.
        // In water or lava, jump is a swim push (part of the vertical flow below), not a jump impulse.
        if (in.jump)
        {
            s.jumpTicks = Math.max(0.0D, s.jumpTicks - elapsed);
            if (s.jumpTicks <= EPS) s.jumpTicks = 0.0D;
            boolean groundedNow = s.gridElapsed <= EPS || gridResult == null || pendingOnGround;
            if (landLike(s) && s.onGround && groundedNow && s.jumpTicks == 0.0D)
            {
                if (s.gridElapsed > EPS) restartGridNow(s, world, in, moveForward);
                if (landLike(s)) jumpNow(s, world, 10.0D);
            }
        }
        else
        {
            s.jumpTicks = 0.0D;
        }

        // Flying: a new ascend/descend press starts a grid tick at its instant, as a jump does, so the fly push
        // (applied at a tick start in vanilla) acts from the press.
        if (s.gridMedium == PlayerState.FLY && pushEdge && s.gridElapsed > EPS) restartGridNow(s, world, in, moveForward);

        double remaining = span;
        while (remaining > EPS)
        {
            applyPendingCutoff(s, in);
            applyLadderClamp(s, strafe, forward, in.sneak);
            double dt = Math.min(remaining, 1.0D - s.gridElapsed);
            Flow f = gridFlow(s, world, strafe, forward, in.jump, dt);
            if (s.gridElapsed <= EPS)
            {
                // first segment of a grid tick: remember its flow and start motion (see wholeTickExactly)
                gridFirstFlow = f;
                gridUniform = true;
                gridStartMX = s.motionX;
                gridStartMY = s.motionY;
                gridStartMZ = s.motionZ;
            }
            else if (gridFirstFlow == null || !gridFirstFlow.sameParams(f)) gridUniform = false;
            s.accX += f.dx(s);
            s.accY += f.dy(s);
            s.accZ += f.dz(s);
            // the motion just after a whole-tick move, before vanilla's post-move callbacks and drag (exact at N = 1)
            lastSegWhole = s.gridElapsed <= EPS && dt >= 1.0D - EPS;
            lastUx = f.ux(s);
            lastUy = f.uy(s);
            lastUz = f.uz(s);
            f.finishFree(s);
            s.gridElapsed += dt;
            remaining -= dt;
            place(s, world, in, strafe, forward);
            if (s.gridElapsed >= 1.0D - EPS)
            {
                if (gridUniform && gridFirstFlow != null) wholeTickExactly(s, world, in, strafe, forward);
                finalizeGrid(s, world, in);
                beginGrid(s, world, true);
                // Held jump at a grid-tick start inside this substep (vanilla checks jump at every tick start).
                // A grid start exactly at the substep end is left to the next substep's own jump check, which
                // runs after that substep's input and yaw update (as vanilla's jump follows its tick's input update).
                if (remaining > EPS)
                {
                    applyPendingCutoff(s, in);
                    if (exact)
                    {
                        // vanilla's per-tick sprint rules against the freshly committed contacts (no new input edge here)
                        VanillaModel.updateSprint(s, in, moveForward, s.prevSneak, s.prevForwardHigh);
                        s.jumpMovementFactor = VanillaModel.airAcceleration(s.sprinting);
                    }
                    double into = span - remaining;
                    if (in.jump && landLike(s) && s.onGround && s.jumpTicks - into <= EPS) jumpNow(s, world, 10.0D + into);
                }
            }
        }
    }

    private Collider.Result gridResult;
    private boolean pendingOnGround, pendingCollidedH, pendingCollidedV;

    private boolean cutoffPending;
    /** The grid tick started on a ladder: its clamps apply when it starts moving (after any jump impulse). */
    private boolean ladderPending;
    /** Pre-drag motion of the last flow segment, and whether that segment was a whole grid tick. */
    private double lastUx, lastUy, lastUz;
    private boolean lastSegWhole;
    /** The grid tick's first flow and start motion, and whether every later segment used the same flow. */
    private Flow gridFirstFlow;
    private boolean gridUniform;
    private double gridStartMX, gridStartMY, gridStartMZ;

    /**
     * A grid tick run with one flow throughout (constant input and state, the usual case) ends exactly where a single
     * whole-tick step would: vanilla's move, bit for bit. Summing its segments instead can leave rounding residue,
     * and vanilla distinguishes an exactly-zero move (no collision, off the ground) from a tiny one (touching the
     * floor), e.g. standing on slime, where the idle bounce leaves the vertical speed exactly 0 at some tick starts.
     */
    private void wholeTickExactly(PlayerState s, CollisionWorld world, TickInput in, float strafe, float forward)
    {
        PlayerState t = new PlayerState();
        t.motionX = gridStartMX;
        t.motionY = gridStartMY;
        t.motionZ = gridStartMZ;
        Flow f1 = gridFirstFlow.at(1.0D);
        s.accX = f1.dx(t);
        s.accY = f1.dy(t);
        s.accZ = f1.dz(t);
        lastUx = f1.ux(t);
        lastUy = f1.uy(t);
        lastUz = f1.uz(t);
        lastSegWhole = true;
        f1.finishFree(t);
        s.motionX = t.motionX;
        s.motionY = t.motionY;
        s.motionZ = t.motionZ;
        place(s, world, in, strafe, forward);
    }

    /** Land rules (ground jump, ladder): on foot or flying. */
    private static boolean landLike(PlayerState s)
    {
        return s.gridMedium == PlayerState.LAND || s.gridMedium == PlayerState.FLY;
    }

    /** The model's own state between substeps (everything not in PlayerState), for replaying from a saved point. */
    public static final class Saved
    {
        private double tickPhase, pieceStartPhase, prevPieceDt, lastUx, lastUy, lastUz, gridStartMX, gridStartMY, gridStartMZ;
        private Collider.Result gridResult;
        private boolean pendingOnGround, pendingCollidedH, pendingCollidedV, cutoffPending, ladderPending, lastSegWhole, gridUniform;
        private Flow gridFirstFlow;
    }

    public Saved save()
    {
        Saved v = new Saved();
        v.tickPhase = tickPhase;
        v.pieceStartPhase = pieceStartPhase;
        v.prevPieceDt = prevPieceDt;
        v.gridResult = gridResult; // never mutated after creation
        v.pendingOnGround = pendingOnGround;
        v.pendingCollidedH = pendingCollidedH;
        v.pendingCollidedV = pendingCollidedV;
        v.cutoffPending = cutoffPending;
        v.ladderPending = ladderPending;
        v.lastUx = lastUx;
        v.lastUy = lastUy;
        v.lastUz = lastUz;
        v.lastSegWhole = lastSegWhole;
        v.gridFirstFlow = gridFirstFlow; // never mutated after construction
        v.gridUniform = gridUniform;
        v.gridStartMX = gridStartMX;
        v.gridStartMY = gridStartMY;
        v.gridStartMZ = gridStartMZ;
        return v;
    }

    public void restore(Saved v)
    {
        tickPhase = v.tickPhase;
        pieceStartPhase = v.pieceStartPhase;
        prevPieceDt = v.prevPieceDt;
        gridResult = v.gridResult;
        pendingOnGround = v.pendingOnGround;
        pendingCollidedH = v.pendingCollidedH;
        pendingCollidedV = v.pendingCollidedV;
        cutoffPending = v.cutoffPending;
        ladderPending = v.ladderPending;
        lastUx = v.lastUx;
        lastUy = v.lastUy;
        lastUz = v.lastUz;
        lastSegWhole = v.lastSegWhole;
        gridFirstFlow = v.gridFirstFlow;
        gridUniform = v.gridUniform;
        gridStartMX = v.gridStartMX;
        gridStartMY = v.gridStartMY;
        gridStartMZ = v.gridStartMZ;
    }

    /**
     * Starts a grid tick at the current state. Vanilla's per-tick motion cutoff belongs to the start of the
     * next tick's update, not the end of the previous one, so it stays pending until this grid tick
     * actually starts moving. The stored motion at a tick boundary is then vanilla's.
     */
    private void beginGrid(PlayerState s, CollisionWorld world, boolean cutoff)
    {
        cutoffPending = cutoff;
        s.gridStartBox = s.box;
        s.gridStartOnGround = s.onGround;
        s.gridElapsed = 0.0D;
        s.accX = s.accY = s.accZ = 0.0D;
        s.gridGround = s.onGround;
        s.viewDX = s.viewDY = s.viewDZ = 0.0D;
        s.gridFriction = s.onGround ? frictionBelow(s, world) : 0.91F;
        gridResult = null;
        gridFirstFlow = null;
        gridUniform = false;

        // The medium for this grid tick, decided where vanilla decides it: at the tick start, from the start box.
        double[] push = world.waterPush(s.box.waterTestBox());
        s.inWater = push != null;
        boolean lava = push == null && world.lavaIn(s.box.lavaTestBox());
        boolean pushed = push != null && !s.flying; // EntityPlayer.isPushedByWater: not while flying
        s.gridPushX = pushed ? push[0] : 0.0D;
        s.gridPushY = pushed ? push[1] : 0.0D;
        s.gridPushZ = pushed ? push[2] : 0.0D;
        // Flying runs the land branch (EntityPlayer's override) whatever the player is in.
        s.gridMedium = s.flying ? PlayerState.FLY : push != null ? PlayerState.WATER : lava ? PlayerState.LAVA : PlayerState.LAND;
        s.gridSwim = s.inWater || lava; // jump in water or lava pushes up (+0.04) even while flying
        s.gridLadder = s.gridMedium == PlayerState.LAND && world.onLadder(s.box);
        s.gridClampX = s.gridClampZ = false;
        ladderPending = s.gridLadder;
        s.gridWeb = false; // set when the tick starts moving (applyPendingCutoff)
    }

    /**
     * Tick-start motion changes, applied when the grid tick starts moving: the water current or the fly push
     * (vanilla adds both before the 0.005 cutoff), then the cutoff.
     */
    private void applyPendingCutoff(PlayerState s, TickInput in)
    {
        if (!cutoffPending) return;
        cutoffPending = false;
        if (s.gridMedium == PlayerState.WATER)
        {
            s.motionX += s.gridPushX;
            s.motionY += s.gridPushY;
            s.motionZ += s.gridPushZ;
        }
        if (s.gridMedium == PlayerState.FLY) VanillaModel.flightPush(s, in);
        VanillaModel.applyMotionCutoff(s);
        // A cobweb touched by the previous move scales this move: Entity.isInWeb, consumed by the next moveEntity
        // (so at a tick boundary the flag is still set, as in vanilla's state).
        s.gridWeb = s.inWeb;
        s.inWeb = false;
    }

    /**
     * Vanilla's ladder rules at the move: (motion + acceleration) clamped to +-0.15 horizontally, a fall speed of
     * at most 0.15, and no descent while sneaking. On a clamped axis the grid tick moves exactly the clamped speed.
     */
    private void applyLadderClamp(PlayerState s, float strafe, float forward, boolean sneak)
    {
        if (!ladderPending) return;
        ladderPending = false;
        float[] a = VanillaModel.flyingDelta(s.yaw, strafe, forward, landAcceleration(s));
        double ux = s.motionX + (double)a[0], uz = s.motionZ + (double)a[1];
        double cx = VanillaModel.clamp(ux, -VanillaModel.LADDER_SPEED, VanillaModel.LADDER_SPEED);
        double cz = VanillaModel.clamp(uz, -VanillaModel.LADDER_SPEED, VanillaModel.LADDER_SPEED);
        if (cx != ux)
        {
            s.motionX = cx;
            s.gridClampX = true;
        }
        if (cz != uz)
        {
            s.motionZ = cz;
            s.gridClampZ = true;
        }
        if (s.motionY < -VanillaModel.LADDER_FALL) s.motionY = -VanillaModel.LADDER_FALL;
        if (sneak && s.motionY < 0.0D) s.motionY = 0.0D;
    }

    private static float landAcceleration(PlayerState s)
    {
        float f4 = s.gridFriction;
        if (s.gridGround) return s.aiMoveSpeed() * (0.16277136F / (f4 * f4 * f4));
        return s.gridMedium == PlayerState.FLY ? VanillaModel.flyAcceleration(s) : s.jumpMovementFactor;
    }

    /**
     * Jump impulse now (vanilla's jump at a tick start); the grid has just begun at this instant. {@code cooldown} is 10
     * plus the time into the current piece at which the jump happens.
     */
    private void jumpNow(PlayerState s, CollisionWorld world, double cooldown)
    {
        jumpNowImpl(s, world, cooldown);
    }

    /**
     * Ends the grid tick in progress at this instant (a jump or a fly push re-phases the grid) and starts a new one, in
     * vanilla's order for a tick start: the contacts of the move just ended are committed, then (EXACT) the sprint
     * rules run against them, then the caller's jump or push. Without the sprint step, a wall hit committed here ended
     * sprint only at the next piece start, whose time depends on the physics rate: a mid-tick jump into a wall kept the
     * sprint-jump boost for a rate-dependent moment (measured: up to 2.4 mm of sideways position, RESULTS Phase 21).
     */
    private void restartGridNow(PlayerState s, CollisionWorld world, TickInput in, float moveForward)
    {
        finalizeGrid(s, world, in);
        beginGrid(s, world, true);
        applyPendingCutoff(s, in);
        if (exact)
        {
            VanillaModel.updateSprint(s, in, moveForward, s.prevSneak, s.prevForwardHigh);
            s.jumpMovementFactor = VanillaModel.airAcceleration(s.sprinting);
        }
    }

    private void jumpNowImpl(PlayerState s, CollisionWorld world, double cooldown)
    {
        VanillaModel.jump(s);
        s.jumpTicks = cooldown;
        ++jumpImpulses;
        lastJumpPhase = pieceStartPhase + (cooldown - 10.0D);
    }

    /** The flow of the current grid tick's medium (land with friction, water, lava) for the given input. */
    private Flow gridFlow(PlayerState s, CollisionWorld world, float strafe, float forward, boolean jump, double dt)
    {
        if (landLike(s))
        {
            float[] a = VanillaModel.flyingDelta(s.yaw, strafe, forward, landAcceleration(s));
            double ax = s.gridClampX ? 0.0D : (double)a[0], az = s.gridClampZ ? 0.0D : (double)a[1];
            if (s.gridWeb) return Flow.web(dt, ax, az, 0.0D);
            if (s.gridMedium == PlayerState.FLY) // the move uses the pushed speed; the next speed is 0.6 x that speed
                return Flow.liquid(dt, (double)s.gridFriction, ax, az, VanillaModel.FLY_DRAG, jump && s.gridSwim ? VanillaModel.LIQUID_JUMP : 0.0D, 0.0D);
            return new Flow(dt, (double)s.gridFriction, ax, az);
        }
        boolean water = s.gridMedium == PlayerState.WATER;
        float[] fa = water ? VanillaModel.waterFrictionAndAccel(s) : new float[] {0.5F, 0.02F};
        float[] a = VanillaModel.flyingDelta(s.yaw, strafe, forward, fa[1]);
        double j = jump ? VanillaModel.LIQUID_JUMP : 0.0D;
        if (s.gridWeb) return Flow.web(dt, (double)a[0], (double)a[1], j);
        return Flow.liquid(dt, water ? (double)fa[0] : VanillaModel.LAVA_DRAG, (double)a[0], (double)a[1],
            water ? VanillaModel.WATER_DRAG : VanillaModel.LAVA_DRAG, j, VanillaModel.LIQUID_GRAVITY);
    }

    /** Box = vanilla's collider applied to the accumulated free displacement from the grid-tick start box. */
    private void place(PlayerState s, CollisionWorld world, TickInput in, float strafe, float forward)
    {
        boolean sneak = in.sneak;
        PlayerState tmp = new PlayerState();
        tmp.box = s.gridStartBox;
        tmp.onGround = s.gridStartOnGround;
        gridResult = Collider.move(tmp, world, s.accX, s.accY, s.accZ, sneak);
        s.box = tmp.box;
        pendingOnGround = tmp.onGround;
        pendingCollidedH = tmp.collidedHorizontally;
        pendingCollidedV = tmp.collidedVertically;
        updateView(s, world, in, strafe, forward);
    }

    /**
     * View offset (rendering only). Vanilla's collider on a partial displacement is not monotone in it: the sneak
     * guard trims in 0.05 steps (a sawtooth while pushing into an edge), and Y-first clipping holds the box at a
     * face it will clear by the tick's end (a stall, then a jump forward). Vanilla never shows either, because it
     * renders a straight interpolation between tick results. The view follows the free path, but on each axis
     * that the tick's predicted end result limits, it never passes that end. The prediction holds the current
     * input to the end of the grid tick, so it is exact unless the input changes. At grid ends the view equals
     * the box.
     */
    private void updateView(PlayerState s, CollisionWorld world, TickInput in, float strafe, float forward)
    {
        boolean sneak = in.sneak;
        s.viewDX = s.viewDY = s.viewDZ = 0.0D;
        if (s.gridElapsed >= 1.0D - EPS) return;
        Flow f = gridFlow(s, world, strafe, forward, in.jump, 1.0D - s.gridElapsed);
        double px = s.accX + f.dx(s), py = s.accY + f.dy(s), pz = s.accZ + f.dz(s);
        PlayerState end = new PlayerState();
        end.box = s.gridStartBox;
        end.onGround = s.gridStartOnGround;
        Collider.move(end, world, px, py, pz, sneak);
        Box b0 = s.gridStartBox;
        double e = s.gridElapsed;
        s.viewDX = b0.minX + viewAxis(s.accX, px, end.box.minX - b0.minX, e) - s.box.minX;
        // On a ladder vanilla resets the vertical speed every tick (fall clamp, sneak hold, climb), so the smooth in-tick
        // curve of the vertical flow would rise and fall within each tick (a 20 Hz vibration while holding still or
        // sliding). The same happens standing on slime, where the idle bounce gives every other tick an upward speed
        // that is used up by the tick's end. There the view follows vanilla's straight line from the tick's start to
        // its predicted end. (A tick that starts in the air and ends level, like a jump's apex, keeps its curve.)
        boolean level = s.gridStartOnGround && Math.abs(end.box.minY - b0.minY) < 1e-9;
        s.viewDY = b0.minY + (s.gridLadder || level ? (end.box.minY - b0.minY) * e : viewAxis(s.accY, py, end.box.minY - b0.minY, e)) - s.box.minY;
        s.viewDZ = b0.minZ + viewAxis(s.accZ, pz, end.box.minZ - b0.minZ, e) - s.box.minZ;
    }

    private static double viewAxis(double acc, double predicted, double end, double elapsed)
    {
        if (Math.abs(end - predicted) < 1e-9) return acc; // not limited this tick: the free path, as vanilla interpolates
        if (end == 0.0D) return 0.0D;
        if (acc * end > 0.0D) return end > 0.0D ? Math.min(acc, end) : Math.max(acc, end);
        return end * elapsed; // limited the other way (step-up, bounce): straight interpolation like vanilla
    }

    /**
     * Commits the grid tick's contact state and vanilla's end-of-tick velocity responses for its medium: clipped
     * axes, the ladder climb (pushing into a wall while on a ladder), the jump out of water or lava.
     */
    private void finalizeGrid(PlayerState s, CollisionWorld world, TickInput in)
    {
        if (gridResult == null) return;
        int medium = s.gridMedium;
        boolean liquid = medium == PlayerState.WATER || medium == PlayerState.LAVA;
        // Post-move horizontal drag, from the tick-start state (before the contacts are committed).
        double fh = medium == PlayerState.WATER ? (double)VanillaModel.waterFrictionAndAccel(s)[0]
            : medium == PlayerState.LAVA ? VanillaModel.LAVA_DRAG : (double)s.gridFriction;
        double dv = medium == PlayerState.WATER ? VanillaModel.WATER_DRAG : VanillaModel.LAVA_DRAG;
        boolean guard = s.gridStartOnGround && in.sneak; // moveEntity's sneak flag (before the move)

        s.onGround = pendingOnGround;
        s.collidedHorizontally = pendingCollidedH;
        s.collidedVertically = pendingCollidedV;

        // The motion just after the move, before moveEntity's callbacks and the drag: exact for a whole-tick segment,
        // otherwise the free end motion with the drag undone. A cobweb move zeroes it.
        double ux, uy, uz;
        if (s.gridWeb) ux = uy = uz = 0.0D;
        else if (lastSegWhole)
        {
            ux = lastUx;
            uy = lastUy;
            uz = lastUz;
        }
        else
        {
            ux = s.motionX / fh;
            uz = s.motionZ / fh;
            uy = medium == PlayerState.LAND ? s.motionY / VERTICAL_DRAG + GRAVITY : liquid ? (s.motionY + VanillaModel.LIQUID_GRAVITY) / dv : s.motionY;
        }
        // Axes a response touches are recomputed through vanilla's drag; the rest keep the flow's exact result.
        boolean tx = s.gridWeb, ty = s.gridWeb, tz = s.gridWeb;

        // Entity.moveEntity: clipped axes stop; onLanded on the block under the feet (slime bounces unless sneaking)
        if (gridResult.clippedX)
        {
            ux = 0.0D;
            tx = true;
        }
        if (gridResult.clippedZ)
        {
            uz = 0.0D;
            tz = true;
        }
        int landing = world.landingBlock(s.posX(), s.posY(), s.posZ());
        if (gridResult.clippedY)
        {
            if (landing == CollisionWorld.LANDING_SLIME && !in.sneak)
            {
                if (uy < 0.0D)
                {
                    uy = -uy;
                    ty = true;
                }
            }
            else
            {
                uy = 0.0D;
                ty = true;
            }
        }
        // the walking callback (slime slows walking), then doBlockCollisions (soul sand, cobweb)
        if (!s.flying && !guard && s.onGround && landing == CollisionWorld.LANDING_SLIME && Math.abs(uy) < 0.1D && !in.sneak)
        {
            double d0 = 0.4D + Math.abs(uy) * 0.2D;
            ux *= d0;
            uz *= d0;
            tx = tz = true;
        }
        int[] bc = world.blockCollisions(s.box);
        for (int i = 0; i < bc[0]; ++i)
        {
            ux *= VanillaModel.SOUL_SAND;
            uz *= VanillaModel.SOUL_SAND;
            tx = tz = true;
        }
        if (bc[1] != 0 && !s.flying) s.inWeb = true;
        // moveEntityWithHeading: the ladder climb (land)
        if (medium == PlayerState.LAND && s.collidedHorizontally && world.onLadder(s.box))
        {
            uy = VanillaModel.LADDER_CLIMB;
            ty = true;
        }

        if (tx) s.motionX = ux * fh;
        if (tz) s.motionZ = uz * fh;
        // Flying keeps 0.6 x the speed it moved with, whatever the move did (EntityPlayer: motionY = d3 * 0.6).
        if (ty && medium != PlayerState.FLY) s.motionY = medium == PlayerState.LAND ? (uy - GRAVITY) * VERTICAL_DRAG : uy * dv - VanillaModel.LIQUID_GRAVITY;
        if (liquid && s.collidedHorizontally && VanillaModel.offsetPositionInLiquid(s, world, s.motionX,
            s.motionY + 0.6000000238418579D - s.posY() + s.gridStartBox.minY, s.motionZ))
            s.motionY = VanillaModel.LIQUID_EXIT;
        if (s.onGround && s.flying) s.flying = false; // EntityPlayerSP: landing ends flight
        gridResult = null;
    }

    // ------------------------------------------------------------------ flow

    /** Flow coefficients for duration t, written in vanilla's operation shape (bit-exact at t = 1). */
    private static final class Flow
    {
        double eh, sh, ah, kh, ev, sv, bv, kv, ax, az;
        /** Liquid vertical form: v' = (v + vj) * d - vc per tick (vanilla's order), vj = swim push, vc = gravity. */
        boolean liquid;
        double vj, vc, ahv;

        /** The parameters that define the flow (kind 0 land, 1 liquid, 2 web), so it can be rebuilt for another duration. */
        int kind;
        double pfh, pd, pj, pc;

        /** The same flow over {@code t}. */
        Flow at(double t)
        {
            return kind == 0 ? new Flow(t, pfh, ax, az) : kind == 1 ? liquid(t, pfh, ax, az, pd, pj, pc) : web(t, ax, az, pj);
        }

        boolean sameParams(Flow o)
        {
            return kind == o.kind && pfh == o.pfh && ax == o.ax && az == o.az && pd == o.pd && pj == o.pj && pc == o.pc;
        }

        /** Land: horizontal v' = (v + a) * fh, D = v + a; vertical v' = (v - 0.08) * 0.98, D = v. */
        Flow(double t, double fh, double ax, double az)
        {
            kind = 0;
            pfh = fh;
            horizontal(t, fh, ax, az);
            ev = Math.pow(VERTICAL_DRAG, t);
            sv = (1.0D - ev) / (1.0D - VERTICAL_DRAG);
            bv = (VERTICAL_DRAG * sv) / ev;
            kv = -GRAVITY * VERTICAL_DRAG * (t - sv) / (1.0D - VERTICAL_DRAG);
        }

        private Flow()
        {
        }

        /**
         * Water or lava: horizontal as on land with friction fh; vertical v' = (v + j) * d - c, D = v + j. The exact
         * flow of that map, in an operation shape that at t = 1 is vanilla's: dy = (v + j*1)*1 + 0, v' = (v + j*1)*d - c*1.
         */
        static Flow liquid(double t, double fh, double ax, double az, double d, double j, double c)
        {
            Flow f = new Flow();
            f.kind = 1;
            f.pfh = fh;
            f.pd = d;
            f.pj = j;
            f.pc = c;
            f.horizontal(t, fh, ax, az);
            f.liquid = true;
            f.vj = j;
            f.vc = c;
            f.ev = Math.pow(d, t);
            f.sv = (1.0D - f.ev) / (1.0D - d);
            f.ahv = (d * f.sv) / f.ev;
            f.kv = j * (t - f.ahv * f.sv) + (d * j - c) * (t - f.sv) / (1.0D - d);
            return f;
        }

        /** Cobweb move: the displacement is the moving speed scaled (0.25 horizontal, 0.05 vertical), at a constant rate. */
        boolean web;
        double wt;

        static Flow web(double t, double ax, double az, double j)
        {
            Flow f = new Flow();
            f.kind = 2;
            f.pj = j;
            f.web = true;
            f.wt = t;
            f.ax = ax;
            f.az = az;
            f.vj = j;
            return f;
        }

        /** Motion after the move, before moveEntity's callbacks and the drag (vanilla's motion at the move). */
        double ux(PlayerState s)
        {
            return web ? 0.0D : s.motionX + ax * ah;
        }

        double uz(PlayerState s)
        {
            return web ? 0.0D : s.motionZ + az * ah;
        }

        double uy(PlayerState s)
        {
            if (web) return 0.0D;
            return liquid ? s.motionY + vj * ahv : s.motionY;
        }

        private void horizontal(double t, double fh, double ax, double az)
        {
            this.ax = ax;
            this.az = az;
            eh = Math.pow(fh, t);
            sh = (1.0D - eh) / (1.0D - fh);
            ah = (fh * sh) / eh;
            kh = t + fh * (t - sh) / (1.0D - fh) - ah * sh;
        }

        double dx(PlayerState s)
        {
            if (web) return (s.motionX + ax) * VanillaModel.WEB_H * wt;
            return (s.motionX + ax * ah) * sh + ax * kh;
        }

        double dz(PlayerState s)
        {
            if (web) return (s.motionZ + az) * VanillaModel.WEB_H * wt;
            return (s.motionZ + az * ah) * sh + az * kh;
        }

        double dy(PlayerState s)
        {
            if (web) return (s.motionY + vj) * VanillaModel.WEB_V * wt;
            return liquid ? (s.motionY + vj * ahv) * sv + kv : s.motionY * sv + kv;
        }

        /** End-of-segment velocities with no collision response (GRID mode applies responses at grid-tick ends). */
        void finishFree(PlayerState s)
        {
            if (web) return; // the motion stays until the grid-tick end zeroes it (vanilla zeroes it at the move)
            double ux = s.motionX + ax * ah, uz = s.motionZ + az * ah, uy = s.motionY;
            s.motionX = ux * eh;
            s.motionZ = uz * eh;
            s.motionY = liquid ? (uy + vj * ahv) * ev - vc * sv : (uy - GRAVITY * bv) * ev;
        }

        /** Applies end-of-segment velocities, then the clipped-axis responses. */
        void finish(PlayerState s, Collider.Result r)
        {
            double ux = s.motionX + ax * ah, uz = s.motionZ + az * ah, uy = s.motionY;
            s.motionX = ux * eh;
            s.motionZ = uz * eh;
            s.motionY = (uy - GRAVITY * bv) * ev;
            if (r.clippedX) s.motionX = 0.0D;
            if (r.clippedZ) s.motionZ = 0.0D;
            if (r.clippedY) s.motionY = (0.0D - GRAVITY) * VERTICAL_DRAG;
        }
    }

    /** Friction and the moveFlying acceleration for the given contact state. */
    private Flow flowFor(PlayerState s, CollisionWorld world, float strafe, float forward, double t, boolean ground)
    {
        float f4 = ground ? frictionBelow(s, world) : 0.91F;
        float accel = ground ? s.aiMoveSpeed() * (0.16277136F / (f4 * f4 * f4)) : s.jumpMovementFactor;
        float[] a = VanillaModel.flyingDelta(s.yaw, strafe, forward, accel);
        return new Flow(t, (double)f4, (double)a[0], (double)a[1]);
    }

    /** One segment of duration t with the contact state fixed at its start. (DISCRETE mode: t = h.) */
    private Collider.Result moveSegment(PlayerState s, CollisionWorld world, boolean sneak, float strafe, float forward, double t, boolean ground)
    {
        Flow f = flowFor(s, world, strafe, forward, t, ground);
        Collider.Result r = Collider.move(s, world, f.dx(s), f.dy(s), f.dz(s), sneak);
        f.finish(s, r);
        return r;
    }

    private static float frictionBelow(PlayerState s, CollisionWorld world)
    {
        int bx = McMath.floor(s.posX());
        int by = McMath.floor(s.box.minY) - 1;
        int bz = McMath.floor(s.posZ());
        return world.slipperiness(bx, by, bz) * 0.91F;
    }
}
