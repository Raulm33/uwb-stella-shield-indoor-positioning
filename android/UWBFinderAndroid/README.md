# UWBFinder for Android

Android port of the UWBFinder iOS app from the UWB Stella-Shield project. It connects to the Stella tag over Bluetooth LE, reads the UWB distance to the Shield anchor, and combines it with the phone heading in a radar display.

```
[Shield]  <-- DS-TWR ranging -->  [Stella]  -- BLE notify -->  [Android app]
 stationary,                      carried,                     Kotlin + Jetpack Compose,
 UWB only                         UWB + BLE                    BLE central + rotation-vector sensor
```

The port follows the iOS source file by file and adds no features.

| iOS (Swift) | Android (Kotlin) | Role |
|---|---|---|
| `UWBFinderApp.swift` | `MainActivity.kt` | entry point, permissions, lifecycle |
| `ContentView.swift` | `ContentScreen.kt` | screen layout, status texts, legends |
| `BLEManager.swift` | `BleManager.kt` | scan, connect, distance notifications |
| `HeadingManager.swift` | `HeadingManager.kt` | phone heading |
| `RadarModel.swift` | `RadarModel.kt` | smoothing, heading fusion, 60 Hz animation |
| `RadarView.swift` | `RadarView.kt` | radar drawing |

## Verification status

Built and launched on 21 November 2026:

- Gradle sync and `assembleDebug` in Android Studio 2026.2.2 on macOS: successful, no errors or compiler warnings.
- The debug APK installed on an emulator (Medium Phone, API 37). The app started, showed the radar screen and asked for the Bluetooth permission. Logcat showed no crash.
- Not yet tested on a phone with Stella. The emulator has no BLE link to the hardware.

Checks done before the first build:

- `RadarModel.kt` was compiled and replayed against a line-by-line transcription of `RadarModel.swift` on a synthetic 90 s trace (750 distance readings, 5400 animation frames). The outputs agreed to within 2e-13.
- `RadarModel.kt`, `BleManager.kt` and `HeadingManager.kt` compile without errors or warnings with Kotlin 2.0.21 against the Android API 35 platform library.

## Toolchain

| Component | Version |
|---|---|
| Android Gradle Plugin | 8.7.3 |
| Gradle (wrapper) | 8.9 |
| Kotlin and Compose compiler plugin | 2.0.21 |
| Compose BOM | 2024.12.01 |
| `androidx.activity:activity-compose` | 1.9.3 |
| `kotlinx-coroutines-android` | 1.8.1 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |

A newer Android Studio may offer to upgrade the Android Gradle Plugin. The project does not depend on anything specific to these versions.

Gradle 8.9 cannot run on JDK 25, which recent Android Studio versions bundle. On first import, choose JDK 21 for Gradle when Android Studio asks (Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK).

## Build and run

1. Open the `UWBFinderAndroid` folder in Android Studio and let the Gradle sync finish.
2. Connect a phone with USB debugging enabled. The emulator cannot reach a real BLE device, so a phone is required.
3. Run the `app` configuration.

Flash the boards as described in the iOS setup guide: `shield_controlee.ino` on the Shield and `stella_ble_tracker.ino` on Stella. The GATT UUIDs in `BleManager.kt` are the ones in the Stella sketch:

- Service: `A1E8F400-EE45-4832-A144-6F3E7F0D6B01`
- Distance characteristic: `A1E8F401-EE45-4832-A144-6F3E7F0D6B01` (little-endian float, meters, notify)

## Permissions

| Android version | Runtime permission requested | Why |
|---|---|---|
| 12 and later (API 31+) | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` | scan for and connect to Stella |
| 8 to 11 (API 26 to 30) | `ACCESS_FINE_LOCATION` | Android requires it for BLE scans on these versions |

The scan permission is declared with `neverForLocation`. The heading sensor needs no permission. If the permission is refused, the status line asks the user to grant it in Settings.

On Android 11 and earlier, BLE scans return no results while the system Location switch is off.

## Using it

1. Power the Shield near the object to be found.
2. Carry Stella, powered from its battery or a power bank.
3. Open the app. It scans for Stella, connects, and draws the radar once a distance and a heading are available.
4. Walk around for a few seconds. The white "anchor direction" arrow appears and becomes more opaque as its confidence grows.

## Tuning

The constants are the same as in the iOS app and sit in the companion object of `RadarModel`:

- `NEAR_M` / `FAR_M`: distance range mapped to the green to red gradient (0.3 m and 4.0 m)
- `DIST_SMOOTH` / `HEADING_SMOOTH`: smoothing factors for distance and heading
- `ARROW_LERP`: how fast the arrows follow their target angle

## Differences from the iOS app

- Heading source. iOS uses Core Location and reports true heading when available. Android uses the rotation-vector sensor, which is referenced to magnetic north. The fusion works the same way; the compass labels can differ by the local declination.
- Heading filter. `HeadingManager` forwards a new heading only after a change of 1 degree, which is the default `headingFilter` of Core Location. This keeps the update pattern of the fusion close to the iOS one.
- Permissions. iOS asks for location access for the compass. Android asks for Bluetooth access, or for location access on Android 11 and earlier, and the denied-permission message is worded accordingly.
- Orientation. The activity is locked to portrait, because the heading is that of the top edge of the phone.
- Lifecycle. Scanning, the sensor and the animation stop when the app goes to the background and resume when it returns.
- Animation timer. iOS uses a 60 Hz `Timer`. Android posts a runnable on the main thread at the same rate, so the per-frame smoothing constants keep their meaning on 90 Hz and 120 Hz screens.
- Scan retries. Like the iOS app, the port rescans one second after a disconnection. Android limits how often an app may start a scan, so after several quick retries the system can delay the next scan.

## Icon

The launcher icons in `app/src/main/res/mipmap-*` were scaled from the 1024 px icon of the iOS app.
