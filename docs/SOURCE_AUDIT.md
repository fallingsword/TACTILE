# Phase 0 — Source Audit (Forge 1.8.9-11.15.1.2318, MCP stable_20)

All references are to the deobfuscated, Forge-patched sources in
`~/.gradle/caches/minecraft/net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/stable/20/forgeSrc-...-sources.jar`.
Line numbers refer to that jar. Nothing here is inferred from the proposal alone.

## 1. Toolchain (verified)

| Item | Value |
|---|---|
| Build | ForgeGradle 2.1.3 on Gradle 2.7 (wrapper). `gradlew build --offline` succeeds (remote repos in build.gradle are dead; caches are complete). |
| JDK | Temurin 1.8.0_504. Compiler runs with `-source 1.6` (FG default). |
| Mappings | `stable_20` (FG warns it was made for 1.8.8; MCP names used here all resolved). |
| Mixin | Not available offline. Hooks must be Forge events or a LaunchWrapper coremod (ASM ships with Forge). |
| JUnit | Not in the offline cache. Tests are a plain-Java runner (`gradlew movementTests`). |

## 2. Client loop and cadences

`Minecraft.runGameLoop()` (L1043):

1. `timer.updateTimer()`: computes `elapsedTicks` (capped at 10) and `renderPartialTicks`.
2. `scheduledTasks` drained. **Network packets (incl. S08 corrections) are applied here, between ticks.**
3. `for j < elapsedTicks: runTick()`.
4. `entityRenderer.updateCameraAndRender(renderPartialTicks)`. Mouse-look `thePlayer.setAngles(...)` runs **per frame** here (EntityRenderer L1082/1088).
5. `updateDisplay()` → LWJGL `Display.update()` (OS message pump + device poll).

`Minecraft.runTick()` (L1704):
`FMLCommonHandler.onPreClientTick` (= `ClientTickEvent START`) → GUI → **`Mouse.next()` / `Keyboard.next()` loops call `KeyBinding.setKeyBindState`** (L1803, L1872) → `theWorld.updateEntities()` (L2176; ticks `thePlayer`) → `onPostClientTick` (= `ClientTickEvent END`, L2238).

### Consequences (four cadences, verified)

| Cadence | Where | Rate |
|---|---|---|
| Movement-key state (`KeyBinding.pressed`) | `runTick` event loop | **20 Hz**: key bindings only change inside `runTick`. |
| Yaw / pitch | `EntityRenderer.updateCameraAndRender` → `setAngles` | **Per render frame** |
| OS input acquisition | `Display.update()` → `Keyboard.poll()` | Per render frame (raw `Keyboard.isKeyDown` is fresher than `KeyBinding`) |
| Player physics | `EntityPlayerSP.onUpdate` inside `updateEntities` | 20 Hz |
| Movement packets | `EntityPlayerSP.onUpdateWalkingPlayer` (end of `onUpdate`) | 20 Hz |

**Input claim boundary:** with the ordinary input flow, movement keys reach the player at 20 Hz.
A 240 Hz integrator receives a new movement-key snapshot once per tick and a new yaw once per frame.
Raising the key observation rate would mean reading `Keyboard.isKeyDown` per frame. Proposal §18 permits
investigating that, but it is **not** done in the first milestones, and it never adds timestamps or event-time splitting.

## 3. Local player movement call graph (one vanilla tick)

```
EntityPlayerSP.onUpdate                         (SP L159)  only if chunk at pos is loaded
 └ EntityPlayer.onUpdate                        (EP L246)  item-use, sleep timers  [tick-level]
    └ EntityLivingBase.onUpdate                 (ELB L1771) ForgeHooks.onLivingUpdate (cancelable event)
       ├ Entity.onEntityUpdate                  (E L357)   prevPos = pos, handleWaterMovement, lava, void
       ├ EntityPlayerSP.onLivingUpdate          (SP L725)
       │   sprintingTicksLeft--, sprintToggleTimer--, portal timers            [tick counters]
       │   prev = (jump, sneak, forward>=0.8)  ;  movementInput.updatePlayerMoveState()   [input snapshot]
       │   item-use slow ×0.2 ; pushOutOfBlocks ×4                              [tick-level]
       │   sprint start/stop rules (uses onGround, isCollidedHorizontally of LAST move)  [discrete]
       │   creative fly toggle / fly vertical motion                            [unsupported → fallback]
       │   └ EntityPlayer.onLivingUpdate        (EP L591)
       │       flyToggleTimer--, inventory anims, prevCameraYaw
       │       └ EntityLivingBase.onLivingUpdate (ELB L1936)
       │           jumpTicks--                                                  [counter]
       │           |motion.{x,y,z}| < 0.005 → 0                                  [tick-level quantization]
       │           updateEntityActionState: moveStrafing/moveForward/isJumping := movementInput
       │           if isJumping: water/lava swim | (onGround && jumpTicks==0) → jump(); jumpTicks=10
       │           else jumpTicks = 0                                            [discrete impulse]
       │           moveStrafing *= 0.98 ; moveForward *= 0.98   (overwritten next tick ⇒ constant scale)
       │           EntityPlayer.moveEntityWithHeading (EP L1857) → ELB.moveEntityWithHeading (ELB L1589)
       │               f4 = onGround ? slip(block below)*0.91 : 0.91
       │               accel = onGround ? AIMoveSpeed*0.16277136/f4³ : jumpMovementFactor
       │               moveFlying(strafe, forward, accel)  : motion += rotate(yaw) * input  [continuous, pre-move]
       │               ladder clamp                                                         [unsupported]
       │               Entity.moveEntity(motion)                                            [collision]
       │               motionY = (motionY − 0.08) * 0.98                                    [continuous, post-move]
       │               motionX,Z *= f4                                                      [continuous, post-move]
       │               limbSwing update ; addMovementStat (exhaustion, stats)               [tick-level]
       │       jumpMovementFactor = 0.02 (+0.006 if sprinting)  ← set AFTER the move: next tick's air accel
       │       setAIMoveSpeed(attribute), cameraYaw/Pitch, entity pickup                    [tick-level]
       └ renderYawOffset / rotation wrap                                                    [render bookkeeping]
 └ onUpdateWalkingPlayer                        (SP L180)  C0B sprint/sneak, C03-C06 position  [network, 20 Hz]
```

`Entity.moveEntity` (E L544) is **not** a pure collision primitive:

| Part | Lines | Class |
|---|---|---|
| web slow-down (sets motion 0) | 558-567 | unsupported state |
| sneak-edge clamp (0.05 steps, probe y−1) | 572-640 | collision (needs `onGround`, `isSneaking`) |
| Y→X→Z clipping vs `world.getCollidingBoundingBoxes` | 642-665 | collision |
| step-up (stepHeight 0.6, two candidate paths) | 667-759 | collision |
| flags `isCollidedHorizontally/Vertically`, `onGround` | 764-767 | collision outputs |
| `updateFallState` (fallDistance) | 785 | per-displacement accumulation (safe per substep) |
| motionX/Z := 0 on clipped axis | 787-795 | collision response |
| `block.onLanded` (default `motionY = 0`; slime bounce) | 797-800 | collision response, block-specific |
| step sounds / `onEntityCollidedWithBlock` | 802-839 | side effect (distance-based) |
| `doBlockCollisions` (soul sand ×0.4, web, portal, cactus …) | 843 | **per-call multiplicative side effects → would apply N× per tick** |
| fire checks | 853-878 | side effect |

Exact constants confirmed: gravity 0.08, vertical drag 0.98 (`0.9800000190734863`), air friction 0.91,
default slipperiness 0.6, ground-accel factor 0.16277136, jump 0.42F (+0.1/level of Jump Boost),
sprint-jump boost 0.2 along yaw using `MathHelper.sin/cos` (65536-entry table), speedInAir 0.02,
sprint modifier ×1.30000001192092896 (op 2), stepHeight 0.6, motion cutoff 0.005,
`Block.onLanded` sets `motionY = 0`.

## 4. Operation classification for the substep operator

| Operation | Class | Substep rule |
|---|---|---|
| `moveFlying` acceleration | continuous (pre-move) | part of the affine velocity flow, see §5 |
| gravity −0.08, drag ×0.98 | continuous (post-move) | affine flow; exact h-th power of the tick map |
| horizontal friction ×f4 | continuous (post-move) | affine flow with f4 evaluated per substep |
| `moveForward *= 0.98` | constant input scale (overwritten each tick) | **not** time-scaled |
| `|v|<0.005 → 0` | tick-level quantization | once per vanilla tick boundary |
| jump impulse (vy=0.42, sprint boost) | discrete impulse | once per activation, at the substep its condition holds |
| `jumpTicks` (10) | counter | normalized time: decrement by h per substep |
| sprint start/stop, `sprintToggleTimer` | discrete, input-driven | once per tick (input changes at 20 Hz) |
| `jumpMovementFactor` (prev-tick sprint) | tick-level state | update at tick boundary, as in vanilla |
| AABB clip / step-up / sneak-edge | geometry | per substep, unscaled (no geometry is time-scaled) |
| `onLanded` motionY=0, clipped-axis zero | collision response | per substep (see §5 rule for the vertical case) |
| `doBlockCollisions`, sounds, stats, exhaustion | tick-coupled side effects | once per tick, or the state is unsupported (fallback) |
| packets (`onUpdateWalkingPlayer`) | network | unchanged, once per tick |
| S08 correction (`handlePlayerPosLook`, NHPC L662) | external authority | rebase substep state; applied between ticks by the scheduled-task queue |

## 5. Substep formulation (design, to be verified by the harness)

Per axis, one vanilla tick is the affine map `v' = f·(v + a_pre + a_post)`, displacement `D = v + a_pre`
(horizontal: `a_pre` = moveFlying accel, `a_post = 0`; vertical: `a_pre = 0`, `a_post = −0.08`).
Treating this as the time-1 flow of a linear ODE gives the exact fractional step for `h = 20/H`:

```
v*  = f(a_pre + a_post)/(1 − f)
E   = f^h ,  S = (1 − E)/(1 − f)
D_h = h(a_pre + v*) + (v − v*)·S          // displacement over the substep
v_h = v* + (v − v*)·E                      // velocity after the substep
```

Properties: at h = 1 it **is** the vanilla update. In free motion with constant inputs and ground state,
1/h substeps compose to exactly one vanilla tick, so tick-boundary positions and velocities match vanilla.
Divergence from vanilla can only come from events inside a tick: contacts, ground-state
changes, and yaw changes. Those are the intended resolution gains, and the harness measures them.

Collision response is a **state assignment, independent of h**. A clipped axis takes vanilla's post-clip value:
0 horizontally, and `(0 − 0.08)·0.98 = −0.0784` vertically (vanilla's resting motionY).
*Revised after measurement:* the first version scaled the response with h (`v*(1 − E)`). At ≥120 Hz that left the
embedding's displacement velocity `w = β + αv` positive (β ≈ +0.0396/tick, because vanilla applies gravity after the move).
Resting players were lifted off the floor every other substep, `onGround` flickered, and sneak-edge, walking and
sprint-jump failed (sprint-jump 0.03 blocks instead of 3.6). The constant response keeps vanilla's resting state
(w ≈ −0.0396, never a bounce), and walk-offs start from exactly vanilla's state.

Ground state, input and discrete rules are evaluated per substep (decided 2026-10-10, see `docs/RESULTS.md`).

## 6. Hook feasibility

| Need | Option | Verdict |
|---|---|---|
| Baseline recording, scripted input | `TickEvent.ClientTickEvent` START/END + `KeyBinding.setKeyBindState` | Forge event, no patching |
| Replace local-player move | `PlayerControllerMP.func_178892_a` creates the player inside `loadWorld`, right after the controller is constructed in `handleJoinGame` (NHPC L273-276) | No window to inject a subclass cleanly |
| | `LivingUpdateEvent` (cancelable) | Cancels the **entire** living update. Too coarse |
| | Coremod: redirect `moveEntityWithHeading` call in `EntityLivingBase.onLivingUpdate` for `EntityPlayerSP` only | **Chosen for Phase 4**: one call site, SRG-named, fail-closed |
| Camera | `orientCamera` interpolates `prevPos→pos` by `renderPartialTicks` (ER L611), entity render uses `lastTickPos` | Phase 5: must feed one interpolation path |
| Corrections | `handlePlayerPosLook` runs from the scheduled-task queue between ticks | Detect by pos discontinuity, or a hook in Phase 6 |
| Velocity overrides | `S12PacketEntityVelocity` (quantized to 1/8000) also rewrites local motion. **Observed in the baseline:** after the 4-block fall in `walk_off_ledge`, motionY became −0.078375 between ticks 24 and 25 | Second external-authority path; the substep state must rebase on it like S08 |
