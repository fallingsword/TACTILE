# TACTILE verification report

This report explains how TACTILE was compared with the unmodified game, what was measured, and every known
difference. All numbers in the results sections come from one test session on 2026-10-13 with the 1.0.0 build,
unless a section says otherwise. Later additions, up to version 1.2.0, give their own date and version; the recorded
scenarios of section 5 were recorded again with the 1.1.0 build on 2026-11-12.

## Summary

TACTILE lets your movement keys take effect the moment you press them, instead of waiting for Minecraft's next game
tick. The question this report answers is: **does it change anything else?**

The short answer is no: with the same key timing, TACTILE now gives exactly vanilla's movement.

- We recorded the real, unmodified game doing 34 different things: walking, sprinting, jumping, sneaking at edges,
  stairs, water, lava, ladders, vines, soul sand, slime, cobwebs, creative flight, eating, bows and sword blocking.
  We then played exactly the same key presses with TACTILE switched on. In all 34 the result was identical to the last
  digit (since 1.1.0; in 1.0.x, six differed by 6 mm to 11 cm, because a change of sprint in mid-air took effect
  sooner than in vanilla).
- Getting hit by a zombie or a player pushes you exactly as far as in vanilla (to six decimal places), and you see the
  push start about 3 ms after it arrives instead of 27-40 ms.
- When you press jump, you see the jump start about 2 ms later. In vanilla it is about 30 ms on average, and up to
  50 ms.
- Short taps are never lost. In vanilla, a tap that starts and ends between two ticks is ignored.
- Pressing as early as possible never gets you anything vanilla's best timing could not. For example, building
  straight up is never faster than vanilla's fastest rate, and the latest moment you can press jump for a long jump
  is exactly the same as in vanilla.
- Key reading only happens while the game window is in front. With the pause menu open, 20 simulated key presses
  reached TACTILE 0 times.
- Servers with strict movement checks recompute every 50 ms tick of your movement with vanilla's rules, so a key
  pressed between two ticks can make them pull you back. Section 13 measures this and the option that avoids it for
  slime and knockback.

The rest of this report is written for technical readers, but it does not assume you know how Minecraft's movement
code works.

## Contents

1. [How movement works in Minecraft 1.8.9](#1-how-movement-works-in-minecraft-189)
2. [What TACTILE changes](#2-what-tactile-changes)
3. [How it was verified](#3-how-it-was-verified)
4. [Offline checks](#4-offline-checks)
5. [Recorded scenarios](#5-recorded-scenarios)
6. [Other situations tested in the game](#6-other-situations-tested-in-the-game)
7. [Timing](#7-timing)
8. [Known differences](#8-known-differences)
9. [Differences in earlier versions, and fixes](#9-differences-in-earlier-versions-and-fixes)
10. [Performance](#10-performance)
11. [Privacy checks](#11-privacy-checks)
12. [Fail-safe tests](#12-fail-safe-tests)
13. [Server movement checks](#13-server-movement-checks)
14. [Limits of this verification](#14-limits-of-this-verification)
15. [Reproducing the results](#15-reproducing-the-results)

## 1. How movement works in Minecraft 1.8.9

The game updates the world 20 times per second. Each update is a *tick* of 50 ms. In every tick, the client:

1. reads which movement keys are held at that moment;
2. updates the player's speed from them (acceleration, friction, gravity);
3. moves the player by that speed, resolving collisions with blocks;
4. records the contacts that result: whether the player is standing on the ground, touching a wall, and so on.

The next tick starts from those values. Two consequences matter here:

- A key pressed between two ticks has no effect until the next tick reads it, which is 0 to 50 ms later (25 ms on
  average).
- A key pressed and released between two ticks is never seen at all.

Between ticks, the game draws frames by blending the player's position from the previous tick to the current one.
Because each tick calculates the next 50 ms of movement at once, the game knows where you will be up to one tick
before the screen shows you there. The blending shows that movement during the 50 ms it covers, so it does not delay
the start of a movement. The delay you feel after a key press comes from waiting for the next tick to read the key.
The gap does show at events: in the game, a landing counts from the tick, while the screen still shows the player
coming down for up to one more tick (section 2 has the measurement).

Distances in this report are in blocks. One block is one meter in the game.

## 2. What TACTILE changes

TACTILE keeps every rule of that update and changes only the timing:

1. **Key times.** On Windows, each movement-key change arrives as an event with the time it happened. Section 11
   and the README's privacy section explain how this access is limited.
2. **Movement between ticks.** For a given set of held keys, the per-tick update of speed and position can be written
   as an exact formula over time. TACTILE evaluates that formula at whatever moment it needs: the time of a key
   change, the time a frame is drawn, and regular checkpoints (960 per second). A key change therefore acts
   at its own time. When no key changes during a tick, the result at the end of the tick is exactly the one vanilla's
   update gives.
3. **Contacts on vanilla's schedule.** Collisions with blocks are resolved by vanilla's own collision code, and the
   resulting contacts are committed when a tick ends, as in vanilla. A jump, a knockback or a flight push starts a new
   tick at the moment it happens, so its path is vanilla's path, started at that moment.
4. **Drawing.** The camera shows the player's position at the moment each frame is drawn.

**EXACT** mode applies each key change at its own time. **STEPPED** mode applies it at the next step of a chosen
rate (1 to 1920 steps per second). The movement calculation is the same in both. **VANILLA** mode leaves the game
untouched.

The *physics rate* is how often TACTILE brings its saved state up to date between ticks. Section 4 shows that it does
not change where the player ends up, and the next section explains why it cannot. Up to 1.0.1 it was a setting
(default 960 per second); since 1.0.2 it is fixed at 960, the rate used for most of the tests in this report. The tests still
vary it internally to check that it has no effect.

### In detail: ticks, jumps, collisions and drawing

This section is for readers who want to know exactly where TACTILE's movement could differ from vanilla's.

**Collision windows.** TACTILE keeps vanilla's 50 ms ticks for movement. Call each one a *collision window*. A window
starts from the player's position and contacts at its start, and ends 50 ms later with one call to vanilla's own
collision code on the window's whole movement. Like vanilla, that code resolves the vertical part of the move first,
then the horizontal parts, and clips the move against every block box along the way, so a long move cannot pass
through a block. The contacts it finds (standing, touching a wall) are committed at the end of the window, as in
vanilla.

**Checkpoints and frames.** Between the start and end of a window, every checkpoint and every drawn frame also runs
vanilla's collision code. Each of these checks covers the whole movement from the window's start, never from the
previous check, and none of them commits contacts. The end of the window therefore gets the same result however many
checks came before it, which is why the physics rate cannot change movement. Each frame gets its own check at its own
moment. On every axis where the window's collision will stop the player (a wall, a floor, a ceiling), the drawn
position never passes that stopping point, so the player is not drawn inside a wall. On an axis the collision does not
limit, the drawn position follows the straight path between the window's start and end. So when a jump clears the top
corner of a block thanks to the vertical-first order, the drawn position can briefly cut through that corner, exactly
as vanilla's own blended drawing does. This is how the code is built rather than a separate measurement; the related
measurements are the smoothness tests (`jitterTest`, `ladderViewCheck`, at 240 and 960 checks per second), which found
no backward movement of the drawn position at edges, steps and ladders.

**A jump pressed mid-tick.** In vanilla a jump can only happen at a tick start. In TACTILE, a jump at any moment does
the following:
1. The window in progress ends at the press. Vanilla's collision code runs on the movement since that window began,
   and its contacts are committed.
2. Sprint rules run against those contacts, then the jump is applied, in the same order as at a vanilla tick start.
3. A new window starts at the press. From then on, windows end every 50 ms counted from the jump.

After the jump, the windows line up with the jump exactly as vanilla's ticks line up with a jump made at a tick start,
so the jump's path and every collision along it are vanilla's, started at the press. Section 6 includes a test of
vanilla's corner clip (clearing the top corner of a block thanks to the vertical-first order), which TACTILE
reproduces in every case, including with mid-tick presses. A knockback or a flight push (starting to fly up or down)
starts a new window in the same way, and so does a movement key pressed while standing still (since 1.2.0; see
below). Windows stay aligned to the latest such event until the next one.

Step 1 is the only part of a mid-tick jump with no vanilla equivalent: vanilla never ends a tick early. It covers at
most 50 ms of movement, collision-checked by the same rules. It is where the earlier timing of a press takes effect.

**Starting from standing still.** Since 1.2.0, a movement key pressed while the player stands completely still on the
ground (no horizontal speed) starts a new window at the press, as a jump does. The window in progress ends there with
its contacts committed; nothing has moved during it, so nothing changes but the timing of later windows. A run from
standstill then has the same windows relative to its own start, wherever that start falls between two game ticks. In
vanilla the run's first key acts at a tick start, so its ticks are lined up with the run in the same way. Up to 1.1.0
the windows kept the game's tick phase (or the last jump's), so the same run from the same spot could get anywhere
from 0 to 49 ms of coyote time at an edge, depending on the millisecond the run began (section 7, "Coyote time at an
edge").

**Other key changes during a window.** A key that changes in the middle of a window while the player is moving
(anything other than a jump) does not start a new window. The movement formula changes from that moment, and the
window still ends with one collision check on its whole movement. Vanilla has no equivalent either, since it would
apply the key at the next tick; this is the purpose of the mod. A version in which every key change started a window
was tested and rejected: a 1 ms tap of any key just before an edge then bought the largest possible coyote time every
time (section 7).

**Air acceleration after a sprint change.** When sprint starts or stops in mid-air (for example because the player
runs into a wall), vanilla changes the air acceleration only from the next tick: it sets the value after a tick's move,
for use during the next one. Since 1.1.0 TACTILE follows the same order: each window's air acceleration is fixed when
the window begins. Up to 1.0.3 TACTILE changed it at once, which was the cause of every difference found in the
recorded scenarios (D1 in section 9). A legacy setting, *Instant air control*, brings that behavior back. Apart from
the timing points above, the rules are vanilla's.

**Rules that keep a mid-tick jump fair.** A mid-tick jump is allowed only if the player was on the ground both when
the current window began and at the moment of the press. With only the first condition, a player who walked off an
edge would get up to a tick of extra time to jump. With only the second, a press could fire at the instant of
touchdown, sooner than vanilla's jump rhythm allows. Vanilla's jump cooldown also still applies.

**What stays on the game clock.** Everything vanilla does at its own ticks that is not the player's movement keeps
the game's 20 ticks per second: sending the player's position to the server, item use, and the world itself. When a
game tick falls inside a collision window, the server receives the player's position at that moment, after collision
with the movement so far. Since 1.1.0 the packet's on-ground flag follows vanilla's own rule for the movement since the
last packet (the downward part of the attempted move was stopped), rather than the contacts committed at the end of
the last window; section 13 explains why.

**Drawing.** Vanilla draws a blend between the last two tick results, so its game state runs up to one tick ahead
of the screen. In the 1.0.0 test session, at about 1,000 frames per second, vanilla's game counted each landing about
50 ms (49.4 ms on average, 8 jumps) before the screen showed the player reaching the floor; at the moment it counted
the landing, the screen still showed the player 0.10 blocks above it. TACTILE draws the player's real-time position
at the moment of each frame instead, so the game state and the screen agree. For each frame, TACTILE briefly gives
vanilla's renderer the current position as both ends of its blend, and restores the real values right after the
frame, so game logic never sees the substitution.

## 3. How it was verified

### Three sources compared

- **Vanilla, recorded in the real game.** A recorder plays scripted key sequences in an unmodified client (TACTILE
  switched off) and records the player's position, speed and contacts after every tick.
- **A reference model of vanilla.** A program that reproduces vanilla's movement rules outside the game. It is
  checked against the recordings: it must reproduce every recorded tick of the real game exactly, to the last bit.
- **TACTILE**, in two forms:
  - the in-game engine, recorded in the real game with the same scripted key sequences;
  - the same engine code run outside the game, where it can be tested with thousands of key sequences, at any timing
    and at every physics rate.

### Two kinds of test

- **Same key timing.** The scripted key sequences change keys only at the start of a tick, which is when vanilla
  reads them. With identical timing, TACTILE has no timing advantage to apply, so it must produce vanilla's result.
  This tests the movement rules themselves.
- **Finer key timing.** Separate tests then measure what changes when keys are pressed between ticks, which is the
  purpose of the mod.

### Acceptance rule

- **Not acceptable:** any difference from vanilla's movement rules. With the same key timing, positions, speeds,
  contacts and timers must equal vanilla's, apart from floating-point rounding (below 1e-9 blocks).
- **Acceptable:** differences that come only from the finer timing (a key press or a contact change taking effect
  sooner than the next tick). Up to 1.0.3, one more case was accepted by the project owner, with a measured upper
  bound: D1 in section 9, resolved in 1.1.0.

### Test setup

- Windows 11, the development machine, Minecraft 1.8.9 with Forge 11.15.1.2318, in a flat test world generated by
  the recorder.
- All runs in sections 4 to 11 used the 1.0.0 build, except where a section names another version and date: the
  corner-clip test in section 6 (1.0.1), the re-examination of D2 in section 9, the 1.0.3 tests in section 12, and the
  1.1.0 recordings and tests in sections 5, 6 and 12 (2026-11-12). The movement code is the same from 1.0.0 to 1.0.3;
  1.1.0 changed the air-acceleration order (D1) and the on-ground flag sent to the server. 1.2.0 changed only when a
  window starts after standing still; it was tested offline (section 7, "Coyote time at an edge", 2026-11-13), and
  every offline check of section 4 was rerun on it with unchanged results. With inputs at tick starts, as in the
  recorded scenarios, a window already starts at every such press, so nothing changes there: replaying the recordings
  offline gave 0.0000 from vanilla in 34 of 34, as before. The engine in the development
  build used for recording is the same code as in the release jar; the release jar only leaves out the test tools
  (section 14).
- Simulated key presses for the timing tests were injected through Windows while the game window was in front.
  Latencies are measured from the moment Windows received the press, so the keyboard's own delay is not included.

### Raw data

The raw recordings are not published, because the recording logs contain details of the test machine (user name and
file paths). This report gives the aggregated results. Section 15 explains how to produce the recordings again.

## 4. Offline checks

These run outside the game (`gradlew movementTests`, and the other tasks named in section 15). All passed on the
1.0.0 code, and again on the 1.1.0 code (2026-11-12) with the same results.

| Check | What it shows | Result |
|---|---|---|
| Reference model vs real vanilla (gate A) | The model reproduces the recorded game. | 34 of 34 scenarios, 2,621 of 2,621 ticks identical |
| Engine at one step per tick (gate B) | With one step per tick, the engine is the reference model. | Identical on every recorded input |
| In-between motion (gate C) | Moving in many small steps ends exactly where one full tick would. | Max relative error 3.7e-14 on land; 0 in water, lava and flight |
| Jumps and short taps (gates D, E) | A jump starts on the press; a tap shorter than one step is still counted. | Pass |
| Mid-tick input (gate F) | A key pressed mid-tick acts from the moment it was detected, not from the next tick. | Pass |
| EXACT and the physics rate (gate G) | EXACT's result does not depend on the physics rate. | 200 random key sequences at 20, 60, 240 and 960 per second: max difference 1.6e-12 blocks. Separately, 1,000 sequences: max 1.6e-12 |
| STEPPED at 20 steps per second (gate S) | Keys are read exactly where vanilla reads them. | Max difference 0 |
| STEPPED vs EXACT (gate S) | STEPPED is EXACT with key changes moved to the step boundaries. | Max difference 2e-13 |
| STEPPED and the physics rate (gate S) | STEPPED's result does not depend on the physics rate. | 0 of 200 random sequences differ; max 1.3e-12 |
| Timing accuracy | EXACT against a reference at 15,360 steps per second, 300 random sequences. | Same jump count in 300 of 300; all physics rates within 1.5e-12 of each other |

A difference of 1e-12 blocks is a billionth of a millimeter: floating-point rounding, not a change in behavior.

## 5. Recorded scenarios

Each scenario was recorded in the game with VANILLA (1.0.0 session; vanilla's movement is unchanged since), and
with the engine on the 1.1.0 build (2026-11-12): EXACT at physics rates 240 and 960, STEPPED at 20 steps per second,
and EXACT 960 with the option *Server-safe slime and knockback*. STEPPED at 35 steps per second was recorded with 1.0.0
(see below). The columns are:

- **Model** (gate A): the reference model reproduces the recorded vanilla run.
- **Engine** (gate L): the in-game engine produced exactly what the offline engine predicts for the same input. Run
  for every engine recording.
- **vs vanilla**: the largest distance between TACTILE's and vanilla's position at any tick end, in blocks. All four
  engine recordings gave the same value in every scenario, so one column is shown. **1.0.x** shows the 1.0.0 value
  where it was not 0.

| Scenario | What it does | Ticks | Model | Engine | vs vanilla | 1.0.x |
|---|---|---|---|---|---|---|
| stand | Stand still and settle on the ground | 30 | identical | identical | 0 | |
| walk_stop | Walk, release, slide to a stop | 50 | identical | identical | 0 | |
| sprint_stop | Sprint, release, stop | 60 | identical | identical | 0 | |
| diag_strafe | Walk diagonally while strafing | 55 | identical | identical | 0 | |
| jump_in_place | Jump on the spot | 28 | identical | identical | 0 | |
| sprint_jump_chain | Repeated sprint jumps | 90 | identical | identical | 0 | |
| walk_off_ledge | Walk off a block edge and fall | 50 | identical | identical | 0 | |
| wall_scrape | Sprint along and into a wall | 50 | identical | identical | 0 | |
| slab_steps | Walk up half-slabs | 50 | identical | identical | 0 | |
| sneak_edge | Sneak to the edge of a block | 75 | identical | identical | 0 | |
| jump_onto_block | Jump up onto a one-block step | 44 | identical | identical | 0 | 0.0059 (D1) |
| head_hitter | Jump under a low ceiling | 70 | identical | identical | 0 | |
| turning_jumps | Sprint jumps while turning | 75 | identical | identical | 0 | |
| double_tap_sprint | Start sprinting by double-tapping forward | 38 | identical | identical | 0 | |
| water_swim | Swim through water | 130 | identical | identical | 0 | |
| water_tread | Stay afloat in water | 97 | identical | identical | 0 | |
| lava_swim | Swim through lava | 140 | identical | identical | 0 | |
| ladder_climb | Climb and descend a ladder | 102 | identical | identical | 0 | |
| ladder_jump | Jump onto a ladder | 65 | identical | identical | 0 | |
| vine_climb | Climb vines | 60 | identical | identical | 0 | |
| soulsand_walk | Sprint and jump on soul sand | 65 | identical | identical | 0 | 0.1065 (D1) |
| slime_bounce | Fall onto slime and bounce | 94 | identical | identical | 0 | |
| slime_walk_jump | Walk off onto slime, jump on it | 75 | identical | identical | 0 | |
| slime_sneak_land | Land on slime while sneaking (no bounce) | 62 | identical | identical | 0 | |
| web_walk | Walk through cobwebs | 110 | identical | identical | 0 | |
| fly_basic | Creative flight: up, down, across | 96 | identical | identical | 0 | |
| staircase_hold | Sprint-jump up a staircase, jump held | 80 | identical | identical | 0 | 0.0334 (D1) |
| staircase_repress | Sprint-jump up a staircase, jump pressed each time | 80 | identical | identical | 0 | 0.0349 (D1) |
| staircase_walk_repress | Walk up a staircase, jump pressed each time | 120 | identical | identical | 0 | |
| eat_walk | Eat while walking | 60 | identical | identical | 0 | |
| eat_sprint | Eat while sprinting (sprint ends), then resume | 65 | identical | identical | 0 | |
| eat_jump_doubletap | Start eating in the air; double-tap forward while eating | 65 | identical | identical | 0 | 0.0094 (D1) |
| bow_strafe_shoot | Strafe while drawing a bow, shoot, jump while drawing | 71 | identical | identical | 0 | |
| sword_block_jump | Sprint-jump chain while blocking with a sword | 75 | identical | identical | 0 | 0.0481 (D1) |

"Identical" means every recorded value (position, speed, contacts) is equal to the last bit in every tick.

**STEPPED at 35 steps per second** (1.0.0) differs from vanilla in most scenarios (largest 1.25 blocks, in staircase_hold).
This is expected: at 35 steps per second a key change takes effect at a different moment than vanilla's tick, so the
player follows a different route. The offline checks in section 4 show that STEPPED applies the same movement rules
as EXACT.

**Hand-overs during recording.** Over the four 1.1.0 engine runs there were 0 hand-overs to vanilla. With the
server-safe option on, the engine switched to vanilla tick timing 3 times, once in each slime scenario, and the motion
it took over from the server emulation differed from its own by 0 (with keys at tick starts, the two are the same). The engine logged the
recorder's own teleports between scenarios as outside position changes, as intended; none fell inside a recorded tick.

## 6. Other situations tested in the game

### Getting hit

Vanilla's own damage code, 5 trials per hit type and mode, standing still. "First drawn movement" is the time from
the knockback's arrival to the first frame that shows the player moving.

| Hit | VANILLA pushed | EXACT pushed | STEPPED 20 pushed | First drawn movement (VANILLA / EXACT / STEPPED) |
|---|---|---|---|---|
| Zombie | 1.984094 | 1.984094 | 1.984094 | 27.5 / 3.2 / 2.9 ms |
| Player | 1.984094 | 1.984094 | 1.984094 | 37.2 / 3.0 / 3.0 ms |
| Sprinting player | 4.939078 | 4.939078 | 4.939078 | 39.5 / 2.8 / 2.9 ms |

Every trial in every mode pushed the same distance. Peak heights differ by up to 3 mm, only because the knockback
starts mid-tick and tick ends sample the arc at a different point; the path itself is vanilla's.

With the option *Server-safe slime and knockback* on (1.1.0, 2026-11-12), a knockback is applied from the last game
tick instead, as vanilla's next packet expects (section 13). Every trial again pushed exactly vanilla's distance
(1.984094 and 4.939078 blocks), and the first drawn movement came 1.8 to 1.9 ms after arrival in EXACT and STEPPED
(VANILLA in the same run: 27.2 to 45.0 ms).

### Building straight up (pillaring)

Jump and place a block under yourself, 10 blocks high. "Early" presses jump just before landing, "late" presses it
just after. The table shows game ticks per block. EXACT ran at physics rates 960 and 20 with the same result; STEPPED
ran at 960 steps per second.

| Jump press | VANILLA | EXACT | STEPPED |
|---|---|---|---|
| Held | 9.90 | 9.90 | 9.90 |
| Early | 9.00 | 9.00 | 9.00 |
| Late | 9.90 | 9.37 | 9.38 |

All runs: 10 of 10 jumps, 0 lost presses, 0 ghost blocks, 0 position corrections from the server. With late presses
TACTILE jumps at the press instead of at the next tick, so it is faster than vanilla's late timing, but never faster
than vanilla's best timing (9.00). See "Earlier response to presses" in section 8.

### Placing blocks while moving, and bridging

- Placing blocks while moving: 55 of 55 placed in both VANILLA and EXACT, 0 ghost blocks (blocks the client shows but
  the server removes).
- Bridging backwards at six placement timings: VANILLA and EXACT gave identical results at every timing (same blocks
  placed, same distance, same falls), with 0 backward snaps.

### Landing near an edge and jumping again

Offline, against the reference model:
- Holding forward, sprint and jump towards the end of a platform, from 50 start points: the number of jumps matched
  vanilla in 50 of 50.
- Landing near an edge, then one jump press at 9 timings around the touchdown, 225 combinations: 0 jumps lost where
  vanilla jumps, 0 extra jumps where vanilla does not.

### Clearing the corner of a block (corner clip)

Because vanilla resolves each tick's move vertically first, a jump that rises past a block's top within one tick
clears its corner even where a straight line between the tick positions would have hit the block. Offline (run
2026-11-10, on the engine code of 1.0.1, which has the same movement code as 1.0.0): sprint-jumps at a one-block step
from 3,000 run-up positions, 1 mm apart. A "corner clip" counts a tick that starts below the top and in front of the
face and ends above the top and past it, where a straight line between the two would have crossed the face below the
top.

| Jump | Ended on top of the step | Corner clips | Same outcome as vanilla |
|---|---|---|---|
| VANILLA, at a tick start | 2,349 | 915 | |
| EXACT at 20, 240 and 960 per second, at a tick start | 2,349 | 915 | 3,000 of 3,000, including every corner clip |
| EXACT 960, pressed 0.1 to 0.9 of a tick after a tick start | 2,349 at every timing | 920-929 | 3,000 of 3,000 at every timing |

For the mid-tick presses, the run-up was shifted back by the extra sprint distance so each jump starts from the same
spot as the vanilla jump it is compared with. The corner-clip count varies by about 1 % because the test observes
positions on the game clock, which no longer coincides with the jump's own windows; whether the jump ends on top is
identical. In 1.0.x, end positions differed by up to 3.6 cm in runs that hit the step's face: that was the mid-air
sprint difference D1, and it never changed the outcome. Since 1.1.0 (rerun 2026-11-12) the end positions are identical
too (largest difference 0.0000 at all three physics rates).

### Hand-overs and settings

- **Hand-over to vanilla and back:** sprint-jumping with jump held across alternating soul sand and stone, with
  control switched between TACTILE and vanilla 52 times. Creative flight was never switched on by mistake.
- **Settings screen and keys:** every switch changes the running mode as expected, F7 cycles the modes and saves the
  choice, and the test restored the original settings afterwards.

### Earlier versions

The following were verified on earlier versions and not repeated for 1.0.0. The code involved has not changed since,
and the recorded scenarios above exercise the same movement code.
- Vanilla potion effects (speed, slowness, jump boost, blindness, night vision) and low hunger: identical to vanilla
  (0.4.1).
- Hand-over camera: no backward jump of the camera when control passes between TACTILE and vanilla (0.4.1).

## 7. Timing

### Key reading

630 simulated presses of 1 to 67 ms, at frame rates of 60, 144 and about 260 frames per second. Latency is the time
from Windows receiving the press to TACTILE recording it.

| Key source | Missed | Press latency p50 | p99 | max |
|---|---|---|---|---|
| Key events (default) | 0 of 630 | 0.28-0.30 ms | 0.47-0.57 ms | 0.76 ms |
| Poller, 2000 per second | 1 of 630 | 0.31-0.35 ms | 0.80-0.97 ms | 1.34 ms |
| Once per frame (last fallback), 60 FPS | 43-45 % | 8-20 ms | 16-32 ms | 32 ms |

The key-event source received every press, including 1 ms taps. For comparison, vanilla's own movement update never
saw 93 % of presses of 1 to 4 ms, 78 % of 8 ms presses, 70 % of 16 ms presses and 31 % of 33 ms presses, because they
started and ended between two ticks.

In rare cases Windows takes about 10 ms to hand a key event over. TACTILE knows an event is on its way and holds the
movement for it, so the press still takes effect at its own time. In the jump-display test below, this happened in 1
of 40 presses (11.7 ms wait).

### What you see after pressing jump

8 jumps per mode, measured from the press to the camera. "First rise" is the first drawn frame with the camera higher
than before. "Delay" compares the drawn jump with an ideal jump that starts at the moment of the press.

| Mode | First rise | Delay, mean (range) |
|---|---|---|
| VANILLA | 30.5 ms | 30.6 ms (9.2-49.4) |
| STEPPED, 20 steps per second | 28.9 ms | 26.5 ms (9.8-41.2) |
| STEPPED, 35 steps per second | 15.1 ms | 13.7 ms (2.5-20.7) |
| STEPPED, 60 steps per second | 9.0 ms | 7.6 ms (2.2-17.4) |
| EXACT, physics rate 20 | 4.5 ms | 1.8 ms (1.7-2.3) |
| EXACT, physics rate 960 (fixed since 1.0.2) | 3.2 ms | 1.8 ms (1.7-1.9) |

The remaining 1.8 ms in EXACT has two parts, measured on the engine's own clock in all 16 EXACT jumps:
- about 0.25 ms for the key press to reach TACTILE;
- about 1.5 ms between the start of a frame, when the position it shows is computed, and the moment the test recorded
  the frame, after the world had been drawn. This part follows the time taken to draw each frame (1.6 to 1.7 ms per
  frame in this test). Vanilla also computes each frame's position when the frame starts.

The time for a finished image to reach the screen is not part of this measurement, in any mode. In STEPPED the delay
is mainly the wait for the next step, on average half a step.

Standing on slime (a reported camera vibration): the camera's feet height and eye height did not move in any mode,
and the small tilt that remains (0.157 degrees) is vanilla's own view bobbing, equal in every mode.

### Jump timing windows

Offline: a sprint run-up to a jump across a gap, with jump tapped at every 1 ms moment, from 20 start points. The
window is the range of press times that make the jump.

| Jump | VANILLA window | EXACT window (any physics rate) | Latest working press | Earliest working press |
|---|---|---|---|---|
| 4 blocks (3-block gap) | 217.5 ms mean (200-250) | 193.0 ms mean (169-217) | identical | 24.5 ms later |
| 3 blocks (2-block gap) | 395.0 ms mean (350-400) | 371.0 ms mean (347-395) | identical | 24.0 ms later |

The latest working press is the same as vanilla's in every case. The window is smaller only at its early end: in
vanilla, a press slightly too early is delayed to the next tick, which can rescue it. TACTILE acts on the press when
it happens. See "Earlier response to presses" in section 8.

### Coyote time at an edge (1.2.0)

Offline (`gradlew inputPhaseTest`, 2026-11-13), EXACT at 960 per second. *Coyote time* is how long after the player's
box has fully left a block a jump still works; *overhang* is how far past the edge the box is at that last working
jump. Both are decided by where a window boundary falls after the box leaves the block (section 2), so they can be 0
to 50 ms (0 to 27.5 cm at sprint speed). 1.1 is the behavior before 1.2.0.

| Test | VANILLA | EXACT 1.1 | EXACT 1.2 |
|---|---|---|---|
| 1. Sprint from standing still toward an edge, the run started at each 1 ms offset within a tick; 10 start spots. Spread of the overhang over the offsets, per spot | 0 cm | 27.4 cm on average (0 to 27.5 cm at every spot) | 0 cm |
| 1. Overhang at each spot | 2.3 to 27.6 cm, set by the distance | — | equal to vanilla's at every spot (within 0.1 cm, the test's 1 ms step) |
| 2. Jump in place, sprint-forward pressed in mid-air at each 1 ms offset, land, run to the edge. Spread, per spot | 0 cm | 20.4 cm on average | 20.4 cm on average (unchanged) |
| 3. Second jump after a landing, fresh press or space held, with or without a 1 ms tap of A anywhere around the touchdown | 12 ticks | 12 ticks | 12 ticks; a tap never makes it sooner |
| 4. Best overhang a 1 ms tap of A just before the edge can give, standing start | — | no gain | no gain |

- **Standing starts:** the coyote time now depends only on the distance from the edge, exactly as in vanilla. Before,
  the millisecond at which the run began could change it from none to the full 50 ms.
- **Key changes while moving** (test 2): unchanged. Pressing a key 1 ms later really changes the run in TACTILE, so the
  edge is reached at a slightly different moment, and the window boundary falls elsewhere relative to it. Vanilla is
  consistent here only because it rounds every press to a tick, which is the delay TACTILE removes; no choice of
  window start can make this case consistent without rounding keys again. STEPPED at 20 per second (without
  short-tap keeping) reads keys where vanilla does, and so is consistent in the same way.
- **Rejected alternative:** letting every key change start a window. Measured with a prototype in the same test: it
  fixed standing starts equally, did not help test 2 (20.2 cm), and a 1 ms tap of A just before the edge gave the
  largest overhang (27.5 cm) at every start spot, where vanilla gives it only when the tick phase happens to be lucky.

### Short taps

One tap of the forward key, 10 to 20 taps per length. "Model" is the offline engine's prediction for the requested
tap length; "residual" compares each tap with the prediction for the length the engine actually received.

| Tap | EXACT 240 measured | Model | STEPPED 60 measured | Model |
|---|---|---|---|---|
| 3 ms | 0.0074 | 0.0064 | 0.0646 | 0.0642 |
| 10 ms | 0.0370 | 0.0364 | 0.0637 | 0.0642 |
| 20 ms | 0.0796 | 0.0784 | 0.0631 | 0.0642 |
| 40 ms | 0.1677 | 0.1629 | 0.1361 | 0.1345 |
| 100 ms | 0.4243 | 0.4257 | 0.4221 | 0.4245 |

- In EXACT the distance grows with the length of the tap, as it would with a perfectly timed key.
- In STEPPED at 60 steps per second, every tap shorter than one step counts as one step (0.064 blocks).
- Per-tap residual: 0 for every tap in every mode. The remaining differences in the table come from the real tap
  lengths, which ran 0.3-0.6 ms longer than requested, and from where the tap fell relative to a step.

## 8. Known differences

These are the differences from vanilla in the current version. With the same key timing, TACTILE's movement is
vanilla's (section 5); every difference below comes from the finer timing itself or from situations TACTILE leaves to
vanilla. Differences found and resolved in earlier versions are in section 9.

### By design: timing

**Earlier response to presses.** A press that vanilla would delay to the next tick acts at once. This changes some
timing windows, always in the direction of "the press counts when you made it":
- Long jumps: the latest working press is identical to vanilla; the earliest is about 24 ms later (section 7).
- Pillaring with late jump presses: 9.37 ticks per block instead of vanilla's 9.90; with early presses, both are 9.00
  (section 6).

**Short taps count.** Taps that vanilla never sees are applied for their real length (section 7).

**Coyote time after key changes on the move.** How long a jump still works after walking off an edge (0 to 50 ms) is
decided by where a window boundary falls, as in vanilla. From a standing start it depends only on the distance, as in
vanilla (since 1.2.0). After key changes while moving, or during a jump, it also depends on the exact milliseconds of
those presses, where vanilla's tick rounding makes it the same for every press within a tick (section 7, test 2).
See "If you optimize your timing" below.

**STEPPED at rates other than 20.** Key changes take effect at a different rhythm from vanilla's ticks, so routes
differ, as intended. At 20 steps per second without short-tap keeping, STEPPED reads keys exactly where vanilla does
(section 4).

**Movement between game ticks in what the server receives.** TACTILE sends one position per game tick, as vanilla does,
but when a key changes between two ticks, that packet contains movement no single vanilla tick produces. Vanilla's own
server check does not notice; servers that replay every tick with vanilla's rules, such as Hypixel, can pull the player
back (section 13). The option *Server-safe slime and knockback* removes this for slime and knockback; elsewhere it is
the finer timing itself and cannot be removed without giving up the purpose of the mod. Accepted by the project owner
(2026-11-12) after a play-test of 1.1.0 on Hypixel.

### If you optimize your timing

TACTILE keeps vanilla's 50 ms collision windows: within a window, contacts (standing, leaving an edge, landing, touching
a wall) are decided once, at its end, as vanilla decides them once per tick. Only where the windows start differs. For
players who practice precise timings:

- **What starts a window.** A jump, a knockback, starting to fly up or down, and the first movement key pressed while
  standing completely still. Nothing else: key presses while moving or in the air never move the windows. The game's
  own ticks are used until the first of these.
- **Walking off an edge.** You can still jump until the end of the window in which your box left the block, the same
  coyote time vanilla gives (0 to 50 ms). From a standing start, how much you get depends only on how far you started
  from the edge, as in vanilla; the moment you start does not matter.
- **After a jump or a key change on the move.** The exact milliseconds of those presses change your run, and with it
  where the window boundary falls when you reach an edge. So the coyote time at that edge can vary between attempts by
  up to 50 ms, where vanilla would give the same for every press within the same tick. This is the price of not
  rounding presses to ticks. STEPPED at 20 per second, without short-tap keeping, rounds them like vanilla if you want
  vanilla's consistency.
- **Taps do not buy coyote time.** Tapping a key just before an edge does not move the windows (tested; section 7).
- **The jump rhythm is vanilla's.** After a landing, a jump never fires sooner than vanilla allows, whatever keys you
  press around the touchdown (section 7). A held jump fires at the landing and starts the next windows itself, so a
  bunny-hop chain keeps the windows of its first jump.
- **The latest working jump before an edge** is the same moment as in vanilla for the same windows (section 7, "Jump
  timing windows").

### Handled by vanilla

**Situations the engine does not run.** While riding an entity, flying on a ladder, in spectator mode, or under a
potion effect added by another mod, TACTILE hands control to vanilla until the situation ends. Movement in these
situations is vanilla's, with vanilla's timing.

### Open

None.

## 9. Differences in earlier versions, and fixes

These no longer apply to the current version. They are kept so that earlier results and the development log can be
followed.

### Differences that were resolved or withdrawn

**D1 (resolved in 1.1.0): sprint starting or stopping in mid-air.** In vanilla, a change of sprint state affects air
acceleration from the next tick: the value is set after a tick's move and used during the next. Up to 1.0.3, TACTILE
applied it from the moment of the change. This happened when sprint stopped in the air (for example on running into a
wall or the face of a block, or starting to eat or block) and when it started again.
- Size in 1.0.x: 5.9 mm (jump_onto_block) to 4.8 cm (sword_block_jump) in the recorded scenarios; soul sand enlarged
  it to 10.7 cm, because its slowdown is applied per block and the player reached the next block one tick sooner. In
  an offline test of 400 jumps onto a step, 175 showed it, by at most 4.2 cm.
- Decision history: accepted on 2026-10-12 as a difference of the finer timing. On re-examination (2026-11-12) it
  turned out to be only the order in which vanilla applies a rule, not something the finer timing needs, so 1.1.0
  follows vanilla's order: each internal tick's air acceleration is fixed when it begins. Key presses keep their exact
  timing.
- Result: all 34 recorded scenarios identical to vanilla (section 5); corner clip and jumps onto a step 0 difference.
- Legacy: the setting *Instant air control (legacy, not vanilla)*, off by default, brings the 1.0.x behavior back. Its
  tooltip warns that it changes vanilla's timing. With it on, the offline check (`gradlew serverCheck`, part 5)
  reproduces the 1.0.x differences exactly.

**D2 (withdrawn): clipping the corner of a ledge.** Earlier editions of this report listed a separate difference of up
to 6 mm when jumping onto a block, blamed on TACTILE's smooth path between ticks. A tick-by-tick replay of
jump_onto_block (offline, 2026-11-12) showed that this was D1:
- Up to the tick where the player runs into the step's face, vanilla and TACTILE are identical, including the
  collision with the face (both stop at the same point in the same tick).
- Running into the face ends sprint. On the next tick, vanilla still uses the sprinting air acceleration for one more
  move, and TACTILE uses the walking one at once. That single tick accounts for the whole 5.9 mm (0.006 × 0.98 of a
  block), which then shrinks as sprint resumes.

The label dated from an older version of the engine that did handle corners differently (by about 0.15 blocks); later
versions removed that difference, and the small residual kept its old name. The corner-clip test in section 6 confirms
that collisions at corners match vanilla in every tested case.

### Problems fixed during development

| Version | Problem | Found by |
|---|---|---|
| 1.2.0 | From a standing start, the coyote time at an edge depended on the millisecond the run began relative to the game's ticks (0 to 49 ms for the same run from the same spot). A key pressed while standing still now starts a window (section 2). | Project owner's question about jump windows |
| 1.1.0 | A change of sprint in mid-air changed the air acceleration at once instead of from the next tick (D1, up to 10.7 cm). | Re-examination of D1 |
| 1.1.0 | The on-ground flag sent to the server could disagree with the reported position for one packet around a jump or landing partway through a tick. | Server-check research (section 13) |
| 1.0.3 | On Lunar Client, which ships a newer JNA library, the key reader failed to load and TACTILE switched itself off. The key reader now uses a field present in every JNA version, and a key reader that cannot start falls back to the poller, then to reading keys once per frame (section 12). | User report ([issue #1](https://github.com/fallingsword/TACTILE/issues/1)) |
| 1.0.1 | With chat messages switched off, F7 still printed the new mode in chat. | Play-testing |
| 1.0.0 | A jump pressed mid-tick at the moment of hitting a wall while sprinting kept the sprint-jump boost briefly; the result depended on the physics rate by up to 2.4 mm. The sprint rules now run before such a jump, as in vanilla. | STEPPED rate check (gate S) |
| 0.8.1 | Knockback pushed the player about 35 % too far: vanilla's ground friction on the first knockback tick was skipped. A knockback now starts a full tick when it arrives. | Hit test |
| 0.7.3 | STEPPED (then called HIGH_RES) at low rates drew the camera one step late. | Camera timing test |
| 0.7.3 | A key event delayed about 10 ms by Windows could take effect up to a tick late. | Key timing test |
| 0.6.2 | Small camera vibration while standing on slime. | Play-testing |
| 0.6.1 | The slime scenarios never reached the slime, so slime was untested. | Play-testing review |
| 0.6.0 | Camera vibration on a ladder while sneaking; server flight changes overwritten. | Play-testing, tests |
| 0.5.0 | Holding space during a hand-over could switch on creative flight. | Hand-over test |
| 0.4.1 | A short stop while bridging; a camera jump at hand-overs. | Bridge and hand-over tests |
| 0.4.0 | Blocks placed while moving could appear and vanish. | Placement test |

The development log, [RESULTS.md](RESULTS.md), records every measurement made during development in chronological
order, including how each of these problems was found and fixed.

## 10. Performance

Measured in the game during the scenario recordings of section 5 (about 2,600 ticks per run, uncapped frame rate):

| Run | Time per step | Engine time per frame: median / 99th percentile / max | Frame time: median / 99th percentile |
|---|---|---|---|
| EXACT, physics rate 960 | 3.2 µs | 6 / 25 / 214 µs | 1.37 / 2.11 ms |
| EXACT, physics rate 240 | 5.5 µs | 5 / 24 / 246 µs | 1.38 / 2.08 ms |
| STEPPED 20, physics rate 960 | 3.3 µs | 7 / 25 / 212 µs | 1.37 / 3.02 ms |
| STEPPED 35, physics rate 960 | 3.3 µs | 6 / 22 / 235 µs | 1.39 / 2.36 ms |

At 960 steps per second (the fixed rate since 1.0.2), the engine uses about 3 ms of processor time per second of play (0.3 % of one
core). A step at 240 per second costs more than one at 960 because it covers more distance, so its collision checks
consider more blocks.

The 1.1.0 recordings (2026-11-12) gave the same picture: 3.2 µs per step for EXACT 960, 4.9 µs for EXACT 240, 3.4 µs
for STEPPED 20 and 2.8 µs for EXACT 960 with the server-safe option, with median frame times of 1.29 to 1.37 ms. With
that option on, the server emulation also runs once per game tick (72 vanilla tick predictions, not part of the
per-step time); its own cost was not measured separately.

| Key reading | Processor use |
|---|---|
| Key events (default) | 0.17 % of one core |
| Poller at 2000 per second | 4.6 % of one core in the game; 6-7 % in a separate benchmark |
| Poller at 4000 or 8000 per second | a full core (it must busy-wait) |

## 11. Privacy checks

TACTILE reads keys through Windows Raw Input (or, if chosen, a poller using `GetAsyncKeyState`). The README describes
the safeguards. These were checked on 1.0.0:

- **Menu open:** with the pause menu open, 20 simulated key presses were sent. The key-event source received 0, and
  0 were waiting unread.
- **Not in front:** over the whole 75 s key test, 0 key events arrived while the game window was not in front. The
  source registered with Windows only 3 times, because it registers only while the game is in front and unregisters
  whenever it is not.
- **Only movement keys:** the key reader discards every key that is not a movement binding when it arrives. The
  simulated presses used for testing are themselves a movement binding.
- **Nothing stored or sent:** key events are kept in memory only until the next tick uses them. The release jar
  contains no code that writes key data or opens network connections; the test tools that record keys for the
  measurements in this report exist only in the development build.

## 12. Fail-safe tests

Errors were forced inside the engine on purpose (`HRMOVE_FAULTTEST`):

| Forced error in | Result |
|---|---|
| Tick handler | Game kept running; engine switched off with an error status; the player then walked 12.95 blocks in 3 s under vanilla |
| Frame handler | Same |
| Movement hook | Same |
| Key-event thread | Engine stayed on, reading keys once per frame instead |

**Added in 1.0.3**, after a report that TACTILE switched itself off on Lunar Client
([issue #1](https://github.com/fallingsword/TACTILE/issues/1)). According to the report, Lunar replaces Minecraft's JNA 3.4.0 (the library used
to call Windows functions) with JNA 5, which removed a field the key reader read while loading. Tested on 2026-11-10:

| Test | Result |
|---|---|
| Every JNA field and method TACTILE's compiled code uses (18), checked with their exact signatures against JNA 3.4.0, 4.5.2, 5.0.0 and 5.14.0 | Before the fix, 18 of 18 resolve in 3.4.0 and 4.5.2, 17 of 18 in both JNA 5 versions (`Pointer.SIZE` missing). Its replacement, `Native.POINTER_SIZE`, exists in all four |
| Development client with JNA 5.14.0 ahead of Minecraft's JNA, old code | `NoSuchFieldError: SIZE`, the error in the report. With the new fallback, TACTILE stayed on and used the poller |
| Same, fixed code | Key events start normally |
| Fixed code with Minecraft's own JNA 3.4.0 | Key events start normally |
| Key reader and poller both forced to fail while loading (`HRMOVE_FAULTTEST=keysinit,pollerinit`) | Engine stayed on, reading keys once per frame; the player walked 12.95 blocks in 3 s |

After these tests, the project owner play-tested 1.0.3 on Lunar Client and confirmed that it works.

## 13. Server movement checks

Added in 1.1.0 (research and tests on 2026-11-12). Players reported being pulled back ("rubber-banding") on Hypixel
with TACTILE on, and slime bounces failing about half the time, while other servers were fine.

### How servers check movement

- **Vanilla's own server check is lenient.** It sets the player back only if the position the client reports is more
  than 0.25 blocks horizontally from where the server's own collision puts it (a bug makes it ignore vertical
  differences), if a single packet moves more than 10 blocks, or if the player ends up inside blocks. TACTILE's
  differences never come near this, which is why plain servers are unaffected.
- **Prediction-based checks are strict.** GrimAC (open source), recent versions of NoCheatPlus, and reportedly
  Hypixel's Watchdog replay every 50 ms tick of the player's movement with vanilla's own rules. For each tick they try
  every vanilla input (each direction, jump, sprint, sneak, item use; a jump is a yes or no for the whole tick) and keep
  the prediction closest to the reported position. GrimAC's defaults flag a difference of 0.001 blocks, set the player
  back at once for 0.1 blocks in a single tick, and otherwise once the differences add up to 1 block. They also check
  the on-ground flag separately. A setback teleports the player, and the vanilla client then sets its speed to zero,
  which is why a setback during a slime bounce kills the bounce.
- **Why TACTILE trips them.** TACTILE sends one position per game tick, as vanilla does. When a key changes partway
  through a tick, the movement in that packet is a mix that no single vanilla input produces: for example, a jump
  pressed 0.3 of a tick in has risen about 0.30 blocks by the next packet instead of vanilla's 0.42. This is the
  finer timing itself, so it cannot be removed without giving up the purpose of the mod.

### Measured offline (`gradlew serverCheck`, 1.1.0)

The check emulates a prediction-based server (class `ServerShadow`, the same code the option below uses in the game):
from its own copy of the player's state it tries the 72 vanilla inputs for each tick and keeps the closest. With keys
at tick starts, every packet in every scenario below gives 0. EXACT with keys changed partway through each tick
(default settings):

| Situation (packets) | Keys at +0.1 tick | +0.3 | +0.5 | +0.7 | +0.9 |
|---|---|---|---|---|---|
| Sprint, then bunny-hop with jump held (90) | 0.044 / 56 / 0 | 0.137 / 52 / 4 | 0.234 / 59 / 6 | 0.154 / 59 / 4 | 0.061 / 61 / 0 |
| Horizontal keys only, no jumps (75) | 0.024 / 32 / 0 | 0.047 / 44 / 0 | 0.048 / 55 / 0 | 0.040 / 41 / 0 | 0.013 / 40 / 0 |
| Sprint-jump onto slime, keep running (60) | 0.046 / 52 / 0 | 0.126 / 51 / 13 | 0.216 / 57 / 26 | 0.284 / 55 / 27 | 0.321 / 55 / 12 |

Each cell: largest difference from the closest prediction (blocks) / packets flagged at 0.001 / packets at or above 0.1
(an immediate setback in GrimAC's defaults). Jumps and slime bounces partway through a tick give the largest
differences; horizontal keys alone stay below 0.05 but are flagged often, so under GrimAC-like rules they add up.

**On-ground flag.** Up to 1.0.3 the packet carried the contacts TACTILE committed at the end of its last internal tick,
which after a jump or landing partway through a tick disagreed with the reported position for one packet (about once
per jump in each direction: "on the ground" while already rising, "in the air" while already standing). Since 1.1.0
the flag is computed by vanilla's own rule for the movement since the last packet. Against vanilla's flag in all 34
recorded scenarios, keys at tick starts: 2,477 ticks, 0 differences. With keys partway through a tick, the false flags
are gone; a few packets (0 to 3 per run) still differ from the server's flag, because the server's closest prediction
is itself off in those packets.

**The option "Server-safe slime and knockback"** (Advanced settings, off by default). When slime comes under the player
(the first solid block below the center, or the block stood on), the engine switches at the next game tick to vanilla's
tick timing: its internal ticks are lined up with the game clock, the player's speed becomes the one the server
emulation expects, and key changes take effect at tick starts. It switches back to exact timing once the player is on
the ground off slime. Every knockback is applied from the last game tick, rewinding and replaying the short time since,
so the next packet already contains the knockback's first tick, as vanilla's does.

| Test (offline) | Without the option | With the option |
|---|---|---|
| Sprint-jump onto slime, jump pressed at +0.1 to +0.9 tick: packets from the switch on (41 to 42 per run) | see the slime row above | largest difference 0; 0 flagged; 0 flag differences, at every timing |
| Knockback arriving at +0.1 / +0.5 / +0.9 tick (25 packets) | largest 0.056 / 0.233 / 0.481; 2 packets at or above 0.1 for +0.5 and +0.9 | 0 at every timing |

The packets before the switch (the jump from stone) are not covered: a jump pressed partway through a tick anywhere
else is still flagged.

### Measured in the game (1.1.0, 2026-11-12)

- **Fall damage.** The server works out fall damage from the reported positions and on-ground flags. Walking off a
  platform onto stone, health lost: 8 blocks 3, 12 blocks 6, 16 blocks 11, 20 blocks 14, identical in VANILLA, EXACT
  and EXACT with the option. (A first version of the test teleported the player into the air instead; it gave 6 and 7
  at 12 blocks in different runs of vanilla alone, because the server ignores the first packets after a teleport.)
- **Knockback with the option on:** distances identical to vanilla in every trial (section 6).
- **Packets recorded with key changes at 0.37 of a tick**, run through the same emulation. Here the emulation is less
  exact: it does not reproduce every vanilla detail of the recordings (it also reports differences for vanilla's own
  recordings in 13 scenarios, such as ladders, item use and sneak-landing on slime), so the numbers are only useful
  side by side. Packets flagged at 0.001, option off / on: slime_bounce 49 / 16, slime_walk_jump 25 / 9 (on-ground flag
  differences 5 / 0); the remaining flags are mostly the packets before the switch. In slime_sneak_land the emulation
  is unreliable even for vanilla, and the result is inconclusive.
- **Recorded scenarios with the option on** (keys at tick starts): identical to vanilla in all 34 (section 5).

**Play-test (project owner, 1.1.0):** slime bounces work with the option. On Hypixel, movement in general is still
pulled back as before, as expected from the measurements above: jumps and other key changes between ticks are the
finer timing itself. This is accepted as a known difference (section 8). Watchdog's rules are not public, so the
comparison with it stays a play-test.

Sources: GrimAC [README](https://raw.githubusercontent.com/GrimAnticheat/Grim/2.0/README.md),
[PredictionEngine](https://raw.githubusercontent.com/GrimAnticheat/Grim/2.0/common/src/main/java/ac/grim/grimac/predictionengine/predictions/PredictionEngine.java),
[OffsetHandler](https://raw.githubusercontent.com/GrimAnticheat/Grim/2.0/common/src/main/java/ac/grim/grimac/checks/impl/prediction/OffsetHandler.java),
[default config](https://raw.githubusercontent.com/GrimAnticheat/Grim/2.0/common/src/main/resources/config/en.yml),
[GroundSpoof](https://raw.githubusercontent.com/GrimAnticheat/Grim/2.0/common/src/main/java/ac/grim/grimac/checks/impl/prediction/GroundSpoof.java);
NoCheatPlus [SurvivalFly](https://raw.githubusercontent.com/Updated-NoCheatPlus/NoCheatPlus/master/NCPCore/src/main/java/fr/neatmonster/nocheatplus/checks/moving/player/SurvivalFly.java);
Hypixel forum posts on its prediction check ([1](https://hypixel.net/threads/thoughts-on-prediction-ac.5890691/),
[2](https://hypixel.net/threads/explaining-prediction-anticheats.6011832/)) and on slime lagbacks
([3](https://hypixel.net/threads/petition-fix-slime-jumping.3966580/)); vanilla 1.8.9's server check
(`NetHandlerPlayServer.processPlayer`).

## 14. Limits of this verification

- **One machine, Windows only.** All measurements were made on the development machine. Other systems fall back to
  reading keys once per frame and were not tested.
- **Development build in the game.** The in-game tests ran on the development build, which contains the test tools.
  The release jar is the same mod code with the tools removed, checked by listing its contents and its internal
  references (no missing classes). The automated tests cannot run on the release jar itself, because the test tools
  are not in it.
- **The reference model is hand-written.** It is trusted because it reproduces every one of 2,621 recorded vanilla
  ticks exactly, across all 34 scenarios. Situations outside these scenarios rely on the same code paths but were not
  recorded.
- **Servers.** Play-testing on a multiplayer parkour server showed no problems. Servers with strict movement checks,
  such as Hypixel, pull the player back repeatedly while TACTILE is on. Section 13 measures this against an emulation
  of such a check; on a real strict server, the server-safe option was only play-tested (slime bounces work).
- **Other mods and clients.** OptiFine was play-tested, and 1.0.3 was play-tested on Lunar Client. No other mods or
  clients were tested.

## 15. Reproducing the results

You need the source code, JDK 8, and Windows. Run every command from the project folder with `--offline` after the
first build. The in-game recordings open a game window, run by themselves and close; those marked *focus* simulate
key presses and need the game window to stay in front.

The recordings are not part of the repository (section 3, "Raw data"), so make them first. The main test suite
compares the reference model with them; without recordings it stops with "FAIL: no traces found".

**1. Recorded scenarios (section 5).** Set the environment variables, then run `gradlew runClient`:

| Run | Environment variables |
|---|---|
| Vanilla | `HRMOVE_BASELINE=1 HRMOVE_BASELINE_SET=all HRMOVE_BASELINE_DIR=v100-vanilla` |
| EXACT 960 | as above, `HRMOVE_BASELINE_DIR=v110-exact960 HRMOVE_MODE=EXACT HRMOVE_RATE=960 HRMOVE_INPUT=SCRIPTED` |
| EXACT 240 | as above with `HRMOVE_RATE=240` and its own folder |
| STEPPED 20 / 35 | `HRMOVE_MODE=STEPPED HRMOVE_STEP=20` (or `35`), `HRMOVE_RATE=960 HRMOVE_INPUT=SCRIPTED` and its own folder |
| Server-safe option | as EXACT 960, plus `HRMOVE_SAFE=1` |
| Keys partway through a tick (section 13) | as EXACT 960, plus `HRMOVE_SCRIPT_PHASE=0.37`, optionally `HRMOVE_SAFE=1`, and `HRMOVE_BASELINE_ONLY=<names>` |

The folder in `HRMOVE_BASELINE_DIR` is relative to the game folder `run`, so `v100-vanilla` ends up in `run/v100-vanilla`.

Then compare:
```
gradlew liveValidation -Ptraces=run/v110-exact960 -Psteps=48 -Plead=1 -Pexact=true
gradlew traceDiff -Pa=run/v100-vanilla -Pb=run/v110-exact960 -Plead=1
```
(`-Psteps` is the physics rate divided by 20: 12 for 240, 48 for 960.)

**2. Offline checks (section 4 and parts of sections 6, 7 and 12),** using the recordings from step 1:
```
gradlew movementTests -Ptraces=run/v100-vanilla
gradlew serverCheck -Ptraces=run/v100-vanilla -Ppackets=run/v110-phase-exact,run/v110-phase-safe
gradlew edgeLandingTest jumpWindowTest jitterTest exactTimingTest ladderViewCheck jumpLatencyCheck pollerBench cornerClipTest inputPhaseTest
```
The tests in the second line do not need recordings.

**3. In-game tests (sections 6, 7, 10, 11, 12):**
- Hits, pillaring, placement, bridging, hand-overs: `HRMOVE_HITPROBE=1`, `HRMOVE_PILLARPROBE=1`,
  `HRMOVE_PLACEPROBE=1`, `HRMOVE_BRIDGEPROBE=1`, `HRMOVE_FLIGHTPROBE=1 HRMOVE_TOGGLETEST=4`, each with
  `HRMOVE_INPUT=SCRIPTED`.
- Fall damage: `HRMOVE_FALLPROBE=1 HRMOVE_INPUT=SCRIPTED`. Knockback with the server-safe option: add `HRMOVE_SAFE=1`
  to the hit probe.
- Settings screen: `HRMOVE_UIPROBE=1`.
- Forced errors: `HRMOVE_FAULTTEST=tick`, `frame`, `move` or `keys`; key sources failing to load: `keysinit`,
  `pollerinit` or `keysinit,pollerinit`.
- Another JNA version: `gradlew runClient -PjnaJar=<path to a JNA jar>`, for example JNA 5.14.0 from Maven Central.
- *Focus:* camera after a jump press: `HRMOVE_VIEWPROBE=1`, then `gradlew viewAnalysis`.
- *Focus:* key reading and the menu check: `HRMOVE_INPUTPROBE=auto`, then `gradlew inputAnalysis`.
- *Focus:* taps: `HRMOVE_INPUTPROBE=taptest`, then `gradlew tapAnalysis`.

[USAGE.md](USAGE.md) lists every option of these tools. The `HRMOVE_` names come from the project's earlier name and
were kept so that older records stay valid.
