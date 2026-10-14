# Development log

This is the chronological record of TACTILE's development: each phase's tests, measurements, problems found and
fixes, in the order they happened, from the first prototype to 1.0.0. Earlier phases describe versions and mode names
that no longer exist (for example HIGH_RES, now STEPPED), and some of their numbers were superseded by later phases.

**For the results of the released version, see [VERIFICATION.md](VERIFICATION.md).** It was produced in a single test
session on the 1.0.0 build and is the reference for how TACTILE behaves today. This log is kept for transparency: it
shows how each result was reached.

Everything below was produced by commands that were actually run. The first recording, for example, was made with:

```powershell
# 1. Record the vanilla baseline (opens the client, runs 14 scripted scenarios, exits; don't touch input)
$env:HRMOVE_BASELINE='1'; .\gradlew.bat runClient --offline
#    optional: $env:HRMOVE_BASELINE_ONLY='sneak_edge,walk_stop'
# 2. Offline gates and rate report (no game needed)
.\gradlew.bat movementTests --offline
#    per-tick diagnostic: $env:HRMOVE_DUMP='walk_off_ledge:12'   (scenario:substepsPerTick)
```

Environment: Forge 1.8.9-11.15.1.2318, MCP stable_20, Temurin JDK 1.8.0_504, Windows 11, dev runtime (deobfuscated).

Naming: since 0.6.1 the code lives in `com.tactile` (was `com.example.hrmove`), and the classes this record
cites by their old names are HRMove → Tactile, HRConfig → TactileConfig, HRHooks → TactileHooks,
HRTransformer → TactileTransformer, HRLoadingPlugin → TactileLoadingPlugin, HRGuiFactory → TactileGuiFactory,
HRMovementInput → TactileMovementInput. Environment variables (`HRMOVE_*`) and the recordings under `run/hrm-*`
keep their names so the commands and paths below stay valid. Release jars of earlier editions are in
`archive/releases/`.

## Phase 1: Vanilla baseline (2026-10-10)

14 canonical scenarios, 765 ticks, recorded from the real client with no movement hook installed
(`run/hrm-baseline/*.csv`). 0 contaminated rows.
1 external state change: an S12 velocity packet after fall damage in `walk_off_ledge` (see SOURCE_AUDIT §6).

## Phase 2: Isolated operator gates (all pass)

| Gate | Check | Result |
|---|---|---|
| A | `VanillaModel` (20 Hz port) vs recorded client, one-step and free-run | **765/765 ticks bit-exact**, free-run never diverges |
| B | `SubstepModel` at 1 step/tick vs `VanillaModel` | 765/765 bit-identical (both ground policies) |
| C | Contact-free composition: N substeps vs one vanilla tick | max rel. error 1e-15 (40 Hz) … 3e-14 (480 Hz) |
| D | Jump impulses per activation | tap = exactly 1 at every rate; held-jump counts within ±1 of vanilla |

## Rate comparison (PER_SUBSTEP policy, max position divergence from vanilla at tick boundaries, blocks)

| scenario | 40 Hz | 60 Hz | 120 Hz | 240 Hz | cause |
|---|---|---|---|---|---|
| stand, walk_stop, sprint_stop, diag_strafe, wall_scrape, slab_steps, double_tap_sprint, jump_in_place | 0 | 0 | 0 | 0 | no intra-tick event |
| walk_off_ledge | 0.35 | 0.47 | 0.47 | 0.53 | edge departure resolved mid-tick (vanilla "hovers" until the tick ends because it clips Y first) |
| sneak_edge (diagonal walk-off phase) | 0.02 | 0.48 | 0.61 | 0.67 | same |
| jump_onto_block | 0.42 | 0.52 | 0.58 | 0.65 | landing time resolved mid-tick |
| head_hitter | 0.31 | 0.48 | 0.60 | 0.70 | ceiling contact mid-tick |
| sprint_jump_chain | 4.7 | 5.8 | 7.3 | 8.1 | earlier landing → earlier re-jump; drift accumulates over 70 ticks |
| turning_jumps | 1.3 | 2.7 | 3.4 | 3.9 | same, plus yaw |

Offline cost at 240 Hz: about 4–10 µs per vanilla tick (12 substeps) against a static course on the dev machine.
This does **not** include in-game `World.getCollidingBoundingBoxes` cost; Phase 4 must profile that.

## Jump characteristics (flat floor)

| model | apex (sampled) | landing τ (ticks) | sprint-jump air distance |
|---|---|---|---|
| vanilla 20 Hz | 1.2492 (tick-boundary samples) | 12.000 | 3.6274 |
| 240 Hz PER_SUBSTEP | 1.2591 (true curve max) | 11.250 | 3.8243 (+5.4 %) |
| 240 Hz TICK_LATCHED | 1.2591 | 11.250 | 3.4137 (−5.9 %) |

Tick-boundary heights during the jump are identical to vanilla (gate C). The landing time changes because the
trajectory crosses the floor between tick boundaries (τ ≈ 11.25), and vanilla only detects contact at τ = 12.

## Decisions (2026-10-10, by the project owner)

1. **Full substep simulation.** Friction and acceleration follow the contact state at each substep.
   `TICK_LATCHED` was removed after the comparison above. Different jump distances are accepted
   as long as they are measured and intentional.
2. **Input resolution is a goal in its own right.** Movement keys are sampled separately from `KeyBinding`
   and fed to substeps by **timestamped sample-and-hold**: each substep uses the latest sample taken at or
   before its start time. Nothing is split inside a substep and nothing is interpolated. The Windows-only
   async sampler (JNA `GetAsyncKeyState`, opt-in) is approved, and so is this change of scope from the
   original proposal.
3. **Yaw.** By default each substep in a frame uses the frame's yaw. Interpolating yaw within a frame is
   experimental only.
4. Clocks stay independent: sampler rate, physics rate (20·N Hz) and render FPS. **VANILLA mode** is a
   passthrough hook and must stay bit-exact. No upper physics rate is fixed; presets need measured benefit.

## Phase 2b: per-substep input (2026-10-10)

`SubstepModel` now runs the input-driven state machines at every substep:
- sprint rules against the previous substep's input,
- the double-tap window (7 ticks) and jump cooldown (10 ticks), counted in τ,
- the jump check,
- air acceleration from the current sprint state.

Only the sprint timeout and the 0.005 cutoff stay at tick boundaries. Each rule generalizes vanilla's, so N=1 is still bit-identical.
Intended differences: sprint stops on the substep where a wall is hit (wall_scrape 0.005 blocks), and air
acceleration follows a sprint change after h instead of one tick.

Gates: A 765/765 · B 765/765 · B2 (timeline path ≡ held-input path) at 40–960 Hz · C ≤ 3.7e-14 · D pass.

### Physics convergence (vs 3840 Hz, tick-sampled input; worst non-chaining scenario, blocks)

| 40 Hz | 60 Hz | 120 Hz | 240 Hz | 480 Hz | 960 Hz |
|---|---|---|---|---|---|
| 0.73 | 0.33 | 0.19 | 0.089 | 0.046 | 0.017 |

The error is first order (it halves each time the rate doubles), and all of it comes from contact
resolution, since free flight is exact. Sprint-jump distance converges too: 3.824 (240) → 3.850 (480) →
3.863 (960), limit ≈ 3.88 (+7 % over vanilla). Rates still differ from each other by up to 1 %.

### Input-timing sweep (jump press at 200 sub-tick offsets; mean takeoff delay after the real press, ms)

| sampler \ physics | 120 Hz | 240 Hz | 480 Hz | 960 Hz |
|---|---|---|---|---|
| vanilla KeyBinding + 20 Hz physics | 25.0 (one column) | | | |
| frame-polled, 60 FPS | 12.5 | 10.5 | 9.4 | 8.9 |
| frame-polled, 144 FPS | 7.5 | 5.5 | 4.5 | 4.0 |
| frame-polled, 240 FPS | 6.2 | 4.2 | 3.1 | 2.6 |
| 125 Hz keyboard + 1 kHz poll | 8.7 | 6.6 | 5.5 | 5.0 |
| 1 kHz keyboard + 1 kHz poll | 4.7 | 2.6 | 1.5 | 1.0 |
| exact (ideal) | 4.2 | 2.1 | 1.0 | 0.5 |

Mean takeoff position error, 1 kHz keyboard: 25 / 14 / 8 / 5 milliblocks at 120/240/480/960 Hz
(vanilla: 140). The delay is roughly half the sum of the sampler period and the physics period, so
**whichever is slower limits the result.** With a frame-polled sampler, input resolution is capped by FPS. With a
125 Hz keyboard, physics above ~240 Hz gains under 1.6 ms. Only a 1 kHz keyboard with the async sampler
benefits from 480–960 Hz. Sampler phases vary per event, because frames and reports are not aligned to the tick grid.

Offline cost per vanilla tick: 3.2 µs (240 Hz), 5.9 µs (480 Hz), 11.4 µs (960 Hz), with static-course collision only.

Server-side validation still runs vanilla 20 Hz physics, so every mode can be corrected by a server.
That is measured in Phase 6.

## Phase 2c: press latch (2026-10-10)

Plain sample-and-hold loses any key tap whose press and release both fall between two substep starts.
Gate E showed this at every rate: 0 jumps for a 0.4-substep tap. `InputTimeline.atLatched` treats a key
with a press edge since the previous substep as down for one substep. With it, gate E gives exactly 1 jump
at 40–960 Hz. Releases are not latched (a sub-substep release-and-repress is not a human input).

## Phase 3a: input pipeline measured in-game (2026-10-10)

Run: `$env:HRMOVE_INPUTPROBE='auto'; gradlew runClient`, then `gradlew inputAnalysis`.
The game window must stay focused for about 2 minutes: injection only happens while it is the
foreground window, and unfocused keystrokes are never logged.

**Method.** 630 synthetic F13 pulses (1–66 ms long, 40–100 ms apart) were injected with `keybd_event`,
at FPS limits of 60, 144 and unlimited (vsync off). Every source timestamps transitions with `System.nanoTime`.
Latency is measured **from the moment the OS accepted the event**. Keyboard scan, debounce and USB polling
come before that point; they cannot be measured in software and are not included.

| Source | p50 press latency (60 / 144 / unlimited FPS) | p99 at 60 FPS | Misses |
|---|---|---|---|
| async poller, 2 kHz, high-resolution waitable timer | 0.38 / 0.39 / 0.41 ms | 0.9 ms | 1 of 630 pulses |
| per-frame `Keyboard.isKeyDown` at vanilla's poll point | 21 / 8.1 / 1.0 ms | 33 ms | most pulses shorter than a frame |
| per-frame read after a fresh `Display.processMessages()` | 7.7 / 3.1 / 1.1 ms | 18 ms | same |
| vanilla event loop (when `KeyBinding` changes) | 38 / 31 / 26 ms | 65 ms | see below |

**Vanilla loses short taps.** If a tap's press and release are both handled in one event loop, `KeyBinding`
ends that loop released and physics never sees the tap. Measured share of taps lost:
1–2 ms: 100 %; 4 ms: 90–97 %; 8 ms: 72–97 %; 16 ms: 73–87 %; 33 ms: 27–50 %; 66 ms: 0 %.
This matches the geometry: a tap of length d is seen only if it spans a tick boundary, which has probability d/50 ms.

**Poller timing.** Java's `parkNanos` and `sleep` snap to the ~1 ms system tick (a 1 ms schedule has p90 2 ms).
A Windows high-resolution waitable timer (`CREATE_WAITABLE_TIMER_HIGH_RESOLUTION`, via JNA) gives a 0.5 ms
period with p50 0.55 ms, p99 1.15 ms, using about 3 % of one core. There were 10 gaps longer than 5 ms in
70 s (startup, GC); the worst was 347 ms. A 250 µs period is not achievable (the timer's floor is about 0.5 ms).

**Game-time anchor.** Frame-to-frame consistency of τ against vanilla's Timer clock: p99 0.005 ms at 144 FPS
and 1.2 ms at 60 FPS. Against raw frame `nanoTime`: p99 1–2.4 ms, max 32 ms, because tick work delays the
render event. A global linear fit is misleading here: the Timer's sync adjustment drifts its rate by ~1 %.
→ **Sample timestamps are mapped to τ through the Timer itself, re-anchored every frame.**

**LWJGL event timestamps** (Windows) have 1 ms resolution. They are only available in vanilla's event loop,
i.e. 26–38 ms late, so they cannot drive live physics.

**What this does not establish.**
- Real keyboard latency and report rate. That needs a manual session (`HRMOVE_INPUTPROBE=manual`); the analyzer then estimates the USB report grid from phase clustering.
- Behavior on other operating systems: the async poller is Windows-only, and elsewhere the frame sampler with a fresh pump is the fallback.
- How often humans actually make sub-50 ms taps.

## Phase 4a: passthrough hook (2026-10-10)

A coremod (`HRLoadingPlugin`/`HRTransformer`) redirects the single `moveEntityWithHeading` call in
`EntityLivingBase.onLivingUpdate` to `HRHooks`. Without a handler, `HRHooks` makes the identical
virtual call. It fails closed: anything other than exactly one call site leaves the class untouched.
**Gate A′:** the full corpus was re-recorded with the coremod loaded (`run/hrm-baseline-a2`):
- 765/765 ticks bit-exact against `VanillaModel`.
- 12/14 files byte-identical to the reference recording.
- `sneak_edge`: recorded at a different origin in the reference.
- `walk_off_ledge`: identical up to tick 24. The test player is now invulnerable, so the server's fall-damage velocity packet (`S12`) no longer occurs.

## Phase 4b: live engine (2026-10-10)

Architecture (`client/live/LiveEngine`):
- Player time τ is mapped through vanilla's Timer and re-anchored every frame. The player reaches τ = m exactly at the START of vanilla tick m, so packets carry boundary states. Frames run every substep that has fully ended by "now".
- Inside the vanilla tick, the hook restores the engine's boundary state and skips vanilla's move. `HRMovementInput` supplies the sampled sneak state and suppresses vanilla's jump.
- External changes (S08, S12, entity pushes) are detected by comparing the entity with the last written state, then adopted and counted.
- The engine also performs the move's own side effects: step sounds, view-bobbing distance, limb swing, and jump stat/exhaustion/`LivingJumpEvent`.
- The camera shows the live substep state (`prevPos = lastTickPos = pos` each frame), so there is no double interpolation.
- Unsupported states (liquids, ladders, webs, riding, flight-capable modes, potions, item use, blocks with movement side effects) fall back to vanilla at a tick boundary.

**Gate L** (`gradlew liveValidation`): in-game recordings of the corpus in high-resolution mode with scripted input.

| run | result |
|---|---|
| 20 Hz, tick-driven | **byte-identical to the vanilla recording (14/14 files)**; 765/765 bit-exact vs offline |
| 240 Hz, tick-driven | 765/765 bit-exact vs offline `SubstepModel` |
| 240 / 480 / 960 Hz, frame-driven (real batching) | 765/765 bit-exact vs offline at each rate |

**In-game cost** (real collision queries; uncapped FPS ≈ 550; 14-scenario corpus):

| rate | µs per substep | engine µs per frame p50 / p99 / max | substeps per frame | frame ms p50 / p99 |
|---|---|---|---|---|
| 20 Hz | 15.4 (cold JIT) | 1 / 18 / 245 | 0.0 | 1.71 / 3.85 |
| 240 Hz | 5.5 | 1 / 16 / 270 | 0.4 | 1.66 / 3.44 |
| 480 Hz | 4.0 | 4 / 14 / 220 | 0.8 | 1.84 / 4.30 |
| 960 Hz | 2.2 | 5 / 20 / 320 | 1.7 | 1.87 / 3.76 |

At 960 Hz the engine uses about 2 ms of CPU per second. **Performance does not limit the rate choice.**

**Server acceptance (integrated server only).** There were zero corrections during recorded movement at any rate,
including the longer sprint-jumps. Corrections appear only right after long teleports, while chunk/block sync
is pending; vanilla shows the same effect less often. Multiplayer servers and anti-cheat plugins are untested.

**Fallback transitions (in-game, `HRMOVE_BASELINE_SET=fallback`).** The player walks through a water pit
(holding jump to swim out) and onto soul sand. Before entering the water, the high-res trajectory follows
its own substep physics; while falling back it is identical to vanilla. Verified sequence:
active → fallback "liquid nearby" → reactivated → fallback (swimming) → reactivated → fallback "soul_sand".
**Bug found and fixed by this test:** reactivation restarted the engine clock and cleared the input timeline,
which discarded the scripted input; in real play it would have discarded already-timestamped samples. The
player froze after the second fallback. Now one continuous clock counts every player tick, active or not.
Gate L and the 20 Hz byte-identity were re-run after the fix and pass.

**Open items.**
- Manual play feel: camera smoothness, view bobbing, third person.
- Real keyboard report rate (`HRMOVE_INPUTPROBE=manual`).
- The poller thread restarts on every reactivation, so keys pressed during a fallback period come from the re-read on activation.
- Remote servers.

## Phase 5: rate normalization, continuous contacts (2026-10-10)

**Finding from the user's play session.** The user's world was creative. The engine treated every
flight-capable player as unsupported and fell back to vanilla *silently*, so nothing the user tested was
high-res. Fixed:
- Creative is supported while on foot; the engine performs the double-tap-jump flight toggle itself and falls back only while actually flying or in spectator.
- When HIGH_RES is selected, the actual state ("active N Hz" / "vanilla fallback: reason") is shown in chat (rate-limited).

**User decision.** Rates are customisable up to **480 Hz** (config cap). A higher cap only if rates are
normalized (shown below; the decision is pending).

**Why rates differed.** Free flight is exact at every rate (gate C), so all rate dependence came from
contact events resolved only at substep boundaries, plus ground physics during the jump substep.
`SubstepModel.Contacts.CONTINUOUS` (now the live default) removes these:
- Landing, ceiling and wall contacts: the contact time is solved on the analytic flow, the substep is split there, and the remainder runs under the new contact state.
- Walking off edges: support loss is located by bisection and the box is placed just past the edge. A first version returned the last *supported* instant; the leftover 1e-12 overlap let the collider's Y-first pass put the player back on the ground for one substep. Measured, then fixed.
- Takeoff is instantaneous: air physics starts right after the impulse.
- A held jump re-fires at the exact landing instant, with the cooldown counted from that instant.
- The sneak-edge guard is exact: the largest safe move under vanilla's support rule (anything within one block below), computed from one collision query. Vanilla's 0.05-block decrements made the stopping point depend on step size.

Input is still sample-and-hold at substep starts; only physics events split a substep.
DISCRETE mode is kept for vanilla-equivalence gates (B, L at 20 Hz).

| measure | DISCRETE | CONTINUOUS |
|---|---|---|
| sprint-jump distance, 120–960 Hz spread | 2.03 % | **0.00 %** (4.8938 at every rate, 40–960 Hz) |
| corpus worst, 240 vs 480 Hz (excl. jump chains) | 0.044 blocks | **0.0040** |
| corpus worst, 480 vs 960 Hz | 0.029 | **0.0020** |
| walk_off_ledge / sneak_edge vs 3840 Hz | first order | **0.0000 at every rate** |
| held-jump chain at 240 Hz vs 3840 Hz | 0.87 | 0.0051 |

What remains is O(h) and at most 4 mm between 240 and 480 Hz: step-up, sprint stop on wall contact, and
the air-acceleration update, which are still evaluated at substep starts.

**"Floaty" check (user report).** Gravity is identical: tick-boundary heights match vanilla (gate C).
The displayed path is not the same, though. Vanilla draws straight lines between tick positions (apex 1.2492);
high-res shows the true curve (apex 1.2591). Time above 1.2 blocks: 2.22 ticks (vanilla display) vs
2.42 ticks (high-res), i.e. ~10 ms more near the top. Time above 1.0 block: 5.07 vs 5.09. The user's
session itself ran vanilla (creative fallback), so the report there cannot have come from high-res physics.

**Sprint-jump distance vs vanilla (intentional, measured).** Over a fixed window (takeoff to 14 ticks
later): vanilla 4.3526, CONTINUOUS 4.8938 (+12 %). Takeoff to touchdown: vanilla 3.63 blocks, high-res
≈3.88 (+7 %). The cause is vanilla's jump tick, which applies ground friction (×0.546) for the whole
tick in which the player leaves the ground; in continuous time that interval is zero. This changes
which gaps are reachable, so it is flagged to the user.

Live validation after the change:
- 20 Hz DISCRETE: byte-identical to vanilla (14/14).
- 240 and 480 Hz CONTINUOUS, frame-driven: 765/765 bit-exact vs offline.
- Cost: CONTINUOUS ≈ 10–13 µs per substep (1.9 queries), i.e. ≈5 ms of CPU per second at 480 Hz; engine p99 34 µs per frame.

## Phase 5b: end-to-end tap test (in-game, real OS input, 2026-10-10)

`HRMOVE_INPUTPROBE=taptest gradlew runClient` (the game window must be focused, ~5 min), then `gradlew tapAnalysis`.
The forward key is pressed through `keybd_event` for 3–100 ms at random phases; the result is the distance
moved per tap, 3 taps per cell. "model" = offline prediction (vanilla: expected value over phases).

| tap | VANILLA mean (min–max) | 240 Hz mean (min–max) | 480 Hz mean (min–max) |
|---|---|---|---|
| 3 ms | 0 (all lost) | 0.0074 (0.0070–0.0081) | 0.0071 (0.0049–0.0114) |
| 10 ms | 0 (all lost) | 0.040 (0.029–0.047) | 0.031 (0.027–0.036) |
| 20 ms | 0.2054 (every tap = a full 50 ms tick) | 0.0797 (0.0790–0.0805) | 0.0817 (0.0793–0.0838) |
| 30 ms | 0 (all lost) | 0.119 (0.118–0.120) | 0.123 (0.118–0.127) |
| 60 ms | 0.2054 (one tick) | 0.250 | 0.253 |
| 100 ms | 0.4229 (two ticks) | 0.4247 | 0.4259 |

**Verified:** vanilla is quantized to whole ticks (0 / 0.2054 / 0.4229). High-res distance is proportional
to the real tap length, with small phase spread, and matches the model. The remaining spread for 3–10 ms
taps comes from substep quantization (4.2 ms at 240 Hz, 2.1 ms at 480 Hz) plus the press latch.

## Phase 6: vanilla-parity mechanics, camera, rate cap (2026-10-10)

**User decisions.** Jump distance should match vanilla (option B). Raise the rate cap as far as experiments
justify. User report: input is now truly sub-tick, but the camera shakes and sprint-jumping is faster than vanilla.

### Vanilla-parity rule (CONTINUOUS mode)
Positions stay geometrically exact, but **contact-state changes take effect on a 20 Hz grid, as in vanilla**.
These are ground/air for friction, acceleration and jump eligibility, plus the ceiling bounce. Inside a jump
the grid is the jump's own clock (it starts at the real, high-res takeoff instant); otherwise it is the game tick.
- Jump arc: for 1 tick after takeoff, horizontal physics uses the takeoff block's ground friction/acceleration (vanilla's jump tick).
- Touchdown: air physics continues until the arc's next tick. That is where vanilla detects the landing, and where a held jump re-fires.
- Walking off an edge: the player "hovers" grounded until the next game tick, and can jump during it (vanilla's last-moment jump). If no jump happens, the fall starts there with one tick of ground parameters, as vanilla's first falling tick.
- Ceiling: the post-hit velocity applies at the next grid tick.
Input stays high-res: jumps, key changes and yaw act at substep resolution, so a jump pressed during the edge hover fires immediately.

Divergence from vanilla (tick-sampled corpus, max over each run, blocks), CONTINUOUS at 40–960 Hz:

| scenario | before parity | now |
|---|---|---|
| sprint_jump_chain, turning_jumps, jump_in_place, walk/sprint/strafe/stand, slab_steps, double_tap | up to 8.8 | **0.0000** |
| walk_off_ledge | 0.59 | **0.0000** |
| head_hitter | 0.076 | 0.0040 @240 → 0.0015 @960 |
| wall_scrape | 0.0047 | 0.0047 (sprint stops on the substep of the wall hit, not at the tick) |
| sneak_edge | 0.74 | 0.0424 (exact sneak guard lets the player stand up to ~4 cm further out than vanilla's 0.05-block decrements) |
| jump_onto_block | 0.15 | 0.15. **Open:** vanilla resolves each tick's vertical move before the horizontal one, so after hitting a ledge's side it lifts first and then moves forward a full tick. In continuous time the player clears the corner only at the end of that tick: ~0.1 blocks less progress |

Single sprint-jump distance (14-tick window): vanilla 4.3526, CONTINUOUS **4.3526 at every rate**. Held-jump count = vanilla.
Long run: 6000 ticks (5 min) of held sprint-jumping while turning stays within 1e-11 blocks of vanilla at every rate.

### Camera shake
Measured with `HRMOVE_CAMERAPROBE=1`: the coefficient of variation (CV) of the camera's frame-to-frame speed and of the view-bob
phase speed during a steady sprint, at 120 FPS (the user's frame rate).

| | position CV | bob-phase CV |
|---|---|---|
| vanilla | 8.1 % | 8.3 % |
| 240 Hz before fix | 12.9 % | **191.6 %** |
| 240 Hz after fix | **4.2 %** | **4.2 %** |
| 480 Hz after fix | 3.9 % | 3.9 % |

Causes:
1. **View-bob jerk.** Vanilla's bob adds (distanceWalked − prev)·partialTicks with prev set once per tick. The engine advanced distanceWalked live, so the term grew within each tick and snapped back at the tick boundary.
2. **Uninterpolated position.** The display showed the last whole substep.

Fix: render at (now − h), interpolated between the last two substep states. Position and bob distance are swapped in
for the frame only and restored at RenderTickEvent END, so game logic never sees them. At uncapped FPS: 4.5 % vs vanilla 17.2 %.

### Rate cap (gradlew rateCapExperiment)
No error growth up to 15360 Hz:
- free-flight error ≤ 3e-10 relative;
- distance to the 2× rate shrinks from 1.3 mm (240 Hz) to 0.08 mm;
- the 5-minute chain stays identical to vanilla.

The practical limit is input: above ~2 kHz the substep is shorter than the 0.5 ms poller period.
In-game at 1920 Hz: 6.9 µs per substep (≈13 ms of CPU per second), engine p99 114 µs per frame, gate L passes.
→ **Config cap 1920 Hz, default 960 Hz** (~1 ms input quantization). Existing config files keep their saved value.

Regression after all changes:
- offline gates A–E pass;
- 20 Hz DISCRETE live run is byte-identical to vanilla (14/14);
- gate L passes at 240, 960 and 1920 Hz (CONTINUOUS, frame-driven).

## Phase 7: GRID contacts (replaces CONTINUOUS), 2026-10-10

**User decisions.** Leave the ledge-corner and wall-sprint behavior as is, unless a maneuver becomes
significantly easier or harder. Make the sneak-edge distance match vanilla.
**User report.** When landing on an edge while pressing space, the jump sometimes fails where vanilla jumps.

**Reproduced offline** (`gradlew edgeLandingTest`). A sprint-jump lands near a platform end, then one 80 ms
space press at 9 timings relative to touchdown, over 25 landing points. CONTINUOUS lost the second jump in
48/225 cases where vanilla jumped. There were two causes, both vanilla's per-tick collision order:
1. In vanilla's landing tick, Y is resolved at the tick-start horizontal position. The player stays landed even after sliding past the edge, and can jump at the next tick. In CONTINUOUS, the player slid off during the landing grace.
2. Vanilla lands whenever the box was over the block at the tick start. CONTINUOUS required overlap at the contact instant, so it missed edge landings.

The mirror of (2) made gap jumps *easier* in CONTINUOUS: vanilla misses a landing if the box was not over the
platform at the tick start; CONTINUOUS landed anyway (up to ~0.28 blocks of leniency).

**Decision: GRID contacts** (`SubstepModel.Contacts.GRID`, live default). Rather than patching each vanilla rule into
continuous time, motion and input stay at substep resolution, but collision is vanilla's own:
- At every substep the box is vanilla's collider (Y, X, Z; sneak guard; step-up) applied to the free displacement accumulated since the current grid tick began, from that tick's start box. Positions are always collision-safe. At each grid-tick end the position is exactly vanilla's move.
- Contact state (onGround, collision flags, clipped-axis velocity responses) is committed at grid-tick ends; the 0.005 cutoff applies at grid-tick starts.
- A jump restarts the grid at its real instant, so every arc is vanilla's arc.
- A jump pressed mid-tick needs grounded-at-tick-start **and** grounded-now (vanilla's collider at the current position). The first alone gave up to a tick of extra coyote time (measured: 16/225 extra jumps). The second alone would allow a jump at the instant of touchdown, faster than vanilla's cadence.

CONTINUOUS and all its machinery (arc clocks, edge hover, landing grace, ceiling hold, contact-time search, exact
sneak clamp) were removed. Sneak-edge parity follows from vanilla's own guard being applied to the tick displacement.

Results (offline, `gradlew movementTests` / `edgeLandingTest` / `rateCapExperiment`):
- GRID at N = 1: 765/765 ticks bit-identical to vanilla. Free flight exact at 40–960 Hz (≤ 3.7e-14).
- Divergence from vanilla (tick-sampled corpus): **0.0000 for every scenario** at 40–960 Hz, including sneak_edge, wall_scrape, head_hitter, walk_off_ledge, sprint_jump_chain and turning_jumps. The exception is jump_onto_block at 0.003–0.006 (the ledge corner; left as is per the user, now within 6 mm).
- Edge landing: lost 0, extra 0 (of 225) at 240 and 960 Hz; held chains 0/50 differences.
- Rate spread: sprint-jump distance 4.3526 at every rate. Corpus: 240 vs 480 Hz 0.35 mm, 960 vs 1920 Hz 0.045 mm, halving each step. No error growth up to 15360 Hz; a 5-minute chain stays within 2e-11 of vanilla.
- Offline cost per vanilla tick: 4.4 µs (240 Hz), 11 µs (960 Hz), 24 µs (1920 Hz). About 20× cheaper than CONTINUOUS.

**Gate F (user requirement: input acts from its detection time, not the next tick).** Forward pressed at
tick 2.25 has moved the player 0.04015 blocks by 2.75, exactly the exact-flow prediction for 0.5 ticks of
acceleration. Jump pressed at 5.25 has lifted the player 0.221 blocks by 5.75. Both happen before the next tick
boundary, at 400 and 960 Hz.
Only collision bookkeeping uses vanilla's tick timing; movement keys, sprint, sneak, yaw and jump act from
the substep in which they are detected.

**Bug found by the in-game gate L and fixed.** After the runner's teleport, adopted mid-frame, the grid restarted
at an arbitrary moment and stayed offset from the game ticks. Physically valid, but not vanilla's tick phase
(gate L failed 10–14 of 14). A grid (re)initialized mid-tick now gets a partial first tick ending at the next
game-tick boundary; only jumps re-phase the grid.

**In-game verification (GRID):**
- Gate L bit-exact 765/765 at 240, 960 and 1920 Hz (frame-driven, scripted).
- 20 Hz DISCRETE live run byte-identical to vanilla (14/14).
- Fallback scenario at 960 Hz: active → liquid → active → soul sand.
- End-to-end tap test (real OS key presses of 3–100 ms at random tick phases, 27 per mode).
  - VANILLA is tick-quantized. Taps of 3–10 ms and 30 ms moved 0, and 15–60 ms taps moved 0 or 0.2054
    (one tick) regardless of length.
  - GRID moves the player in proportion to how long the key was held, at both rates. 3 ms moves 0.008, 10 ms
    0.037–0.041, 20 ms 0.080, 30 ms 0.118–0.122 and 60 ms 0.253–0.256 blocks, tracking the offline model within
    a few mm.
  - No tap was lost in HIGH_RES. One tap was skipped while the window was unfocused, before the VANILLA trials.
- Live cost: 2.9 µs per substep at 960 Hz, 1.5 µs at 1920 Hz (≈2.9 ms of CPU per second); engine p99 15–20 µs per frame; frame times unchanged.

## Phase 7b: in-tick display path (2026-10-10)

**User report (play test).** Two cases looked fine at tick boundaries but jittered in between:
- When sneaking into the edge limit, the player bounced slightly, less and less, then stopped.
- On sprint-jumps that only just reach a block's top, the player jittered, then continued.

Everything else (edge landings with space, sneak distance, sprint-jumps, short taps) the user reported as
identical to vanilla.

**Measured** (`gradlew jitterTest`, path along the direction of travel). Both are properties of vanilla's
collider applied to a *partial* tick displacement:
- The sneak guard trims in 0.05 steps, so the result sawtooths as the displacement grows. The box moved
  backwards by up to 0.049 blocks within ticks (240 and 960 Hz).
- Y-first clipping holds the box against a face that the full tick displacement clears. It stalled, then
  moved up to 0.337 blocks in one substep.

Tick-boundary results were exactly vanilla's in both cases. Vanilla never shows either effect, because it
renders a straight interpolation between tick results.

**Fix: a view position, used for rendering only** (`SubstepModel.updateView`; `PlayerState.viewDX/DY/DZ`).
- Each substep predicts the end of the current grid tick, holding the current input, and runs vanilla's collider on it.
- On axes that the predicted end limits (sneak guard, wall, floor), the view follows the free path but never passes the end.
- Other axes follow the free path, which cuts block corners exactly as vanilla's interpolation does.
- At grid ends the view equals the box.
- The physics box, contact logic, server positions and every gate are unchanged.
- Walked distance (view bob, step sounds) follows the view path.

Results:
- Sneak edge: backtrack 0.049 → 0.000.
- Grazing step, 400 takeoff offsets: stall-release 0.337 → 0.000 blocks per substep.
- Tick-boundary results unchanged; all gates and the edge-landing test pass.
- In game, gate L is bit-exact 765/765 at 960 Hz with the view renderer active.

Note: the same test shows the known wall-sprint residual in 175/400 grazing-step runs (≤ 4 cm by the end of
the run). After leaving a wall, the substep model applies sprint air acceleration one substep later rather
than one tick later. This is left as is, per the user's decision.

## Phase 8: EXACT input timing, a third mode (2026-10-10)

**User proposal.** Timestamp each input and split the physics step at that instant: the dynamics before the
timestamp use the old input, those after it use the new one, with several splits if several inputs fall in one
step. The physics rate stays configurable, but input timing becomes as precise as the timestamps.
**User preference.** Keep the current mode as a preset and add this as a third setting.

**Feasibility.** The motion integrator is the exact flow of the per-tick map, valid for any duration, so the
split needs no new physics. `SubstepModel.substep(inputs[], offsets[], …)` runs each piece with the full input
logic (sprint rules, jump check) at its own start time, and the exact flow for its own length. GRID contacts
already work on a time grid that is independent of the substeps. Modes (config `mode`, F7 cycles):
`VANILLA` → `HIGH_RES` (unchanged preset: input sampled per substep) → `EXACT`.

Timestamp-splitting exposed three rate dependences, found with `gradlew exactTimingTest`. All are fixed, the
last two in EXACT only:
1. Timers (double-tap sprint window, jump cooldown) were reduced by the length of the piece about to run.
   With uniform substeps that equals the time since the previous check; with pieces it does not (a double-tap
   sprint was missed at 240 Hz, but not at 960 Hz). Timers now count the time since the previous check. HIGH_RES is
   bit-identical either way.
2. Air acceleration followed the sprint state one piece late, so the delay depended on where the next substep
   boundary fell. EXACT applies it from the instant sprint changes. Vanilla lags one tick, and HIGH_RES lags one
   substep; this is the wall-sprint residual the user chose to keep.
3. Contacts committed at a grid-tick end inside a piece (landing, wall) reached the sprint rules only at the next
   piece start. EXACT runs vanilla's per-tick sprint rules at grid-tick starts, where vanilla runs them.

The live engine splits at key timestamps (2 kHz poller) and at yaw samples (frame times).

**Results (offline).** 300 random timelines × 80 ticks, with key events at arbitrary times on a course with a
step, a gap and edges. The reference is HIGH_RES at 15360 Hz (a 65 µs input grid):

| | mean error | max error | runs with a different jump count |
|---|---|---|---|
| HIGH_RES 20 Hz | 0.339 | 4.29 | 49/300 |
| HIGH_RES 60 Hz | 0.122 | 2.66 | 16/300 |
| HIGH_RES 240 Hz | 0.032 | 1.56 | 4/300 |
| HIGH_RES 960 Hz | 0.0092 | 1.53 | 0/300 |
| EXACT 20 / 60 / 240 / 960 Hz | 0.00032 | 0.048 | 0/300 |

- EXACT results are the same at every rate: 2e-12 blocks apart between 20 and 960 Hz (gate G). The remaining
  error is the reference's own: its one-substep sprint lag, amplified at the sneak guard's 0.05 steps (without
  sneak the max is 0.0065).
- Gate G: forward pressed at tau 2.3137 moves exactly the analytic flow by tick 3, at 20, 60, 240 and 960 Hz.
- Vanilla parity (corpus, tick-sampled input): every scenario 0.0000, except jump_onto_block at 0.0059
  (HIGH_RES 0.0052; the wall-sprint residual).
- Edge landing: 0 lost and 0 extra jumps (of 225); 0/50 held-chain differences.

**In-game.** Gate L in EXACT at 240 Hz: 765/765 bit-exact. The scripted input changes only at tick
boundaries, so this checks the live EXACT path, not mid-substep splitting. The end-to-end tap test (real key
presses) covers that.

**End-to-end tap test** (real OS key presses of 3–100 ms at random phases, 27 per mode; one skipped while unfocused):

| tap | HIGH_RES 60 Hz | EXACT 60 Hz | EXACT 240 Hz | model |
|---|---|---|---|---|
| 3 ms | 0.0636 | 0.0066 | 0.0076 | 0.0064 |
| 10 ms | 0.0637 | 0.0376 | 0.0381 | 0.0364 |
| 20 ms | 0.0631 | 0.0805 | 0.0792 | 0.0784 |
| 30 ms | 0.1367 | 0.1227 | 0.1249 | 0.1226 |
| 60 ms | 0.2552 | 0.2544 | 0.2531 | 0.2498 |

- HIGH_RES at 60 Hz rounds each tap up to whole 16.7 ms substeps (press latch): 3–20 ms taps all move about 0.063.
- EXACT moves in proportion to how long the key was held, and 60 Hz and 240 Hz agree within the spread between taps.
- Measured distances run 0–5 mm above the model. Hypothesis, not verified: the injected key stays down a little
  longer than requested (injection call plus the 0.5 ms poll period).

**What the rate means in EXACT.** It no longer changes the physics or input timing. It sets how often the view
position is computed and the render delay (frames are drawn at now − h: 4 ms at 240 Hz, 1 ms at 960 Hz), plus CPU.

## Phase 9: stability review and user-facing release (0.3.0, 2026-10-10)

**Stability evidence:**
- Full offline suite: all gates pass (A–G).
- In-game gate L is bit-exact for HIGH_RES at 240, 960 and 1920 Hz and for EXACT at 240 Hz. DISCRETE at 20 Hz is byte-identical to vanilla.
- Fallback scenario passes. Tap tests in all modes are as predicted.
- The user's play tests found no perceivable error in HIGH_RES or EXACT.
- Server acceptance (singleplayer): every available client log was searched. No "moved wrongly" / "moved too quickly" rejections, and the user's own play session had 22 activations and 0 external position corrections.
- Live cost: 2.8 µs per substep at 960 Hz (HIGH_RES) and 8.4 µs at 240 Hz (EXACT). The engine's p99 per frame is ≤ 141 µs.
- Report 4's "landTau" column was wrong for GRID: a measurement bug, not physics. GRID commits onGround at grid ends, so the report counted the takeoff tick as a landing. Fixed: a landing now requires having been airborne.

**User side:**
- Forge config screen (`HRGuiFactory`): mode and rate on the first page; Input… and Display… pages. Readable names (en_US.lang) and tooltips.
- Settings apply live: a `generation` counter restarts the engine.
- F8 cycles OFF / SIMPLE / DETAILED. The overlay shows the mode actually running (VANILLA / HIGH_RES / EXACT), in green when active and yellow with the reason when paused. Previously EXACT was shown as HIGH_RES.
- Chat notices can be turned off. An *Open movement settings* key is available (unbound).
- Verified by `HRMOVE_UIPROBE=1`: screenshots of each screen and overlay state, with mode changes made through the config screen's save path.

## Phase 10: TACTILE 0.4.0, user feedback round (2026-10-10)

**Renamed** to TACTILE (modid `tactile`, config/tactile.cfg; an old hrmovement.cfg is migrated). EXACT is now the
default mode, marked recommended.

**Rate slider bug.** `Configuration.get(cat, key, int)` resets a property's range to the full int range. The GUI
factory used that overload just to attach the slider class, so the slider covered ±2³¹. Fixed: the property is
looked up directly. `RateEntry` adds a slider (20–960 Hz in steps of 20) and a box for typed values up to 1920,
rounded to a multiple of 20.

**Poll rate up to 8000 Hz** (`gradlew pollerBench`, F24 polled, 3 s per rate):

| target | achieved | interval p50 / p99 | CPU (process, % of one core) |
|---|---|---|---|
| 1000 | 1000 | 1.05 / 1.15 ms | 6 % |
| 2000 | 2000 | 0.55 / 1.05 ms | 5 % |
| 4000 | 4000 | 0.30 / 0.30 ms | 99 % |
| 8000 | 7999 | 0.15 / 0.15 ms | 101 % |

The high-resolution waitable timer cannot sleep less than about 0.5 ms, so rates above 2000 Hz busy-wait a full
core. The default stays 2000 Hz (recommended in the tooltip); the tooltip states the measured cost.

**Block-placement ghosts (user report).** A block placed at the edge of the player's box appeared, then vanished.
- Cause: Minecraft.runTick handles clicks before the player update and checks placement against the client's
  box. In vanilla that box is the position the server last received. The engine had already advanced the entity
  to the current boundary, up to one tick ahead, so the client allowed placements that the server refused.
- Fix: between the tick-start catch-up and the move hook, the entity carries the last state sent to the server.
  The hook then installs the boundary state as before.
- Measured (`HRMOVE_PLACEPROBE`): walking forward and placing in the cell behind the player the moment the
  client allows it, 55 attempts per mode:

| | VANILLA | EXACT, fix off | EXACT, fix on |
|---|---|---|---|
| placed | 55 | 0 | 55 |
| ghosts | 0 | 55 | 0 |

- Note: the first probe run stalled after teleporting (it never settled, and no diagnostics were logged then).
  It did not recur in the two following runs. Cause unknown; possibly first-time chunk generation at the new
  runway in the renamed mod's world.

## Phase 11: Modrinth / multiplayer feedback (2026-10-10)

User report (production jar in Modrinth App):
- Fine on one server at EXACT 20 and 960 Hz.
- Always paused on a server that gives night vision.
- A stall while bridging.
- Hitches when control switches at ladders.
- Felt latency at EXACT 20 Hz, and possibly for sneak and sprint.

**Bridging stall (reproduced, fixed).** `HRMOVE_BRIDGEPROBE`: walk backwards off a platform end and place a block
under the trailing edge once it is within *lead* of the bridge end (33 blocks per run).

| lead | VANILLA | EXACT before | EXACT after |
|---|---|---|---|
| +0.30 | 33 placed, 0 stalled ticks | 33, 0 | 33, 0 |
| +0.10 | 33, 0 | 23, 13 (1 snap-back) | 33, 0 |
| +0.03 | 33, 0 | 20, 17 (1 snap-back) | 33, 0 |
| 0.00 | 33, 0 | 19, 19 | 33, 0 |
| −0.03 / −0.08 | falls (both) | falls | falls, same placements |

- Cause: since the ghost fix (Phase 10), the click is checked against the server's view (the last boundary), as
  in vanilla. The engine had already simulated the following tick without the new block. Near the edge the
  player was already slightly below the block's top, so the new block overlapped the sent position, and the
  server refused it.
- Fix (`LiveEngine.replayIfBlocked`): when the boundary state overlaps a collision box at the move hook, rewind
  to the last sent state (mirror, plus model state via `SubstepModel.save/restore`) and replay the tick against
  the current world. Jump and step side effects are not repeated. This is what vanilla does, because it
  simulates the tick after the click.

**Potions, server speeds, hunger (supported now; previously any effect paused the engine).**
- Speed and slowness act only through the movement-speed attribute. The engine reads it every tick, without
  and with the sprint modifier, through the attribute's own code (`readMovementAttributes`). This also covers
  servers that change walk speed.
- Jump boost is modeled in `VanillaModel.jump`.
- Sprint conditions now include vanilla's food check (> 6 or allowFlying) and blindness. Before, a hungry
  player could sprint with the mod where vanilla refuses.
- Other vanilla effects (night vision, haste, …) do not touch movement. Only unknown modded effects still pause.
- Verified in game (`HRMOVE_BASELINE_EFFECT`/`_FOOD`, `gradlew traceDiff`): VANILLA vs EXACT 960 Hz, 7 scenarios
  each, under Speed II, Slowness I, Jump Boost II, Blindness, Night Vision and food 5: **0.0000 in every
  scenario**.
- A first run showed differences that came from the harness: effects are saved with the player, so one
  recording inherited the previous one's effects (Slowness on top of a leftover Speed II = ×1.19). The recorder
  now clears effects first.

**Hand-over smoothness (ladder, water, soul sand, mode switch).**
- Cause: vanilla draws the player about one tick behind the simulation (interpolating its last two ticks). The
  engine draws the present (EXACT) or one substep behind. When control changed hands, the camera jumped back
  (or forward) by about a tick of motion. At 20 Hz the engine's own render delay was one tick, so it matched
  vanilla, which is why the user saw no hitch there.
- Fix (`LiveEngine.blend`): at a hand-over the camera continues from where it was (with its last step), and
  the difference decays exponentially (80 ms). Where the camera is ahead along the direction of travel, the
  offset shrinks by at most 70 % of the source's own step, so the camera never stops or backs up when the
  source slows sharply.
- Measured (`HRMOVE_TRANSPROBE`): sprint across a 2-block soul-sand strip, vanilla in control on it, the engine
  before and after it, about 550 FPS.
  - Without the blend: a 0.26-block backward jump at the hand-over to vanilla, and a forward jump at the
    hand-back.
  - With the blend: 0 backward frames. Steps around both hand-overs stay smooth (0.0096 → 0.0096 → 0.0084 →
    0.0047; 0.0053 → 0.0053 → 0.0164 → 0.0129).
  - Peak camera speed near a hand-over: 9.5 b/s while catching up (sprint 5.4).

**Render latency.**
- EXACT now draws the state at exactly the frame time: a throwaway copy is advanced from the last substep to
  "now" with the inputs so far (`provisional`, using `SubstepModel.save/restore`). Before, it drew the state one
  substep old (50 ms at 20 Hz, 1 ms at 960 Hz), which explains the delay the user felt at EXACT 20 Hz. HIGH_RES
  keeps the (now − h) interpolation.
- Sneak: the crouch (eye height, pose) follows the key every frame. Before, it changed only at the next tick
  (vanilla behaves the same: up to 50 ms). Packets and game logic still use the tick value.
- Sprint has no direct visual cue apart from the FOV change. Vanilla smooths the FOV per tick, and that is left
  unchanged. The sprint's physical effect is immediate in both modes.

**Probe harness fixes.**
- Courses near spawn: a player teleported more than a view distance away can freeze in an unloaded chunk.
  `serverUpdateMountedMovingPlayer` sends the destination chunks immediately.
- Every variant respawns and protects the player, because a previous run may have ended in a fall.

## Phase 12: water, lava, ladders and vines inside the engine (2026-10-10)

**User proposal.** Instead of handing control to vanilla at ladders and water (where hand-overs caused camera
hitches), support them in the engine.

**Feasibility.** Vanilla's water and lava branches are affine per-tick maps with different constants:
- horizontal: v' = (v + a)·drag, with drag 0.8 (depth strider varies it) or 0.5, and a from moveFlying(0.02);
- vertical: v' = (v + j)·drag − 0.02, where j = 0.04 while jump is held;
- per-tick rules at the tick start (the water current, the inWater flag) and at the tick end (jump out of the
  liquid: motionY = 0.3).

Ladders are the land map plus clamps at the tick start: (v + a) clamped to ±0.15, fall speed ≤ 0.15, no
descent while sneaking. At the tick end, motionY = 0.2 when collided horizontally on a ladder. Every part fits
the GRID design (per-tick contact rules, exact flows in between), so nothing nonlinear happens inside a tick.

**Implementation.**
- `VanillaModel`: handleWaterMovement push, swim/lava jump push, water (depth strider), lava, ladder clamps and
  climb, jump out of liquid. Statement order and constants follow EntityLivingBase 1.8.9.
- `SubstepModel`:
  - Each grid tick takes its medium (land / water / lava) and ladder flag from its start box, as vanilla takes
    them at the tick start.
  - The water current and the 0.005 cutoff apply when the tick starts moving; the ladder clamp follows the jump.
  - A clamped axis moves exactly the clamped speed (no further acceleration), so the tick stays bit-exact.
  - Liquid flows use `Flow.liquid`, the exact flow of v' = (v + j)·d − c, in an operation shape that is vanilla's
    at t = 1.
  - Grid-end responses per medium: the ladder climb, and the jump out of liquid.
  - In liquids, jump is a swim push (piecewise with EXACT input splits), not an impulse.
- In game, `WorldCollision` calls vanilla's own World.handleMaterialAcceleration / isMaterialInBB /
  isAnyLiquid and Forge's ladder rule, so flowing water, Forge hooks and both ladder modes behave as in vanilla.
- Webs, soul sand and slime still hand over to vanilla.

**Verification.**
- New corpus (`Scenario.mediumCorpus`): water_swim (swim across, swim up, climb out over the edge), water_tread,
  lava_swim, ladder_climb (climb, let go, sneak-hold, climb, back off), ladder_jump, vine_climb.
- Gate A (VanillaModel vs real vanilla recordings): 130/130, 97/97, 140/140, 102/102, 65/65, 60/60 bit-exact.
- Gate B: GRID at N = 1 bit-identical to VanillaModel, 1359/1359 ticks (land + media).
- Gate C (liquids): N substeps compose to the vanilla tick map in water and lava, with and without the swim push,
  ≤ 2.6e-15 relative.
- Report 1: GRID divergence from vanilla is 0.0000 for every medium scenario at 40–960 Hz.
- In game, gate L: EXACT 240 Hz and HIGH_RES 960 Hz, bit-exact on all 6 medium scenarios, with no fallback
  during the runs.
- End to end (`traceDiff`): live EXACT 240 Hz and HIGH_RES 960 Hz vs real vanilla: **0.0000** in all 6.

**Creative flight at hand-overs (user report: holding space near water or onto a ladder started flying).**
- Cause: at a hand-over to vanilla the engine installed a fresh MovementInputFromOptions, whose jump field starts
  false. With space held, vanilla saw a new press. Two hand-overs within vanilla's 7-tick double-tap window toggled
  flight (near water the "liquid nearby" check could flicker).
- Fix: the new input starts from the keys' real state.
- Reproduced with `HRMOVE_FLIGHTPROBE` (creative, space held) and `HRMOVE_TOGGLETEST=4` (a one-tick hand-over
  every 4 ticks): flight switched on with the old behavior (`HRMOVE_OLDHANDOVER=1`); 39 hand-overs and no flight
  with the fix.
- Ladders and water no longer hand over at all.

**Hand-over blend.** The no-backtrack constraint now uses the full 3D motion, so the camera also never backs up
vertically, e.g. when a jump coincides with a hand-over.

## Phase 13: soul sand, slime, cobwebs and creative flight inside the engine (2026-10-11)

**User notes (0.5.0).** The camera vibrated while holding still on a ladder with sneak. Jumping at EXACT 20 Hz
felt delayed. Next aim: soul sand, slime, flying and cobwebs without hand-overs.

**Ladder vibration (fixed).**
- Cause: vanilla moves by the speed at the tick start and applies gravity after. The exact in-tick curve of that
  map starts with an effective +0.04 blocks/tick, so a vertical speed reset to 0 every tick (sneak-hold on a
  ladder) rises ~1 cm and comes back within each tick: a 20 Hz vibration. Steady sliding and climbing wobble too.
- Fix: on ladder grid ticks the drawn height follows vanilla's straight line from the tick's start to its
  predicted end.
- `gradlew ladderViewCheck`, drawn height while sneak-holding: 0.0099 → 0.00000. Climbing and sliding: no reversals.

**Jump latency at 20 Hz.** Measured in game (`HRMOVE_LATENCYPROBE`): a timestamped jump is injected exactly as a
poller event, and the drawn height is recorded each frame.
- First visible rise: 2.3 ms (the next frame) at 20, 60, 240 and 960 Hz.
- Height after 100 ms: 0.716 at 20 Hz vs 0.741 at the other rates (unexplained, small; not a delay).
- Offline (`JumpLatencyCheck`), the drawn height after a mid-tick press is identical at every rate.

**Vanilla's move tail, ported (`VanillaModel.moveResponses`, `Entity.moveEntity` 1.8.9).**
- Clipped axes stop.
- onLanded on the block under the feet: slime bounces (motionY = -motionY) unless sneaking. This also applies
  when bumping a ceiling (vanilla quirk).
- The walking callback: slime x(0.4 + |motionY|·0.2) while |motionY| < 0.1, not sneaking, on the ground, not
  sneak-guarded.
- doBlockCollisions: soul sand x0.4 per block touched; a cobweb flags the next move.
- The next move with the flag set: displacement x0.25 / x0.05 / x0.25, motion zeroed (Entity.isInWeb).
- Creative flight (EntityPlayerSP / EntityPlayer):
  - ±3·flySpeed per tick at the tick start for jump/sneak;
  - horizontal acceleration flySpeed·(2 sprinting);
  - next vertical speed 0.6 x the speed moved with;
  - landing ends flight; no water current, cobweb or walking callbacks while flying.

**GRID.**
- Every grid-tick end runs that chain on the motion just after the move (the pre-drag motion, kept exactly for a
  whole-tick segment), then the medium's drag.
- A cobweb grid tick moves at a constant scaled rate.
- Flying uses the vertical form v' = (v + j)·0.6. A new ascend/descend press starts a grid tick at its instant
  (like jumps).
- **Whole-tick exactness**: a grid tick run with one flow throughout ends with a single whole-tick step instead of
  the sum of its segments. Before, rounding residue could turn vanilla's exactly-zero move (standing on slime,
  where the idle bounce zeroes the vertical speed) into a 1e-17 one that touches the floor, flipping onGround.
- Gate C is now exact (0.00) at every rate on land, in water, in lava and in flight.

**Verification (offline).**
- New corpus: soulsand_walk, slime_bounce, slime_walk_jump, slime_sneak_land, web_walk, fly_basic.
- Gate A, VanillaModel vs real vanilla: 65/65, 78/78, 75/75, 53/53, 110/110, 96/96 bit-exact.
- Gate B, GRID at N = 1: 1836/1836 ticks bit-identical.
- Report 1, GRID vs vanilla at 40–960 Hz: 0.0000 for every scenario, except two residuals that remain:
  - jump_onto_block, ≤ 6 mm;
  - soulsand_walk, ~0.11, which starts at the tick where sprint begins in mid-air. Same cause, the air
    acceleration lag (one substep or zero in the engine, one tick in vanilla), amplified by soul sand's per-block
    x0.4 once the box reaches a second block a tick earlier.

**Bugs found on the way.**
- Server flight changes were overwritten. The engine wrote its own flying flag back each frame, so a server
  abilities packet (/fly, gamemode, the test world's reset) never stuck. Now adopted as an external change.
- Harness:
  - the slime settle (onGround and motionY alternate on slime; settle by exact start height);
  - the test world kept a creative/flying player from an earlier probe (TestWorld resets to survival).

Test-world robustness: the persistent test world had a dead player, which blocked the runners. `TestWorld`
now respawns, closes the death screen, and sets the test player invulnerable with full food. It does this
through `disableDamage`, not creative mode, so survival movement is unchanged. Re-recording at the same
origin is byte-identical across sessions.

## Phase 14: slime landings, jump timing windows, naming cleanup (0.6.1, 2026-10-12)

**User decision.** The sprint air-acceleration difference (engine: from the instant sprint starts; vanilla: one
tick later) stays. Differences that come from the higher resolution itself are accepted; vanilla's underlying
mechanics must be kept.

**Slime scenarios never reached the slime (harness bug, found by the user watching the runs).**
- `slime_bounce` walked 8 ticks and stopped 0.08 blocks short of the platform edge. `slime_sneak_land` jumped and
  landed back on its platform. The Phase 13 bit-exact results for these two covered standing on stone only.
- Fixed: `slime_bounce` walks 14 ticks (off the edge at tick 10, lands on slime at tick 20, bounces 2.55, 1.97,
  1.53, ... then walks while bouncing). `slime_sneak_land` walks off and sneaks only once airborne (sneaking on
  the platform would stop at its edge), lands at tick 19 without bouncing, then releases sneak.
- Re-recorded in real vanilla, EXACT 240 Hz and HIGH_RES 960 Hz (all three slime scenarios):
  - gate A (VanillaModel vs real vanilla): 94/94, 62/62, 75/75 bit-exact;
  - gate C (GRID vs vanilla, 40–960 Hz) and Report 6: 0.0000;
  - gate L (live vs offline): bit-exact at both rates;
  - traceDiff live vs real vanilla: 0.0000 every tick; engine fallbacks 0.

**Jump timing windows (`gradlew jumpWindowTest`).** Flat sprint jump across a 3-block gap (a "4-block jump")
and a 2-block gap, sprint run-up from rest, one 80 ms space tap at time p, swept in 1 ms steps. Window = total
length of successful press times. Repeated from 20 start spots spread over one tick of run-up distance (in vanilla
the window depends on where the ticks fall relative to the edge).

| mode | 4-block window, ms (mean / min / max) | 3-block window, ms (mean / min / max) |
|---|---|---|
| VANILLA | 217.5 / 200 / 250 | 395.0 / 350 / 400 |
| HIGH_RES 60 Hz | 201.2 / 184 / 217 | 379.5 / 350 / 400 |
| HIGH_RES 240 Hz | 195.0 / 171 / 217 | 373.3 / 350 / 396 |
| HIGH_RES 960 Hz | 193.6 / 170 / 217 | 371.7 / 348 / 395 |
| EXACT (20, 60, 240, 960 Hz identical) | 193.0 / 169 / 217 | 371.0 / 347 / 395 |

- The latest press that still makes the jump is identical to vanilla's at every start spot and rate. Walking off
  the edge is committed per game tick in GRID, as in vanilla, so the engine allows no extra late jump.
- The earliest press is on average 24.5 ms later than vanilla's. Vanilla acts on a press at the next tick start,
  so a press up to one tick too early is carried forward to a tick where the player is further along; EXACT acts
  at the press, so an early press takes off early and falls short. That is vanilla's 0–50 ms input delay (mean
  25 ms) forgiving early presses, and it accounts for the whole difference: the window is ~24 ms (11 %) shorter on
  the 4-block jump and ~24 ms (6 %) shorter on the 3-block jump.
- Vanilla's window jumps between whole ticks (200 or 250 ms) depending on the run-up; EXACT's varies smoothly
  (169–217 ms) because its early edge is the true physical limit.
- Two harness artifacts fixed while building the test: with space held, the engine's mid-tick landing plus the
  immediate next jump hid the landing from a tick-boundary check (fix: an 80 ms tap, and landing detected by
  height as well as onGround).

**Naming cleanup.** Package `com.example.hrmove` → `com.tactile`; HR* classes → Tactile*; coremod name
HRMoveCore → TactileCore; log prefixes `[hrmove…]` → `[tactile…]`. The 0.6.0 jar shipped empty leftover folders
(`com/example/cbf`, `com/example/examplemod`, `assets/hrmovement`) from stale build output; a clean build removes
them (0.6.1 jar: `com/tactile` and `assets/tactile` only). Old jars (cbf, modid template, hrmovement 0.1.0/0.3.0,
tactile 0.4.0–0.6.0) moved to `archive/releases/`. The `hrmovement.cfg` migration is kept for upgrades.
Verified after the rename: all offline gates pass; edge landing 0/50 and 0/225 mismatches; in game (EXACT 240 Hz)
the hook installs through the renamed coremod, 0 fallbacks, gate L bit-exact and 0.0000 vs real vanilla on
sprint_jump_chain, sneak_edge, water_swim, slime_bounce and fly_basic.

**Slime standing camera vibration (0.6.2, user report).** Standing on slime (e.g. after releasing sneak), EXACT and
HIGH_RES at 20 and 960 Hz showed a small camera jitter. Cause: vanilla's idle bounce gives every other tick an
upward speed that is used up by the tick's end. Vanilla draws a straight line between equal tick heights; the
engine drew the in-tick curve, ~1 cm up and back at 10 Hz (the ladder mechanism of Phase 13). Fix: a grid tick
that starts on the ground and is predicted to end at the same height draws its height on vanilla's straight line
(jump apexes start in the air and keep their curve). `gradlew ladderViewCheck`, drawn height while standing on
slime, frames sampled through the EXACT render path: 0.0099 → 0.00000 at 20, 240 and 960 Hz (physics unchanged:
the box still makes vanilla's in-tick hump). Gates, jitterTest (view backtrack 0) and jumpLatencyCheck
(identical heights at every rate) unchanged.

## Phase 15: event-driven key input (0.7.0, 2026-10-12)

**User proposal.** Instead of polling the keys at 2 kHz, act only when a key actually changes.

**Implementation (`RawKeyInput`, input source EVENTS; AUTO now prefers it).** A hidden message-only window on its own
thread is registered for keyboard Raw Input and blocks in GetMessage; each key change is timestamped when the thread
wakes. No polling interval, no CPU between key events. Falls back to the poller when unavailable or when a movement
key is a mouse button.

**Privacy (the user's condition).**
- First attempt: registration without RIDEV_INPUTSINK, so that Windows itself would only deliver while the game is in
  front. Measured: no events at all (a message-only window is never the foreground window). So the source registers
  with RIDEV_INPUTSINK **only while the game has focus** and unregisters as soon as the game loses it (another window,
  a menu, chat). Each event is additionally read only if the game window is the foreground window at that instant
  (covers the moment between switching away and the game's next frame); otherwise it is counted and never read.
  Non-movement keys are discarded on arrival. RIDEV_NOLEGACY is not used, so normal key messages are unaffected.
- Verified in game: with the pause menu open, 20 injected pulses (to the game's own window) → the source received 0,
  and 0 were even delivered (the registration was gone). No event was ever delivered while unfocused in any run.

**Measured (`HRMOVE_INPUTPROBE=auto`, 630 injected F13 pulses of 1–66 ms at 60 / 144 / unlimited FPS, latency from
the OS accepting the event; two runs):**

| source | press latency p50 | p99 | max | missed | CPU (own thread) |
|---|---|---|---|---|---|
| key events | 0.21–0.24 ms | 0.47–0.71 ms | 0.55–1.36 ms | 0 / 1260 | 0.09–0.11 % of a core |
| poller 2 kHz | 0.36–0.40 ms | 0.91–1.03 ms | 0.95–2.19 ms | 1 / 1260 | 3.9–4.8 % of a core |

Keyboard scan, debounce and USB report interval come before the OS and are not included; with key events they are
the remaining limit on timing.

**End to end through the engine** (tap test, real OS key taps 3–100 ms, engine input "key events"): EXACT moves in
proportion to tap length and tracks the model as closely as the poller run of Phase 8 (10 ms: 0.0365 / 0.0351 vs
model 0.0364; 20 ms: 0.0789 / 0.0788 vs 0.0784; 100 ms: 0.4272 / 0.4237 vs 0.4257); HIGH_RES 60 Hz quantizes to
16.7 ms steps as before. No tap lost. Offline gates unchanged.

## Phase 16: pillaring and staircases (0.7.1, 2026-10-12)

**User request.** Building straight up needs space pressed again for every block (holding it re-jumps a little after
the landing); the user suspected this, and going up staircases, might behave differently in the mod.

**Pillaring, in game (`HRMOVE_PILLARPROBE=1 HRMOVE_INPUT=SCRIPTED`).** Looking down, jump, place a block under the
feet (real `onPlayerRightClick` at a tick start, as Minecraft handles clicks), land on it, jump again; 10 blocks.
Jump strategies: HOLD (space held), EARLY (released after takeoff, pressed again 60 ms before the landing), LATE
(pressed 20 ms after the landing). Placement at the first or the last tick start at which the feet clear the cell.
Engine presses are injected as key events at their real time; VANILLA sees them at the next tick start. Time = first
takeoff to the landing on the 10th block, from the engine's jump record (exact time within the tick; the model now
keeps `lastJumpPhase`).

| strategy | VANILLA | EXACT 960 | EXACT 20 | HIGH_RES 960 |
|---|---|---|---|---|
| HOLD | 9.90 ticks/block | 9.90 | 9.90 | 9.90 |
| EARLY (60 ms before landing) | 9.00 | 9.00 | 9.00 | 9.00 |
| LATE (20 ms after landing) | 9.90 | 9.37 | 9.37 | 9.38 |

- Every variant (24): 10 blocks climbed, no lost presses, no ghost blocks, no server corrections. Identical for first
  and last-moment placement.
- Holding space is slower than re-pressing in every mode (vanilla's 10-tick jump cooldown ends one tick after the
  9-tick landing), so the reason to re-press is preserved.
- Pressing before the landing: identical to vanilla. Pressing after it: vanilla waits for the next tick start (here
  30 ms), the engine jumps at the press. The same inherent difference as the jump window (Phase 14): no tick delay.
- Last-moment placement triggers the engine's replay (the bridging mechanism, Phase 11) once per block: the engine is
  already a little lower than the position the server checks the placement against. No effect on timing or height.
- Harness fixes on the way: an extra press scheduled after the final landing (counted an 11th jump), and the jump
  time hook recorded the start of the physics step (a whole tick at 20 Hz).

**Staircases (climb corpus: `staircase_hold`, `staircase_repress`, `staircase_walk_repress`).** A 1-block staircase,
sprinting with space held, sprinting and pressing space again on each landing (script generated from VanillaModel), and
walking with re-presses. Checked offline first that each run climbs (7, 8 and 12 steps).
- Gate A (VanillaModel vs real vanilla): 80/80, 80/80, 120/120 bit-exact.
- Gate L (live vs offline): bit-exact at EXACT 240 Hz and HIGH_RES 960 Hz.
- Live vs real vanilla: walking 0.0000; sprinting 0.033 / 0.035. First difference at the tick where sprint restarts in
  mid-air after the step's riser stopped it: the accepted air-sprint difference (Phase 14), nothing new.

## Phase 17: settings screen redesign (0.7.2, 2026-10-12)

**User request.** Most players want EXACT and nothing else; the mode and physics-rate controls on the first page
invite decision paralysis and suggest the mechanics change. Draft reviewed by the user first, then implemented with
their choices: an *Advanced settings...* page (not an in-place toggle), an ON/OFF main switch, the physics rate kept
adjustable with 960 Hz the default, and the ASYNC key-reading option renamed POLLER.

- First page: *TACTILE movement* ON/OFF (new `enabled` property; ON runs the selected mode, EXACT by default),
  *Status overlay*, *Chat notices* (ON/OFF), *Advanced settings...*.
- Advanced page: mode, physics rate, key reading, poll rate, tap latch. Poll rate is grayed out (dimmed label, not
  clickable, not saved) unless key reading is POLLER; the tap latch unless the mode is HIGH_RES. The check reads the
  other entry's current, unsaved value, so it follows changes immediately.
- F7 still cycles VANILLA → HIGH_RES → EXACT: VANILLA saves the switch OFF and keeps the selected mode; the others
  switch it ON and select themselves. The overlay shows "OFF (vanilla movement)" while switched off.
- Old configs: `inputSource=ASYNC` becomes POLLER on load; `HRMOVE_INPUT=ASYNC` is still accepted.
- Verified in game (`HRMOVE_UIPROBE=1`): screenshots of both pages (defaults: poll rate and latch grayed; HIGH_RES +
  POLLER: both active), switch ON/OFF and modes through the config-changed path (running mode EXACT / HIGH_RES /
  VANILLA as expected), F7 saves (VANILLA → enabled false, mode kept; HIGH_RES → enabled true, mode HIGH_RES), and
  every original value restored. Offline gates pass.

## Phase 18: the open discrepancies, investigated (0.7.3, 2026-10-12)

Each open item got a hypothesis, a prediction and a measurement before any change. New tools: `ViewProbe`
(`HRMOVE_VIEWPROBE=1`; real OS key presses, the drawn camera recorded every frame; `gradlew viewAnalysis` fits each
mode's display lag against the exact jump arc), `latency|latency20|latency960` variants (per-press timing through the
key source), and the engine's own record of each tap in the tap test.

D9-D12 are the item numbers of the discrepancy list reviewed with the project owner before this phase.

**D9. HIGH_RES 20 Hz felt delayed: confirmed and fixed.** Hypothesis: HIGH_RES drew the state one substep in the past.
Measured display lag (press to camera), before → after drawing HIGH_RES at the exact frame time (a throwaway
continuation of the current substep with its held input, as EXACT already did):

| mode | VANILLA | HIGH_RES 20 | HIGH_RES 60 | HIGH_RES 960 | EXACT 20 | EXACT 960 |
|---|---|---|---|---|---|---|
| before | 31.4 ms | 77.2 | 27.2 | 3.1 | 3.0 | 1.8 |
| after | 31.0 | 26.6 | 10.3 | 2.2 | 1.8 | 1.7 |

HIGH_RES's remaining delay is its input step (mean 25 ms at 20 Hz), by design. Engine-clock residual of the drawn arc
0.217 → 0.007. `HRMOVE_HIGHRES_DELAYED=1` keeps the old path for A/B.

**D10. EXACT 20 Hz drawn arc 3 % lower: a late key event, found and fixed.**
- On the engine clock the drawn arc matched the exact arc equally at 20 and 960 Hz (drawing is right).
- The difference came from single presses: the jump happened exactly at a tick boundary, 9.9 ms late.
- Per-press instrumentation located it: OS delivery is always fast (≤ 0.4 ms in 300+ presses), restarts do not matter, and the game thread collects promptly. In one press per run, at the same moment of the probe's schedule (trial 20), Windows' `GetRawInputData` took 9–12 ms to return. There was no GC, the key thread was at time-critical priority, and frames continued. The wake-up timestamp was right, but by hand-over time the engine had simulated past it. At 960 Hz that moved the press 9.4 ms late; at 20 Hz it moves it to the next tick boundary when one passes.
- Which system activity holds up that call is not identified.
- Fix: the key thread announces "an event from time t is being read" (a timestamp only, set only while the game is in front). Frames do not simulate past t. A tick boundary, where the movement is committed to the server, waits for it, up to 25 ms.
- Verified, same schedule:
  - EXACT 960: the natural stall (10.1 ms) moved the press 9.40 ms before the fix, 0.00 ms after.
  - Every read forced to stall 12 ms (`HRMOVE_KEYSTALL_MS=12`): 25 presses at EXACT 20 Hz, all entered at their own time (moved 0.00), including 4 whose stall crossed a tick boundary.
  - Diagnostics count tick waits.
- The key thread now also runs at time-critical priority (it sleeps until an event; no cost). This alone did not remove the stall.

**D11. HIGH_RES 20 Hz jitter standing on slime: not reproduced; vanilla's own camera tilt.** Two runs: drawn feet height
and eye height do not move at all in any mode (range 0.00000), including VANILLA. The only moving term is vanilla's
view-bobbing tilt (cameraPitch from onGround/motionY flipping each tick on slime), 0.155–0.157° in every mode including
VANILLA. If the user's impression was the 50 ms delay of D9, that is fixed.

**D12. Tap test 0–5 mm above the model: explained, nothing to fix.** Two causes, both confirmed. The model used one
start phase while real taps start at random phases (the phase spread alone is ±5–6 mm). The injected keys stay down
0.3–0.6 ms longer than requested (the engine's record of each tap). Modeling every tap with its own recorded start and
hold: residual 0.00000 blocks in every mode and tap length (HIGH_RES 60, EXACT 60, EXACT 240).

## Phase 19: using items inside the engine (0.8.0, 2026-10-12)

**Vanilla (EntityPlayerSP.onLivingUpdate 1.8.9).** While an item is in use (eating, drinking, drawing a bow, blocking
with a sword) and not riding: movementInput.moveStrafe and moveForward x0.2 (float), sprintToggleTimer = 0, and
neither sprint-start rule fires (!isUsingItem). The stored moveForward (next tick's "was forward high") is the scaled
value. Item use starts and stops in Minecraft.runTick's click handling, before the player update.

**Model.** `PlayerState.usingItem`, set at tick starts by the drivers (VanillaModel.tick, SubstepModel.tick/tickExact
from the input's new 'U' slot); `VanillaModel.itemUseFactor` applies the x0.2 and the timer reset in all three input
paths (vanilla, per-substep, EXACT pieces). The live engine reads `isUsingItem()` in its move hook (after the click
handling, before the tick's interval is simulated). The "using item" fallback is removed.

**Recording.** The recorder holds right-click from the script, equips the scenario's item (bread, bow with arrows,
diamond sword) and food level, and stores the client's *actual* isUsingItem per tick as the model input. Found while
building it: the test world's invulnerability (disableDamage) makes EntityPlayer.canEat refuse, so the first eating
recordings never ate (the recorder reported "item use differs from the script on 25 ticks"); eating scenarios now run
without it (peaceful, flat floor). Two harness fixes: gate A's reconstruction of the previous tick's forward flag
ignored the x0.2, and gate L took item use from the same row instead of the lead-shifted one.

**Item corpus:** eat_walk, eat_sprint (sprint ends while eating, resumes with the key held), eat_jump_doubletap (eat in
the air; double-tap while eating starts no sprint), bow_strafe_shoot (strafe while drawing, shoot, jump while drawing),
sword_block_jump (sprint-jump chain while blocking).
- Gate A (VanillaModel vs real vanilla): 60/60, 65/65, 65/65, 71/71, 75/75 bit-exact.
- Gate L (live vs offline): bit-exact for all five at EXACT 240 and HIGH_RES 960; no fallbacks.
- Live vs real vanilla: 0.0000 for eat_walk, eat_sprint, bow_strafe_shoot; eat_jump_doubletap 0.009 and
  sword_block_jump 0.047, first differing at the tick where sprint ends (eating starts in mid-air) or restarts
  (blocking stops in mid-air): the accepted air-sprint difference, which applies to sprint stopping in mid-air too.

## Phase 20: STEPPED replaces HIGH_RES; getting hit (0.8.1, 2026-10-12)

**STEPPED (user request: a simpler name, and any rate such as 25 or 35 Hz to line up jumps).** HIGH_RES is renamed
STEPPED and redesigned: the physics is EXACT's at the physics rate; only key changes are held to step boundaries,
multiples of 1/stepRateHz on the engine clock (any whole number 1-1920 Hz; slider 20-240; default 20). The keys at a
boundary are the state there, with presses since the previous boundary latched (`InputTimeline.stepped`), so a tap
between two steps lasts one step. Old configs (mode HIGH_RES) migrate to STEPPED with the old physics rate as the step
rate. The physics-rate tooltip now states what the rate does (evaluation frequency only; positions identical).

Gate S (200 random off-grid timelines):
- 20 Hz steps without the latch read the keys where vanilla does: STEPPED equals EXACT fed vanilla's per-tick key state, max difference 0.
- The STEPPED driver equals EXACT on the timeline converted to step boundaries (35 Hz, latch): 2e-13 (floating-point rounding).
- Measured, not gated: 35 Hz steps at 60/960 Hz vs 240 Hz physics differ in 1/200 runs, by up to 2.4 mm.

**New open item: a rare physics-rate dependence in EXACT itself.** The run above is a sprint-strafe into the face of a
1-block step with a jump pressed mid-tick, with the player against the wall. The sideways position after the tick
depends on the physics rate: x = 0.95382 at 20-120 Hz, 0.95495 at 240 Hz, 0.95557 at 480/960 Hz, 0.95573 at 1920 Hz.
This is systematic, unchanged when the jump moment shifts by ±1e-5 tick. Plain EXACT on 1000 raw random timelines
showed none (max 1.6e-12); STEPPED's synchronized timing produced the combination. Size ≤ 2.4 mm. Not yet explained.

**Getting hit (`HRMOVE_HITPROBE=1`).** Vanilla's own damage code on the integrated server: a zombie's hit (mob damage),
a player's hit (FakePlayer.attackTargetEntityWithCurrentItem) and a sprinting player's hit (extra knockback). The
knockback arrives as a velocity packet. 5 trials per mode, standing still.
- Found: EXACT and STEPPED pushed the player ~35 % too far (zombie/player 2.65-2.72 blocks vs vanilla 1.984;
  sprinting player 6.36-6.47 vs 4.939). Cause: an outside velocity restarted the grid only up to the next game-tick
  boundary; the knockback lifts the player before that boundary, so vanilla's ground friction (x0.546) on the first
  knockback tick was skipped.
- Fix: an outside velocity is applied at its arrival like a jump (`SubstepModel.overrideVelocity`): the grid tick in
  progress ends (contacts committed), the velocity is set, and a full grid tick starts there. The live engine queues it
  with its arrival time and splits the step at that time, at any physics rate. Offline: distance pushed 2.059925221 in
  vanilla and in EXACT at 20/240/960 Hz with the packet at +0.00/+0.30/+0.77 of a tick (identical to 9 digits).
- In game after the fix:

| hit | VANILLA pushed | EXACT pushed | STEPPED 20 pushed | arrival to first drawn movement (V / E / S) |
|---|---|---|---|---|
| zombie | 1.984094 (*) | 1.984094 | 1.984094 (*) | 17.6 / 2.8 / 3.0 ms |
| player | 1.984094 | 1.984094 | 1.984094 | 24.0 / 3.2 / 3.0 ms |
| sprinting player | 4.939078 | 4.939078 | 4.939078 | 25.9 / 3.2 / 2.8 ms |

(*) One zombie trial in VANILLA and one in STEPPED pushed 0.988: the same in vanilla, so a probe artifact (the hit
landing while the player had not settled), not a mod difference. Peak heights differ slightly only by sampling (tick
ends see the arc at a different phase when it starts mid-tick); the offline trajectory is identical.

## Phase 21: 1.0.0 (2026-10-13)

**The open rate dependence (Phase 20), explained and fixed.** In the found case, a jump pressed mid-tick re-phased the
grid at the moment the sprinting player hit a wall. The re-phase committed the wall contact, but the sprint rules had
already run at that piece's start with the old contact. So the jump kept the sprint-jump boost, and sprint ended only
at the next piece start, whose time depends on the physics rate: x 0.95382 at 20-120 Hz, 0.95573 at 1920 Hz.
Vanilla's order at a tick start is sprint rules (with the last move's contacts), then the jump. Fix:
`SubstepModel.restartGridNow` commits the contacts, then (EXACT) runs the sprint rules against them, then the caller's
jump or fly push. After the fix:
- x = 0.955845 at every rate from 20 to 1920 Hz.
- Gate S: 0/200 runs rate-dependent (max 1.3e-12). This is now a gated check.
- EXACT on 1000 raw random timelines: max 1.6e-12. All other gates pass, including the bit-exact recorded corpus.

**Fail-safe.**
- Every engine entry point (tick handler, frame handler, the move hook) is guarded. The first unexpected error logs
  the stack trace, restores the frame's swapped render position, hands control to vanilla for the session, and shows
  one chat message.
- A key source whose thread stops is replaced by per-frame key reading.
- Verified in game with forced errors (`HRMOVE_FAULTTEST`):
  - tick / frame / move: the game kept running, the engine was off and showed the error status, and the player walked
    12.95 blocks in 3 s under vanilla.
  - keys (the key-event thread throws on its first event): the engine stayed active with input "frame (key reading
    stopped)".

**Release jar.**
- `gradlew build` now also produces `build/release/tactile-<version>.jar` (119 KB). It is the reobfuscated mod without
  the developer tools (harness, probes, recorder, test world, test fixtures).
- ClientSetup reaches the new `DevTools` class by name and skips it when absent.
- jdeps finds no reference from the release jar's classes to a missing TACTILE class (51 internal references, 0
  missing).
- The development jar in `build/libs` keeps the tools, because the development client loads the mod from it. A first
  attempt that trimmed that jar left the probes unable to start, so the game stayed on the title screen.

**Also:** chat-notice tooltip wording; `mcmod.info` description, author (fallingsword) and credits; MIT license;
README, CHANGELOG, docs/VERIFICATION.md; USAGE.md reduced to configuration and developer tools.

**Validation run on the 1.0.0 build (2026-10-13).** All data in docs/VERIFICATION.md comes from this session.
- Recorded the 34-scenario corpus five times: `run/v100-vanilla`, `v100-exact240`, `v100-exact960`,
  `v100-stepped20`, `v100-stepped35`. 0 fallbacks in every engine run.
- Gate A on v100-vanilla: 34/34, 2621/2621 ticks bit-exact. All offline gates pass.
- Gate L bit-exact 34/34 for exact240, exact960 and stepped20.
- traceDiff vs vanilla, identical for exact240/exact960/stepped20: 0.0000 in 28/34; soulsand_walk 0.1065,
  sword_block_jump 0.0481, staircase_repress 0.0349, staircase_hold 0.0334, eat_jump_doubletap 0.0094 (air sprint),
  jump_onto_block 0.0059 (ledge corner). stepped35 differs by design (max 1.254).
- Engine cost in game: 3.2 us per substep at 960 Hz, 5.5 us at 240 Hz; engine per frame p50 6 / p99 25 / max 214 us;
  frame p50 1.37 ms.
- Probes: hits identical to vanilla in every trial (first drawn movement 27-40 ms vanilla, about 3 ms engine);
  pillar 9.90 / 9.00 / 9.90 (vanilla hold/early/late) vs 9.90 / 9.00 / 9.37 (engine); placement 55/55, 0 ghosts;
  bridging identical at every lead; flight hand-overs 52, no false flight; UI probe correct; fault tests as above.
- Focus probes: jump display delay VANILLA 30.6 ms, STEPPED 20/35/60 26.5/13.7/7.6 ms, EXACT 1.8 ms; key events
  0/630 missed, p50 0.28-0.30 ms, 0.17 % of a core (poller 4.6 %); privacy check 0 of 20 delivered; per-tap
  residual 0.
- The release jar now also carries LICENSE (120,150 bytes).

## Phase 22: 1.0.1, chat-message setting (2026-10-13)

**User bug report:** the chat-message switch did not work. Cause: F7's "Movement: ..." message ignored `chatMessages` (the engine's own state messages already respected it). Fix: F7 prints only when chat messages are on. The fault message (engine switched off after an internal error) stays unconditional.
- UiProbe now presses F7 through the real key handler with chat messages off and on: 0 and 1 chat lines added, and the mode changed both times.
