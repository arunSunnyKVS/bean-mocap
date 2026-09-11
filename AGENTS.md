# AGENTS.md

Working notes for this repo. Read the **Traps** section before changing anything
in `posekit` — three of them are counter-intuitive enough that the "obvious"
change is wrong.

## Layout

```
posekit/                         Android library. No game logic lives here.
  Landmark.kt                    Landmark, Point2, PoseLandmarks (indices, bones)
  PoseAnalyzer.kt                CameraX -> MediaPipe -> PoseResult
  PoseFrame.kt                   Immutable snapshot + scale-invariant anchors
  PoseStream.kt                  Smoothing, calibration, tracking, dispatch
  PoseEvent.kt                   Events, PoseState, GestureDetector interface
  filter/Filters.kt              VelocityTracker, Median, EMA, OneEuro
  detector/BodyDetectors.kt      Jump, Crouch, Lean         (standing)
  detector/HandDetectors.kt      HandTracker, Swipe, Dwell  (seated)
  view/ViewMapping.kt            Normalised coords -> view pixels
  assets/                        pose_landmarker_lite.task  (the only copy)

app/
  DashboardActivity.kt           Game picker (built in code, no layout file)
  PoseActivity.kt                Base class: camera + permission + analyzer
  DebugOverlay.kt                Live traces of the gesture signals
  OverlayView.kt                 Skeleton overlay
  AvatarGLSurfaceView.kt         GL stick figure
  games/                         One Activity + one View per game
```

Module rule: `posekit` never imports from `app`. If a game needs something from
the pose layer, it goes in `posekit` as a general primitive, or it stays in the
game. Resist adding game-specific knowledge to a detector — `DwellDetector`
takes a `hitTest` lambda rather than knowing about grids for exactly this reason.

## Building

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-17"   # required; system Java is 25
./gradlew :app:assembleDebug
./gradlew :posekit:testDebugUnitTest
```

The wrapper is pinned to `gradle-9.3.1` because it is the only distribution
actually cached on this machine and fresh downloads stall. Check
`~/.gradle/wrapper/dists` before changing `distributionUrl`.

## Traps

### 1. World landmarks cannot see a jump

`Landmark.wx/wy/wz` are metric and tempting. They are also **hip-centred**: the
hip midpoint is pinned at the origin by construction, so during a jump the hips
stay at (0,0,0) and the whole-body translation is invisible.

Vertical body motion **must** be measured from image-space `y`
(`PoseState.hipOffsetTl`). World landmarks are for driving an avatar rig, which
is all `AvatarGLSurfaceView` uses them for.

### 2. Signs and mirroring are applied exactly once

Image `y` grows *downward*, and the front-camera preview is mirrored. Both
corrections happen in `PoseStream`, at one boundary:

- `hipOffsetTl` is `(baseline - hipY) / torso` → **positive means raised**.
- `LeanState.LEFT` means **the player's own left**, post-mirror.
- Hand velocity is up-positive and post-mirror.

Do not re-apply either in a detector or a game. If something points the wrong
way, the bug is upstream of where you're looking — a stray `-1` in a game is how
this gets silently wrong in two places at once.

Note `ViewMapping` takes its own `mirror` flag: pass `mirror = false` when
mapping a cursor that `HandTracker` already mirrored, and `true` when mapping
raw landmarks (as `OverlayView` does).

### 3. `timestampMs` and `inferenceMs` are not interchangeable

`PoseResult.timestampMs` is the frame's own identity, echoed back by MediaPipe.
It is the **only** valid time base for velocity.

`PoseResult.inferenceMs` is round-trip latency, for status text and diagnostics.
Frames are dropped under `STRATEGY_KEEP_ONLY_LATEST`, so the gap between two
delivered frames is *not* `inferenceMs`. Using it as a `dt` was a real bug here;
frames are now tracked in a map keyed by timestamp so size and latency always
belong to the frame they're reported with.

## Conventions

**Thresholds are in torso lengths (TL)**, never pixels or metres, so a gesture
reads the same at 2 m and 4 m and for any player height. All of them are
constructor defaults on the detectors — that is the one place to tune them.

**Comments explain *why*.** The what is usually legible from the code; the
reasoning behind a magic number is not. `CrouchDetector.holdMs = 120` looks
arbitrary until you know it is what stops a jump's landing dip registering as a
crouch.

**Detectors are single-threaded.** `PoseStream.push` must always be called from
the same thread (MediaPipe's callback thread). Internals are deliberately
lock-free. `addDetector`/`reset` are the exception — they queue a command that
`push` drains. Anything touching a View must be marshalled to the UI thread by
the Activity.

**Test the timing-sensitive logic on the JVM.** `SyntheticPose` builds frames
from parameters, so debounce, hysteresis and dropout handling are all testable
without a device. Verifying "one jump emits one event" by actually jumping in
front of a phone is not a workable loop — add a test instead.

## Adding a game

1. `games/FooView.kt` — a `View` owning the game state. Drive animation with
   `postOnAnimation`; pose results arrive at ~30fps and are too coarse for
   smooth rendering on their own.
2. `games/FooActivity.kt` — extend `PoseActivity`, implement `previewView()` and
   `onPose()`. Build a `PoseStream`, add detectors, set `onEvent`.
3. `res/layout/activity_foo.xml` — `PreviewView`, a dimming `View`, your game
   view, a status `TextView`.
4. Register in `AndroidManifest.xml` and add an `Entry` to `DashboardActivity`.

Pick detectors by posture: standing games use Jump/Crouch/Lean; seated games use
`HandTracker` plus Swipe or Dwell. Use `CursorFilter.ONE_EURO` when the hand
moves fast (slicing), `EMA` when it must hold still (dwell).

For collision against a moving hand, test the **swept segment** from
`HandState.previousPosition` to `position` — at 30fps a fast hand skips a long
way between frames and point-sampling misses.

## State

Phases 1–3 of `.claude/plans/glittery-chasing-penguin.md` are complete: posekit
extracted, gesture layer built with 16 passing tests, dashboard and five games
wired up and building.

**Not yet done:** on-device verification of the three new games. The Pixel
disconnected partway through, so the runner's thresholds in particular are
reasoned starting points that have never been tested against a real body. The
debug overlay exists to make that tuning fast — expect `JUMP_ENTER` and
`LEAN_ENTER` to need adjustment once someone actually plays it.
