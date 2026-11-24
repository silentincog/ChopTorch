# Chop Torch

Motorola-style "chop chop" flashlight for the Redmi 10 Prime running crDroid (works on any Android 8+ ROM).
Hold the phone, chop down twice, and the torch toggles. It works with the phone locked and unlocked, including with the screen off. **Only when locked** limits it to the lock screen and screen off.

Package `dev.incog.choptorch`. Made with ❤ by **incogcyberpunk**. Licensed under the [MIT License](LICENSE). Privacy policy: [PRIVACY.md](PRIVACY.md) (nothing leaves the phone; the app has no internet permission).

## Download

Get the APK from [Releases](../../releases). Each version has its own release with `ChopTorch-<version>.apk` attached (min Android 8, target Android 14, signed by `CN=incog`).

## Files

| File | What it is |
|---|---|
| `app/` | Kotlin source. UI is Jetpack Compose with Material 3 Expressive (`material3` 1.5.0). |
| `keystore/incog.jks` + `keystore.properties` | Signing key and its passwords. **Not in this repo** (see `.gitignore`); kept outside version control. Back both up: updates must be signed with the same key. |

## How it works

1. `ChopService` is a foreground service that reads the accelerometer at 100 Hz on its own thread.
2. `ChopDetector` subtracts a low-pass gravity estimate to get linear acceleration. One chop is a swing above the trigger force followed within 350 ms by a hard stop in the opposite direction (at least 50 % of the trigger force). Two chops in the same direction that start 150–900 ms apart toggle the torch. After a toggle there is a 1.2 s cooldown.
3. `Torch` uses the public `CameraManager.setTorchMode` API, so it needs no camera permission and stays in sync with the Quick Settings torch tile.
4. With the screen off the service holds a partial wake lock, because the accelerometer stops reporting when the CPU sleeps. An optional pocket guard uses the proximity sensor to ignore chops while the phone is in a pocket.
5. Root is optional. The **Apply root keep-alive** button adds the app to the Doze whitelist and sets its standby bucket to "active". Exempting it from battery optimisation in Settings does the same job without root.

## UI (Material 3 Expressive)

- Four tabs in a `ShortNavigationBar`: **Home** (torch, gesture switch, live force), **Gesture** (sensitivity, Auto/Manual/Model, learning, delay, behaviour), **Vibration** (strength and custom timing) and **Settings** (appearance, import, about, legal). Each tab has its own `LargeFlexibleTopAppBar` that collapses on scroll.
- `MaterialExpressiveTheme` with the expressive motion scheme.
- Hero card with a shape from `MaterialShapes` that morphs from a cookie (torch off) to a sun (torch on) and spins slowly while the gesture is listening. A shape-morphing `ToggleButton` turns the gesture on and off.
- Live force readout using `LinearWavyProgressIndicator`, sensitivity `Slider`, settings as `SegmentedListItem` groups, and a `LoadingIndicator` while root commands run.

Source: `app/src/main/java/dev/incog/choptorch/ui/`.

## Themes

Settings → **Appearance**:

- **Colour**: Wallpaper (Android 12+, Monet), Indigo, Ocean, Teal, Forest, Sunset, Rose, Grape or Mono. The fixed colours are full Material 3 tonal palettes generated from a seed colour with Material Color Utilities (`com.materialkolor:material-color-utilities`), so every colour role has proper contrast.
- **Mode**: System, Light or Dark.
- **Pure black**: black backgrounds in dark mode, for AMOLED screens.

Code: `ui/Theme.kt`, `ui/SettingsScreen.kt`.

## Settings, about and legal

The Settings tab holds Appearance, **Import from Chop Torch 3.x**, **About** (version, author, link to releases, "Made with ❤ by incogcyberpunk") and **Legal**: privacy policy, MIT licence and open-source licences (Apache 2.0 text included). Texts live in `Legal.kt` and match [LICENSE](LICENSE) and [PRIVACY.md](PRIVACY.md).

## Install

1. Download the latest `ChopTorch-<version>.apk` from Releases on the phone and install it. 3.x builds signed by `incog` update in place up to 3.5. **4.0 changed the package from `com.kiro.choptorch` to `dev.incog.choptorch`, so Android installs it as a separate app** (see "Moving from 3.x" below). The very first build used a debug key and must be uninstalled first.
2. Open **Chop Torch** and turn on **Chop gesture enabled**. Allow notifications.
3. Tap **Open battery settings for this app** and allow it to ignore battery optimisation. The status line should show "Battery exempt: yes".
4. Optional: pull down Quick Settings → edit → add the **Chop Torch** tile to switch the gesture on and off.

## Moving from 3.x to 4.0

1. Install 4.0. Both apps now show up; while both have the gesture on, one gesture switches the torch twice.
2. In 4.0: Settings → **Import from Chop Torch 3.x** → **Import (root)**. With root it copies the old settings, personal model, learning history and Auto mode data, then restarts the app and shows what was imported. Without root, teach the gesture again.
3. Tap **Uninstall 3.x**.

How the import works: the new app creates its own destination files, then a root shell `cat`s the old files into them, so the new files keep the new app's owner and SELinux label. Code: `LegacyImport.kt`.

## Only when locked (default off)

Behaviour → **Only when locked**. Off by default: the gesture works locked and unlocked. When on, the service listens only when the screen is off or the lock screen is showing, and turns the accelerometer off as soon as you unlock (`USER_PRESENT`, plus a re-check 0.4 s and 1.5 s after the screen turns on, for fingerprint and face unlock). While paused, the app and notification show "Paused while unlocked" and nothing is learned. Version 3.3 had this on by default; 3.4 switches it off once on upgrade. With the screen lock set to "None", there is no lock screen, so with this on it only works with the screen off.

## Vibration

The **Vibration** tab: one buzz when the torch turns on, two when it turns off, with **Light / Medium / Strong / Custom** (default Strong) and a **Feel it** button. A timeline draws both patterns to scale. The calibration cues use the same pattern.

**Custom** sets the timing yourself, in 10 ms steps: torch-on buzz 20–600 ms, each torch-off buzz 20–400 ms, pause between them 40–400 ms, and intensity 10–255 if the motor supports amplitude control (otherwise longer buzzes feel stronger). Letting go of a slider plays the result. **Reset** goes back to the Strong values.

| Strength | On | Off | Amplitude (only if the motor supports it) |
|---|---|---|---|
| Light | 80 ms | 60 + 60 ms, 90 ms apart | 150 |
| Medium | 150 ms | 100 + 100 ms, 110 ms apart | 220 |
| Strong | 250 ms | 160 + 160 ms, 120 ms apart | 255 |

Since 3.5 the buzz uses Android's hardware-feedback vibration category (the one the fingerprint sensor uses). Before, it had no category, so Android 13+ scaled it by the *media* vibration setting and dropped it in battery saver, and the pulses (60 ms, 2 × 40 ms) were too short for a basic motor to spin up. Hardware feedback follows the touch feedback strength in Settings → Sound & vibration → Vibration & haptics, and still plays in silent mode and battery saver.

## Delay between toggles

The **Delay between toggles** card sets how long gestures are ignored after one switched the torch: 0.3–2.0 s in 0.1 s steps, default 1.2 s. It applies to Auto, Manual, Model and Test & teach, and takes effect immediately.

Simulated quickest on → off (synthetic data): Auto/Manual 0.66 s at 0.3 s, 1.56 s at 1.2 s; Model adds the gesture window (about 0.9 s), so 1.12 s at 0.3 s and 2.02 s at 1.2 s. With the arm springing back after the gesture, no extra switches happened at 0.5 s or more; at 0.3 s, a very springy bounce as strong as the chop switched the torch back off in 20 of 30 gestures.

## Auto mode (default)

The Sensitivity card has **Auto / Manual / Model**. Auto needs no setup: just use the gesture.

1. **Learns your usual force.** It starts at 14 m/s². Each gesture you confirm by what you do next is measured: keeping the torch on for 8 s, repeating a soft gesture harder within 3 s, or using the torch tile within 6 s after a soft one. After 3 gestures the trigger is set to 70 % of the median of your last 20; clear confirmations (repeat, torch tile) count double. Switching the torch back within 3 s, or **Wasn't me** in the notification, counts as a false trigger and keeps the trigger above it.
2. **Catches softer gestures.** A second detector at 60 % of the trigger runs alongside. It never switches the torch; it only notices soft double chops so they can be confirmed and lower the trigger.
3. **Builds the personal model by itself.** It keeps a short recording of each confirmed gesture, plus a 3 s clip of everyday movement about every 90 s and every switched-back false trigger. With 8 gestures it picks the ones that look alike (most typical gesture, then everything within 2x its typical distance), trains the model on the phone, checks it, and switches to **Model**. From there **Learn from use** continues.

The card shows the trigger force, your usual force, a histogram of your last 20 forces, and progress toward the model. **Start learning again** resets it. **Manual** keeps the fixed force with Default / My preset and − / +.

Simulation (synthetic data): a hard chopper (usual force about 30) got a trigger of about 21 and 9–10 of 10 first tries; a soft chopper (about 11) got about 10.5. Both got a personal model after 7–8 uses that recognised 20/20 new gestures with 0 false triggers in 5 minutes of everyday movement. For the soft chopper, force alone caused many false triggers before the model was ready, because everyday bumps were harder than their gesture.

Code: `ml/AutoPilot.kt`, `ml/Snippets.kt` (raw-clip buffer, storage, `AutoModelBuilder`).

## Teach my gesture (personal model)

Sensitivity card → **Teach my gesture**. Takes about a minute; the torch stays off throughout.

1. **8 gestures on cue.** A countdown (3, 2, 1) then **Chop now!** with a buzz. Do the gesture exactly how you want it to work; any gesture works, not only a double chop.
2. **30 s of normal use**, no gesture: walk, pick the phone up, pocket it, wave it. This teaches the model what to ignore. Can be skipped.
3. **Training on the phone** (well under a second). Two options are shown side by side with their results on your data:
   - **Personal model**: a nearest-neighbour model with dynamic time warping, the approach from the uWave work on personalised accelerometer gestures (Liu et al., 2009). Your gestures are the templates. A movement matches when its average distance to the two closest templates is under a cutoff learned from your samples (leave-one-out) and the non-gestures. Matching is on shape, scaled for force, so soft and hard versions of your gesture both count. **Relaxed / Balanced / Strict** move the cutoff.
   - **Classic detector**: the fixed double-chop rules with a trigger force from your average (Light 60 %, Balanced 75 %, Firm 90 %).
4. **Test & teach**: try it live (torch stays off). Each movement shows a match %; above 50 % would switch the torch. Tap **That was it** or **Wasn't me** on any mistake and the model adds that sample and retrains instantly. From the main screen, corrections are saved straight away.

The main screen switches between **Default**, **My preset** (classic calibrated) and **Model**. Everything stays on the phone: the model file is `files/personal_model.bin` in device-protected storage (~25 KB), so it works before first unlock.

Code: `ml/Frontend.kt` (gravity removal, onset-anchored 50 Hz windows, DTW), `ml/PersonalModel.kt` (model, cutoffs, live detector), `ml/Trainer.kt`, `calib/*` (recording, classic calibration), `CalibrationController.kt`, `ui/CalibrationScreen.kt`.

Simulation results before release (synthetic data, not a real phone): new double chops, soft double chops and double wrist twists were all recognised (15/15 each) with 0 false triggers in 60 s of simulated everyday movement and running, where the classic detector either missed soft chops or false-triggered 7 times. With ±30 % speed and ±25° angle variation, Balanced still caught 18/20. 5 minutes of sensor data is processed in 0.04 s on a desktop.

## Learning from use (on-device, continual)

With **Model** selected, the **Learn from use** card (on by default) lets the model keep learning on the phone from what you do next. Nothing is uploaded.

| What you do | What it learns |
|---|---|
| Near miss (35–50 % match), then you repeat the gesture within 3 s and it works | the near miss was your gesture |
| Near miss, nothing triggers, you switch the torch by hand within 6 s | the near miss was your gesture |
| Two similar firm movements both miss, then you use the torch by hand | both were your gesture (catches up when your style changes a lot) |
| A borderline match (50–75 %) switches the torch and you keep it for 8 s | that variant is confirmed |
| The gesture switches the torch and you switch it back within 3 s | that movement was a false trigger |
| **Wasn't me** in the notification while the torch is on | that movement was a false trigger (also turns the torch off) |

Safeguards:
- Calibration samples are never removed. Learned gesture samples fill the remaining 16 of 24 slots, oldest out first.
- A new gesture sample must already be close to your gesture, and have the same number of motion bursts and a similar length. Walking, running, picking the phone up, pocketing and repeated single chops followed by the torch tile were all tested and taught it nothing.
- At most 40 lessons a day, of which at most 12 are confirmations.
- Every lesson shows under **Recently learned** with an **Undo**; **Forget everything learned since calibration** resets to calibration. If it misses you 3 times in a day, the card suggests Test & teach.

Code: `ml/OnlineLearner.kt` (rules), `ml/PersonalModel.kt` (samples with origin, protected eviction, undo), `ml/ModelStore.kt` (locked load–change–save shared by the service and screens, learning history), `ChopService.kt` (signals, torch-change attribution, notification action).

Simulation (5.6 simulated days, 160 uses, synthetic data): with the gesture drifting gradually (25 % faster, 25 % softer, wrist turned 40°), first-try success per 40 uses was 40/40 in every block with learning on, against 40, 37, 18, 0 with it off. False triggers from double-chop-like bumps dropped from about 22 per 13 minutes to 0 after one switch-back. After an abrupt change it took about 40 uses to recover on its own (20, 0, 18, 40), which is why the card suggests Test & teach after 3 misses.

## Tuning

The app shows a live force meter and a gesture log.

- Easiest: use **Calibrate to my gesture**. By hand: the − / + buttons move the trigger force in 0.5 m/s² steps; the slider is continuous from 6 to 40 (left = light chop, right = firm chop).
- Default trigger force is 14 m/s². Going below about 10 m/s² causes false triggers while running.
- Log `chop 1` with no `chop 2` means the second chop was too weak, in a different direction, or later than 0.9 s.
- Log `swing without stop` means the chop was a push without a sharp stop. Chop and stop sharply, like hitting a table edge.

## Battery

Screen on: almost no cost, because the CPU is already awake.
Screen off: the wake lock keeps one CPU core from deep sleep. I have not measured the drain on a Redmi 10 Prime; check Settings → Battery after one day. If it is too high, turn off **Work with screen off**.

## Rebuild

Requires JDK 17, Gradle 9.9, and the Android SDK platform `android-37.2` (Compose BOM 2026.11.01 needs compileSdk 37.1 or later). AGP is 9.5.2 and Kotlin is 2.4.21. Run `gradle assembleRelease` with `sdk.dir` set in `local.properties`. The release build is shrunk with R8 and reads the signing key from `keystore.properties`.
