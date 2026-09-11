# Motion Arcade

Games you play with your body. An Android phone's front camera tracks your
skeleton in real time and drives five demos — no controller, no wearables, no
depth sensor.

Built on [MediaPipe Pose Landmarker](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker),
which returns 33 body landmarks per frame at ~30fps on a mid-range phone.

| | |
|---|---|
| **Package** | `com.example.arcade` |
| **Min Android** | 7.0 (API 24) |
| **APK size** | ~37 MB (the pose model is ~6 MB of that) |
| **Tested on** | Pixel 10, ~30fps, 1–32 ms inference |

---

## The games

Open the app and pick one from the dashboard.

### Endless Runner — *standing*
A three-lane runner. **Lean** left or right to change lane, **jump** to clear the
red barriers, **duck** under the blue beams. Three lives.

You need to stand **2–4 m back** with your whole body in frame — prop the phone
against something at roughly hip height. The game silently learns your resting
posture over the first second or so; just stand still briefly when you start.

### Slicer — *seated*
Fruit-ninja. **Swipe a hand** through the coloured targets before they fall past
the bottom. Dark targets are bombs — hit one and the run ends. Five misses also
ends it.

### Reaction Grid — *seated*
Whack-a-mole on a 3×3 grid. One cell lights green; **hover your palm** over it
for 0.6 s to hit it before it expires. 45-second round.

### Brick Stacker — *seated*
Four bricks, one drop zone. **Hover a palm** over a brick for 2 s to pick it up
(a ring fills to show progress), carry it to the dashed zone, and hover again to
place it. Stack all four.

### Pose Viewer — *either*
Not a game. Camera with a skeleton overlay on top, live 3D stick figure below.
Useful for checking your framing and lighting before playing, and for seeing
what the tracker actually sees.

---

## Playing well

**Distance is the main thing.** The runner needs your hips and shoulders in
frame; the seated games only need your upper body. If the runner says "step
back", it means the tracker can measure you but not reliably — you are too far
for the landmark noise to stay below the gesture thresholds.

**Light yourself, not the camera.** A bright window behind you turns you into a
silhouette and tracking degrades badly. Front or side lighting is best.

**Plain background helps**, though it matters much less than lighting.

**Expect it to warm up.** Sustained camera plus per-frame inference heats the
phone; after 10–15 minutes of continuous play you may see thermal throttling and
a lower frame rate. Fine for demos, worth knowing for longer sessions.

---

## Building

Two things about this machine's toolchain, both of which will bite otherwise:

- **The Android Gradle Plugin needs JDK 17.** Both the system Java and Android
  Studio's bundled JBR are JDK 25 here, which AGP rejects, so `JAVA_HOME` must
  be overridden explicitly.
- **The Gradle wrapper's download stalls**, so the build is pinned to the
  already-cached `gradle-9.3.1`. Don't bump `distributionUrl` without checking
  the new version is actually present in `~/.gradle/wrapper/dists`.

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-17"

./gradlew :app:assembleDebug          # build the APK
./gradlew :posekit:testDebugUnitTest  # run the gesture-detection tests
```

Install and launch:

```bash
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am start -n com.example.arcade/.DashboardActivity
```

---

## Layout

```
posekit/     Library. Pose pipeline, gesture detection, the model asset.
app/         The single APK: dashboard + five activities.
```

`posekit` is a plain local Gradle module, not a published SDK — consumed via
`implementation(project(":posekit"))`. It exists so the pose code has exactly
one copy; everything a game needs to read the body lives there, and nothing
game-specific does.

See [AGENTS.md](AGENTS.md) for the architecture, the non-obvious constraints,
and how to add a new game.

---

## Troubleshooting

**"Step back so your whole body is in frame"** — the runner can't see your hips
and shoulders reliably, or you're far enough away that measurements aren't
trustworthy. Move closer, or improve the lighting.

**"Stand still for a moment…" won't clear** — calibration only accepts a
baseline from a genuinely still player, deliberately, because a baseline
captured mid-movement makes every control feel broken. Stand still for ~1.5 s.

**Gestures feel too sensitive or not sensitive enough** — tap **Debug** in the
runner to see the live traces. `hipOff` is your hip displacement in torso
lengths, with the jump (+0.12) and crouch (−0.15) thresholds drawn as guides.
Watching that while you move shows immediately whether a gesture is falling
short or overshooting. Thresholds live in
`posekit/src/main/java/com/example/posekit/detector/BodyDetectors.kt`.

**The cursor jitters in the seated games** — usually low light. The slicer uses
a One Euro filter, which deliberately smooths less when your hand moves fast, so
some jitter at speed is expected and is the correct trade for a responsive
slice.
