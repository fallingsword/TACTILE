# TACTILE 1.0.0: verification report

This report explains how TACTILE was compared with the unmodified game, what was measured, and every known
difference. All numbers in the results sections come from one test session on 2026-10-13 with the 1.0.0 build,
unless a section says otherwise.

## Summary

TACTILE lets your movement keys take effect the moment you press them, instead of waiting for Minecraft's next game
tick. The question this report answers is: **does it change anything else?**

The short answer is no, apart from a few small, understood cases.

- We recorded the real, unmodified game doing 34 different things: walking, sprinting, jumping, sneaking at edges,
  stairs, water, lava, ladders, vines, soul sand, slime, cobwebs, creative flight, eating, bows and sword blocking.
  We then played exactly the same key presses with TACTILE switched on. In 28 of the 34 the result was identical to
  the last digit. The other six differ by between 6 mm and 11 cm, for the reasons listed below.
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
9. [Performance](#9-performance)
10. [Privacy checks](#10-privacy-checks)
11. [Fail-safe tests](#11-fail-safe-tests)
12. [Limits of this verification](#12-limits-of-this-verification)
13. [Reproducing the results](#13-reproducing-the-results)

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

Between ticks, the game draws frames by blending the player's position from the previous tick to the current one. So
what you see is always up to one tick behind the last position the game has calculated.

Distances in this report are in blocks. One block is one meter in the game.

## 2. What TACTILE changes

TACTILE keeps every rule of that update and changes only the timing:

1. **Key times.** On Windows, each movement-key change arrives as an event with the time it happened. Section 10
   and the README's privacy section explain how this access is limited.
2. **Movement between ticks.** For a given set of held keys, the per-tick update of speed and position can be written
   as an exact formula over time. TACTILE moves the player along that formula in small steps between ticks
   (960 per second by default), so a key change can act at its own time. When no key changes during a tick, the
   result at the end of the tick is exactly the one vanilla's update gives.
3. **Contacts on vanilla's schedule.** Collisions with blocks are resolved by vanilla's own collision code, and the
   resulting contacts are committed when a tick ends, as in vanilla. A jump, a knockback or a flight push starts a new
   tick at the moment it happens, so its path is vanilla's path, started at that moment.
4. **Drawing.** The camera shows the player's position at the moment each frame is drawn.

**EXACT** mode applies each key change at its own time. **STEPPED** mode applies it at the next step of a chosen
rate (1 to 1920 steps per second). The movement calculation is the same in both. **VANILLA** mode leaves the game
untouched.

The *physics rate* (default 960 per second) is how often TACTILE re-checks collisions and the drawn path within a
tick. It is an internal setting: section 4 shows that it does not change where the player ends up.

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
  sooner than the next tick), and the two cases accepted by the project owner (section 8), each with a measured upper
  bound.

### Test setup

- Windows 11, the development machine, Minecraft 1.8.9 with Forge 11.15.1.2318, in a flat test world generated by
  the recorder.
- All runs in sections 4 to 11 used the 1.0.0 build. The engine in the development build used for recording is the
  same code as in the release jar; the release jar only leaves out the test tools (section 12).
- Simulated key presses for the timing tests were injected through Windows while the game window was in front.
  Latencies are measured from the moment Windows received the press, so the keyboard's own delay is not included.

### Raw data

The raw recordings are not published, because the recording logs contain details of the test machine (user name and
file paths). This report gives the aggregated results. Section 13 explains how to produce the recordings again.

## 4. Offline checks

These run outside the game (`gradlew movementTests`, and the other tasks named in section 13). All passed on the
1.0.0 code.

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

Each scenario was recorded five times in the game on the 1.0.0 build: VANILLA, EXACT at physics rates 240 and 960,
and STEPPED at 20 and 35 steps per second. The columns are:

- **Model** (gate A): the reference model reproduces the recorded vanilla run.
- **Engine** (gate L): the in-game engine produced exactly what the offline engine predicts for the same input. Run
  for EXACT 240, EXACT 960 and STEPPED 20.
- **vs vanilla**: the largest distance between TACTILE's and vanilla's position at any tick end, in blocks. EXACT 240,
  EXACT 960 and STEPPED 20 gave the same value in every scenario, so one column is shown.

| Scenario | What it does | Ticks | Model | Engine | vs vanilla |
|---|---|---|---|---|---|
| stand | Stand still and settle on the ground | 30 | identical | identical | 0 |
| walk_stop | Walk, release, slide to a stop | 50 | identical | identical | 0 |
| sprint_stop | Sprint, release, stop | 60 | identical | identical | 0 |
| diag_strafe | Walk diagonally while strafing | 55 | identical | identical | 0 |
| jump_in_place | Jump on the spot | 28 | identical | identical | 0 |
| sprint_jump_chain | Repeated sprint jumps | 90 | identical | identical | 0 |
| walk_off_ledge | Walk off a block edge and fall | 50 | identical | identical | 0 |
| wall_scrape | Sprint along and into a wall | 50 | identical | identical | 0 |
| slab_steps | Walk up half-slabs | 50 | identical | identical | 0 |
| sneak_edge | Sneak to the edge of a block | 75 | identical | identical | 0 |
| jump_onto_block | Jump up onto a one-block step | 44 | identical | identical | 0.0059 (D2) |
| head_hitter | Jump under a low ceiling | 70 | identical | identical | 0 |
| turning_jumps | Sprint jumps while turning | 75 | identical | identical | 0 |
| double_tap_sprint | Start sprinting by double-tapping forward | 38 | identical | identical | 0 |
| water_swim | Swim through water | 130 | identical | identical | 0 |
| water_tread | Stay afloat in water | 97 | identical | identical | 0 |
| lava_swim | Swim through lava | 140 | identical | identical | 0 |
| ladder_climb | Climb and descend a ladder | 102 | identical | identical | 0 |
| ladder_jump | Jump onto a ladder | 65 | identical | identical | 0 |
| vine_climb | Climb vines | 60 | identical | identical | 0 |
| soulsand_walk | Sprint and jump on soul sand | 65 | identical | identical | 0.1065 (D1) |
| slime_bounce | Fall onto slime and bounce | 94 | identical | identical | 0 |
| slime_walk_jump | Walk off onto slime, jump on it | 75 | identical | identical | 0 |
| slime_sneak_land | Land on slime while sneaking (no bounce) | 62 | identical | identical | 0 |
| web_walk | Walk through cobwebs | 110 | identical | identical | 0 |
| fly_basic | Creative flight: up, down, across | 96 | identical | identical | 0 |
| staircase_hold | Sprint-jump up a staircase, jump held | 80 | identical | identical | 0.0334 (D1) |
| staircase_repress | Sprint-jump up a staircase, jump pressed each time | 80 | identical | identical | 0.0349 (D1) |
| staircase_walk_repress | Walk up a staircase, jump pressed each time | 120 | identical | identical | 0 |
| eat_walk | Eat while walking | 60 | identical | identical | 0 |
| eat_sprint | Eat while sprinting (sprint ends), then resume | 65 | identical | identical | 0 |
| eat_jump_doubletap | Start eating in the air; double-tap forward while eating | 65 | identical | identical | 0.0094 (D1) |
| bow_strafe_shoot | Strafe while drawing a bow, shoot, jump while drawing | 71 | identical | identical | 0 |
| sword_block_jump | Sprint-jump chain while blocking with a sword | 75 | identical | identical | 0.0481 (D1) |

"Identical" means every recorded value (position, speed, contacts) is equal to the last bit in every tick.

**STEPPED at 35 steps per second** differs from vanilla in most scenarios (largest 1.25 blocks, in staircase_hold).
This is expected: at 35 steps per second a key change takes effect at a different moment than vanilla's tick, so the
player follows a different route. The offline checks in section 4 show that STEPPED applies the same movement rules
as EXACT.

**Hand-overs during recording.** Over the four engine runs there were 0 hand-overs to vanilla. The engine logged the
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
than vanilla's best timing (9.00). See D3 in section 8.

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
| EXACT, physics rate 960 (default) | 3.2 ms | 1.8 ms (1.7-1.9) |

The remaining 1.8 ms in EXACT is the time to the next drawn frame. In STEPPED the delay is the wait for the next step,
on average half a step.

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
it happens. See D3 in section 8.

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

### Accepted by the project owner

**D1. Sprint starting or stopping in mid-air.** In vanilla, a change of sprint state affects air acceleration from
the next tick. TACTILE applies it from the moment it happens. This applies when sprint starts again in the air (for
example after hitting a wall or a stair face), and when it stops in the air (for example when you start eating or
stop blocking).
- Size: 9.4 mm (eat_jump_doubletap) to 4.8 cm (sword_block_jump) in the recorded scenarios. Soul sand enlarges it to
  10.7 cm, because its slowdown is applied per block and the player reaches the next block one tick sooner. In an
  offline test of 400 jumps onto a step, 175 showed it, by at most 4.2 cm.
- Cause: finer timing of a rule vanilla only checks once per tick. The rule itself is unchanged.
- Decision: accepted (2026-10-12), as a difference that comes from the higher time resolution.

**D2. Clipping the corner of a ledge during a jump.** Vanilla moves the player up and then forward within each tick.
TACTILE's path between ticks is smooth, so when the player rises past the top corner of a block while moving towards
it, the corner is met at a slightly different point.
- Size: at most 6 mm (5.9 mm in jump_onto_block).
- Decision: accepted.

### By design: timing

**D3. Earlier response to presses.** A press that vanilla would delay to the next tick acts at once. This changes some
timing windows, always in the direction of "the press counts when you made it":
- Long jumps: the latest working press is identical to vanilla; the earliest is about 24 ms later (section 7).
- Pillaring with late jump presses: 9.37 ticks per block instead of vanilla's 9.90; with early presses, both are 9.00
  (section 6).

**D4. Short taps count.** Taps that vanilla never sees are applied for their real length (section 7).

**D5. STEPPED at rates other than 20.** Key changes take effect at a different rhythm from vanilla's ticks, so routes
differ, as intended. At 20 steps per second without short-tap keeping, STEPPED reads keys exactly where vanilla does
(section 4).

### Handled by vanilla

**D6. Situations the engine does not run.** While riding an entity, flying on a ladder, in spectator mode, or under a
potion effect added by another mod, TACTILE hands control to vanilla until the situation ends. Movement in these
situations is vanilla's, with vanilla's timing.

### Open

None.

### Fixed during development

| Version | Problem | Found by |
|---|---|---|
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

## 9. Performance

Measured in the game during the scenario recordings of section 5 (about 2,600 ticks per run, uncapped frame rate):

| Run | Time per step | Engine time per frame: median / 99th percentile / max | Frame time: median / 99th percentile |
|---|---|---|---|
| EXACT, physics rate 960 | 3.2 µs | 6 / 25 / 214 µs | 1.37 / 2.11 ms |
| EXACT, physics rate 240 | 5.5 µs | 5 / 24 / 246 µs | 1.38 / 2.08 ms |
| STEPPED 20, physics rate 960 | 3.3 µs | 7 / 25 / 212 µs | 1.37 / 3.02 ms |
| STEPPED 35, physics rate 960 | 3.3 µs | 6 / 22 / 235 µs | 1.39 / 2.36 ms |

At the default 960 steps per second, the engine uses about 3 ms of processor time per second of play (0.3 % of one
core). A step at 240 per second costs more than one at 960 because it covers more distance, so its collision checks
consider more blocks.

| Key reading | Processor use |
|---|---|
| Key events (default) | 0.17 % of one core |
| Poller at 2000 per second | 4.6 % of one core in the game; 6-7 % in a separate benchmark |
| Poller at 4000 or 8000 per second | a full core (it must busy-wait) |

## 10. Privacy checks

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

## 11. Fail-safe tests

Errors were forced inside the engine on purpose (`HRMOVE_FAULTTEST`):

| Forced error in | Result |
|---|---|
| Tick handler | Game kept running; engine switched off with an error status; the player then walked 12.95 blocks in 3 s under vanilla |
| Frame handler | Same |
| Movement hook | Same |
| Key-event thread | Engine stayed on, reading keys once per frame instead |

## 12. Limits of this verification

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
  such as Hypixel, pull the player back repeatedly while TACTILE is on. No server's movement checks were tested
  systematically.
- **Other mods.** OptiFine was play-tested. No other mods were tested.

## 13. Reproducing the results

You need the source code, JDK 8, and Windows. Run every command from the project folder with `--offline` after the
first build. The in-game recordings open a game window, run by themselves and close; those marked *focus* simulate
key presses and need the game window to stay in front.

**Offline checks (section 4):**
```
gradlew movementTests -Ptraces=run/v100-vanilla
gradlew edgeLandingTest jumpWindowTest jitterTest exactTimingTest ladderViewCheck jumpLatencyCheck pollerBench
```

**Recorded scenarios (section 5).** Set the environment variables, then run `gradlew runClient`:

| Run | Environment variables |
|---|---|
| Vanilla | `HRMOVE_BASELINE=1 HRMOVE_BASELINE_SET=all HRMOVE_BASELINE_DIR=run/v100-vanilla` |
| EXACT 960 | as above, `HRMOVE_BASELINE_DIR=run/v100-exact960 HRMOVE_MODE=EXACT HRMOVE_RATE=960 HRMOVE_INPUT=SCRIPTED` |
| EXACT 240 | as above with `HRMOVE_RATE=240` and its own folder |
| STEPPED 20 / 35 | `HRMOVE_MODE=STEPPED HRMOVE_STEP=20` (or `35`), `HRMOVE_RATE=960 HRMOVE_INPUT=SCRIPTED` and its own folder |

Then compare:
```
gradlew liveValidation -Ptraces=run/v100-exact960 -Psteps=48 -Plead=1 -Pexact=true
gradlew traceDiff -Pa=run/v100-vanilla -Pb=run/v100-exact960 -Plead=1
```
(`-Psteps` is the physics rate divided by 20: 12 for 240, 48 for 960.)

**In-game tests (sections 6, 7, 10, 11):**
- Hits, pillaring, placement, bridging, hand-overs: `HRMOVE_HITPROBE=1`, `HRMOVE_PILLARPROBE=1`,
  `HRMOVE_PLACEPROBE=1`, `HRMOVE_BRIDGEPROBE=1`, `HRMOVE_FLIGHTPROBE=1 HRMOVE_TOGGLETEST=4`, each with
  `HRMOVE_INPUT=SCRIPTED`.
- Settings screen: `HRMOVE_UIPROBE=1`.
- Forced errors: `HRMOVE_FAULTTEST=tick`, `frame`, `move` or `keys`.
- *Focus:* camera after a jump press: `HRMOVE_VIEWPROBE=1`, then `gradlew viewAnalysis`.
- *Focus:* key reading and the menu check: `HRMOVE_INPUTPROBE=auto`, then `gradlew inputAnalysis`.
- *Focus:* taps: `HRMOVE_INPUTPROBE=taptest`, then `gradlew tapAnalysis`.

[USAGE.md](USAGE.md) lists every option of these tools. The `HRMOVE_` names come from the project's earlier name and
were kept so that older records stay valid.
