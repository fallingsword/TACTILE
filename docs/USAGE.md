# TACTILE: configuration and developer guide

This page covers the configuration file and the developer tools. For installing and using TACTILE, see the
[README](../README.md). For how TACTILE was tested and what was measured, see [VERIFICATION.md](VERIFICATION.md).

## Configuration file

`config/tactile.cfg` in the game folder. Every option can also be changed on the settings screen (*Mods* > *TACTILE*
> *Config*). Changes made on the settings screen apply from the next game tick, without a restart. Older configuration
files are migrated automatically: `hrmovement.cfg` (before 0.4.0), `inputSource=ASYNC` (before 0.7.2) and
`mode=HIGH_RES` (before 0.8.1).

| Option | Default | Meaning |
|---|---|---|
| `enabled` | true | The main switch (*TACTILE movement*). When off, movement is vanilla whatever the mode. |
| `mode` | EXACT | EXACT: each key change takes effect at the moment it happened. STEPPED: the same physics, but key changes take effect only in steps of `stepRateHz`. VANILLA: unmodified game behavior. |
| `stepRateHz` | 20 | STEPPED only. Steps per second, any whole number from 1 to 1920 (the slider covers 20 to 240). A key change waits for the next step, at most 1/rate seconds. |
| `physicsRateHz` | 960 | How often per second the engine re-checks collisions and the drawn path inside a tick (20 to 1920, in steps of 20). It does not change movement: positions agree to within about 1e-11 blocks across rates. Higher values cost slightly more processor time. |
| `inputSource` | AUTO | AUTO: Windows key events, falling back to the poller, then to reading once per frame. EVENTS: key events (falls back to the poller if a movement key is bound to a mouse button). POLLER: a background thread checks the movement keys at `pollRateHz`. FRAME: once per rendered frame. |
| `pollRateHz` | 2000 | POLLER only, 250 to 8000. Key changes are timed to within 1/rate. Above 2000 per second the poller must busy-wait, which occupies one processor core. |
| `pressLatch` | true | STEPPED only. A tap that starts and ends between two steps still counts, for one step. |
| `overlay` | OFF | Status overlay: OFF, SIMPLE or DETAILED (F8 cycles it). |
| `chatMessages` | false | Show a chat message when the movement mode changes or movement falls back to vanilla. |

The DETAILED overlay shows the active mode, the physics rate, how keys are read, and performance figures (time per
step, frame times, corrections received from the server).

## Developer tools

The development build (`gradlew runClient`, or the jar in `build/libs`) contains test tools that the release jar
(`build/release`) leaves out. Each tool is started by an environment variable. The development client uses an
offline account, so it cannot join online servers.

**Offline (no game needed):**
- `gradlew movementTests --offline`: the main test suite (gates A to G and S, and the comparison reports). It reads
  recordings from `run/hrm-baseline`, or from `-Ptraces=<folder>`.
- `gradlew edgeLandingTest`, `jumpWindowTest`, `jitterTest`, `ladderViewCheck`, `jumpLatencyCheck`,
  `exactTimingTest`, `pollerBench`.
- `gradlew liveValidation -Ptraces=<folder> -Psteps=12 -Plead=1 -Pexact=true`: compares in-game recordings made with
  the engine against the offline model.
- `gradlew traceDiff -Pa=<folder> -Pb=<folder> -Plead=1`: compares two sets of recordings tick by tick.

**Recording in the game** (the game opens, runs scripted movement, and closes):
- `HRMOVE_BASELINE=1`: records the scenarios with vanilla movement into `run/hrm-baseline`. Options:
  `HRMOVE_BASELINE_SET=all|medium|climb|items|fallback`, `HRMOVE_BASELINE_ONLY=<names>`, `HRMOVE_BASELINE_DIR=<folder>`,
  `HRMOVE_BASELINE_EFFECT=<potion:amplifier>`, `HRMOVE_BASELINE_FOOD=<level>`.
- Add `HRMOVE_MODE=EXACT HRMOVE_RATE=240 HRMOVE_INPUT=SCRIPTED` to record with the engine
  (`HRMOVE_MODE=STEPPED HRMOVE_STEP=35` for STEPPED).

**Probes in the game:**
- `HRMOVE_HITPROBE=1`: knockback from a zombie, a player and a sprinting player.
- `HRMOVE_PILLARPROBE=1`: building straight up, per mode and press timing.
- `HRMOVE_PLACEPROBE=1`, `HRMOVE_BRIDGEPROBE=1`: block placement while moving, and bridging.
- `HRMOVE_FLIGHTPROBE=1`, `HRMOVE_TRANSPROBE=1`: hand-overs between TACTILE and vanilla.
- `HRMOVE_FAULTTEST=tick|frame|move|keys`: forces an internal error to check that the game keeps running.
- `HRMOVE_UIPROBE=1`: screenshots of the settings screens (`run/screenshots/tactile-ui-*.png`); restores every setting
  it changes.

The probes above use scripted input (add `HRMOVE_INPUT=SCRIPTED`, except for the UI probe).

**Probes that simulate key presses** (the game window must stay in front; they only press keys while it is):
- `HRMOVE_VIEWPROBE=1`, then `gradlew viewAnalysis`: what the camera shows after a key press, per mode.
  `HRMOVE_VIEWPROBE=latency|latency20|latency960` measures the timing of each press through the key reader.
- `HRMOVE_INPUTPROBE=auto`, then `gradlew inputAnalysis`: key-reading latency per source.
- `HRMOVE_INPUTPROBE=taptest`, then `gradlew tapAnalysis`: distance moved per tap length.

The code is in the `com.tactile` package. The environment variables (`HRMOVE_*`) and the recordings under `run/hrm-*`
keep the names of earlier versions, so the commands in RESULTS.md stay valid. Jars of earlier versions are in
`archive/releases/`.
