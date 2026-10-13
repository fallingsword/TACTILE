# Changelog

All notable changes to TACTILE. Each entry summarizes the development log,
[docs/RESULTS.md](docs/RESULTS.md), which lists every measurement behind these changes. Versions before 0.4.0 were
called "High-Resolution Movement".

## 1.0.0 (2026-10-13)

First stable release.

- **Fixed:** a jump pressed in the middle of a tick, at the moment the player ran into a wall while sprinting, kept the
  sprint-jump boost for a brief moment whose length depended on the physics rate (up to 2.4 mm of sideways position).
  Hitting a wall now ends sprinting before that jump, as it does in vanilla, and results no longer depend on the
  physics rate in any tested case.
- **Fail-safe:** if anything inside TACTILE fails unexpectedly, the game keeps running: movement switches to vanilla
  for the rest of the session, the error is written to the log, and a chat message says so. If the key reader stops,
  keys are read once per frame instead.
- The release jar contains only the mod. The developer tools used for testing are no longer included (the jar is about
  a third of its former size).
- MIT license, README, this changelog, and a verification report ([docs/VERIFICATION.md](docs/VERIFICATION.md)).
- Settings: the chat-notice description no longer speaks in the first person.

## 0.8.1 (2026-10-12)

- **STEPPED replaces HIGH_RES.** STEPPED uses EXACT's physics, but key changes take effect only in steps of a rate you
  choose: any whole number from 1 to 1920 Hz (slider 20-240, any value can be typed). A tap shorter than one step still
  counts for one step. Configurations that used HIGH_RES switch to STEPPED and keep their previous timing.
- **Fixed: knockback.** EXACT and STEPPED pushed the player about 35 % too far when hit by a mob or player. A knockback
  now starts a full movement tick at the moment it arrives, so its distance and arc match vanilla exactly, and it
  appears on screen 2-3 ms after it arrives (vanilla: 18-26 ms).
- The physics-rate tooltip now explains what that setting does: it sets how often the engine re-checks collisions
  inside a tick, and does not change movement.

## 0.8.0 (2026-10-12)

- **Using items inside the engine:** eating, drinking, drawing a bow and blocking with a sword (movement slowed to a
  fifth, no sprint start, double-tap sprint canceled) now match vanilla exactly instead of switching to vanilla.

## 0.7.3 (2026-10-12)

- **Fixed:** HIGH_RES at low rates drew the camera one step late (about 50 ms at 20 Hz). The camera now shows the
  exact current moment in every mode.
- **Fixed:** in rare cases Windows took about 10 ms to hand a key event over, and the press could take effect up to
  a tick late. The engine now waits for an event it knows is on its way.

## 0.7.2 (2026-10-12)

- **New settings screen.** The first page has only the main switch (TACTILE movement ON/OFF), the status overlay and
  chat messages. Everything else is on an *Advanced settings* page, where options that do not apply to your current
  choices are grayed out.
- The key-reading option ASYNC is now called POLLER.

## 0.7.1 (2026-10-12)

- New tests for building straight up (pillaring) and running up staircases; no change to gameplay.

## 0.7.0 (2026-10-12)

- **Key events instead of polling.** Windows now hands each movement-key change to TACTILE as it happens (Raw Input).
  Key changes are seen about 0.22 ms after Windows receives them (the 2 kHz poller: about 0.38 ms), no presses were
  missed in testing, and the key reader uses about 0.1 % of one CPU core instead of 4-5 %. It only listens while the
  game has focus; see the README's privacy section.

## 0.6.2 (2026-10-12)

- **Fixed:** a small camera vibration while standing on slime.

## 0.6.1 (2026-10-12)

- Tests for slime landings corrected (the earlier tests never reached the slime); slime bouncing verified against
  vanilla.
- New test for jump timing windows.
- Internal renaming and removal of leftover files from earlier versions.

## 0.6.0 (2026-10-11)

- **Soul sand, slime, cobwebs and creative flight inside the engine**, matching vanilla exactly.
- **Fixed:** camera vibration while holding still on a ladder with sneak.
- **Fixed:** flight switched on or off by the server (for example `/fly` or a game-mode change) is no longer
  overwritten by the client.

## 0.5.0 (2026-10-10)

- **Water, lava, ladders and vines inside the engine**, matching vanilla exactly.
- **Fixed:** holding space while control passed between TACTILE and vanilla could switch on creative flight.

## 0.4.1 (2026-10-10)

- **Fixed:** a short stop while bridging (placing blocks behind you while walking backwards).
- Vanilla potion effects, server-set walking speeds and low hunger are supported.
- Smooth camera when control passes between TACTILE and vanilla.
- EXACT draws the camera at the exact frame time.
- Chat messages are off by default.

## 0.4.0 (2026-10-10)

- Renamed **TACTILE**.
- EXACT is the default mode. Physics-rate slider with a box for typing values. Key poll rate up to 8000 Hz.
- **Fixed:** blocks placed while moving could appear and then vanish ("ghost blocks").

## 0.3.0 (2026-10-10)

- Settings screen, status overlay (F8), settings that apply without a restart.
- **EXACT mode:** each key change takes effect at the moment it happened, independent of the physics rate.
- Collisions and landings follow vanilla's per-tick rules; smooth camera at edges and steps.

## 0.1.0 (2026-10-10)

- First version: background key reader, movement simulated in small steps between game ticks, and automatic switching
  to vanilla in situations the engine did not yet handle.
