# TACTILE

TACTILE is a client-side mod for Minecraft 1.8.9 (Forge) that changes *when* your movement keys take effect, not
*how* you move. In vanilla Minecraft, a key press waits for the next game tick, which comes every 50 ms, and taps
shorter than a tick can be lost entirely. With TACTILE, each press and release of a movement key takes effect at the
moment it happened. Everything else about movement stays vanilla: jump height and distance, sprinting, sneaking at
edges, landings, liquids, ladders, slime, knockback and the rest follow the game's own rules, and this has been
measured against the unmodified game (see [Verification](#documentation)).

## Authorship

**I (fallingsword) am not the author of this mod's code.** I (fallingsword) set the goals, gave the technical direction,
made the design decisions and play-tested every version. The code, the tests, the measurements and this documentation
were written by Claude, an AI model made by Anthropic, working under that direction. Please read the rest of this
page with that in mind: the project was reviewed by testing and measurement, not by a human reading every line of
code.

## What it does

- **Key timing.** Movement keys (forward, back, left, right, jump, sneak, sprint) take effect at the time you press
  or release them, measured to a fraction of a millisecond. A key held for 20 ms moves you exactly 20 ms worth.
- **Responsive camera.** The camera shows your position at the moment each frame is drawn. A jump becomes visible
  about 2 ms after the key press, against about 30 ms on average in vanilla.
- **Vanilla movement rules.** Positions, collisions, landings and all the timers vanilla uses (jump cooldown, sprint
  double-tap) follow vanilla's code. With the same key timing, TACTILE produces the same result as vanilla.

## What it does not do

- It does not move you faster, jump higher or farther, or change any game rule. It only removes the wait between a
  key press and the next game tick.
- It does not automate anything. Every movement comes from your own key presses.
- It does not change other players, mobs, the world, or anything sent by the server. The game still runs at
  20 ticks per second.

## Modes

| Mode | What happens to a key press | When to use it |
|---|---|---|
| **EXACT** (default) | Takes effect at the moment it happened. | Recommended for almost everyone. |
| **STEPPED** | Takes effect at the next step of a rate you choose (1 to 1920 steps per second). At 20 steps per second this matches vanilla's key timing, without losing short taps. | If you want key timing on a fixed rhythm, for example to practice a particular jump at a particular rate. |
| **VANILLA** | Unchanged game behavior (TACTILE switched off). | For comparison, or where mods that change movement are not allowed. |

Movement physics is the same in EXACT and STEPPED; only the moment a key takes effect differs.

STEPPED at 20 steps per second is not the same as VANILLA. Keys take effect at the same moments as in vanilla, but
the rest is TACTILE's:
- short taps still count;
- the camera shows where you are at the moment the frame is drawn, instead of trailing up to one tick behind;
- a knockback takes effect when it arrives, not at the next tick;
- the small known differences listed below apply.

## Installation

TACTILE needs Minecraft 1.8.9 with Minecraft Forge (11.15.1.2318 or a later 1.8.9 build). Download the jar from the
[Releases page](https://github.com/fallingsword/TACTILE/releases).

With the Modrinth App:
1. Create an instance for Minecraft **1.8.9** with the **Forge** loader.
2. Open the instance, go to *Content*, and add `tactile-1.0.0.jar` (*Add content* > *From file*, or drop the file
   into the instance's `mods` folder).
3. Start the game and press **F8**: the overlay in the top left should say `EXACT active`.

With another launcher, put the jar in the `mods` folder of a Forge 1.8.9 installation. Remove older TACTILE jars;
two copies cannot be loaded at the same time.

## Settings and keys

- **F7** cycles VANILLA, STEPPED and EXACT.
- **F8** cycles the status overlay: off, simple, detailed.
- **Settings screen:** *Mods* > *TACTILE* > *Config*, or *Options* > *Mod Options* while in a world. All keys can be
  changed under *Controls* > *TACTILE*.

The first page has the main switch (*TACTILE movement* on or off), the overlay, and chat messages. The *Advanced
settings* page has the mode, the step rate (STEPPED only), the physics rate, how keys are read, the poll rate (POLLER
only) and the short-tap option (STEPPED only). Options that do not apply to your current choices are grayed out.
Every option has a tooltip. Changes apply immediately, without restarting.

The physics rate does not change how you move. It sets how often TACTILE re-checks collisions within a tick; the
default (960 per second) costs a few microseconds of processor time per step.

## Compatibility

- **OptiFine:** works (tested by play).
- **Multiplayer:** works on servers that allow client mods of this kind (tested by play on a parkour server).
  Servers still check your movement, and many forbid mods that change movement. Read the rules of the server before
  you use TACTILE there.
- **Incompatible servers:** servers with strict movement checks reject TACTILE's movement and pull you back
  repeatedly ("rubber-banding"). **Hypixel is one of them**: with TACTILE switched on, movement is heavily
  rubber-banded. Switch TACTILE off (F7 to VANILLA, or the main switch) on such servers.
- **Operating systems:** precise key timing uses Windows. On other systems TACTILE reads keys once per rendered frame,
  so its timing is as fine as your frame rate.

## Known differences from vanilla

TACTILE was compared with the unmodified game in 34 recorded scenarios (walking, sprinting, jumping, sneaking at edges,
staircases, water, lava, ladders, vines, soul sand, slime, cobwebs, creative flight, eating, bows and sword blocking),
and in tests of getting hit, building straight up, placing blocks and bridging. With the same key timing, the results
match vanilla exactly except for these known cases:

- **Sprint starting or stopping in mid-air** (for example, coming off a wall, or starting to eat while airborne).
  TACTILE applies the change in air acceleration immediately; vanilla applies it one tick later. The effect was 1 to
  5 cm in the tested cases, and 11 cm on soul sand, which enlarges it.
- **Clipping the corner of a ledge during a jump:** up to 6 mm.
- **Earlier response.** Because a press is no longer delayed to the next tick, some timing windows change shape. For
  example, on a flat four-block sprint jump the latest moment you can press jump is identical to vanilla, while the
  earliest is about 24 ms later, because vanilla's tick delay forgives presses that come slightly too early.

These are explained, with measurements, in the verification report, [docs/VERIFICATION.md](docs/VERIFICATION.md).

## Transparency and privacy

TACTILE reads keyboard input at a low level, which is the same kind of access a key logger uses. This section
explains exactly what it reads and how it limits that access. The source code is available, so all of this can be
checked.

**How keys are read (Windows):**
- By default TACTILE registers for Windows *Raw Input* keyboard events. Windows then sends each key event to a small
  background thread inside the game, which records the time it arrived.
- Alternatively (setting *Key reading: POLLER*), a background thread checks the movement keys' state at a fixed rate
  with `GetAsyncKeyState`.

**Steps that protect your privacy:**
- **Only while the game has focus.** The Raw Input registration exists only while the game window is in front and no
  menu or chat is open. As soon as you switch to another window, open a menu or open chat, TACTILE unregisters and
  Windows stops sending it anything. A test with the pause menu open confirmed that no key events reached TACTILE.
- **A second check on every event.** Before reading an event, TACTILE checks that the game window is the window in
  front at that moment. If it is not, the event is not read; it is only counted.
- **Only movement keys.** Every key that is not one of your movement key bindings is discarded as soon as it arrives.
  The poller only ever looks at the movement keys.
- **Nothing is stored or sent.** Key events are used for movement and then discarded. TACTILE writes nothing about your
  keys to disk and sends nothing about them anywhere. The only network traffic it causes is the game's own: your
  position, as vanilla sends it every tick, and a flight on/off message when you land while flying, as vanilla does.
- **Your other programs are unaffected.** TACTILE does not block or change key messages; every program, including
  Minecraft itself, still receives its keys normally.

The development version contains test tools that simulate key presses for automated measurements. They are not part
of the release jar, and even in development they only act while the game window is in front.

## When TACTILE hands control back to vanilla

TACTILE switches to vanilla movement automatically, with a smooth camera transition, while you are riding an entity,
flying on a ladder, in spectator mode, or under a potion effect added by another mod. It switches back as soon as the
situation ends. If anything inside TACTILE fails unexpectedly, the game keeps running: movement switches to vanilla
for the rest of the session, a chat message tells you, and the error is written to the game's log.

## Reporting problems

Open an issue on the [Issues page](https://github.com/fallingsword/TACTILE/issues). Please include the game log
(`logs/latest.log` in your instance folder) and describe what you were doing. The game log can contain your Windows
user name in file paths; replace it before posting if you prefer. The status overlay (F8, detailed) shows whether
TACTILE was active and why it may have handed control back to vanilla.

## Documentation

- [docs/VERIFICATION.md](docs/VERIFICATION.md): the verification report. It explains how TACTILE was compared with
  vanilla, gives the measured results, and lists every known difference.
- [CHANGELOG.md](CHANGELOG.md): changes per version.
- [docs/RESULTS.md](docs/RESULTS.md): the development log. Every test and measurement made during development, in
  the order it was made, including earlier versions. For the results of the current version, see the verification
  report above.
- [docs/USAGE.md](docs/USAGE.md): the configuration file and developer tools.

## Building from source

Requirements: JDK 8. From the project folder:

```
gradlew build --offline
```

The release jar is written to `build/release/tactile-<version>.jar`. The jar in `build/libs` is a development build
that also contains the test tools. `gradlew movementTests` runs the offline test suite.

## License

MIT, see [LICENSE](LICENSE). Minecraft is a trademark of Mojang. TACTILE is not affiliated with Mojang or Microsoft.

## Frequently asked questions

**Is this cheating?** TACTILE does not change what your character can do, only how quickly your key presses are
applied. Whether that is allowed is up to each server; check its rules.

**Does it change jump distance or height?** No. Jump arcs and distances were measured against vanilla and match
exactly.

**Which mode should I use?** EXACT. Try STEPPED only if you want key timing on a fixed rhythm.

**Does the physics rate matter?** No, not for movement. Leave it at the default unless you want to save a little
processor time.
