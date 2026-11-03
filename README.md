# MacPad

MacPad turns a Samsung Galaxy Tab S9 FE (Android 16) into an extended second display for a Mac. The Mac app is a menu bar app that creates a virtual display, encodes it as H.264, and streams it to the tablet over USB-C (`adb reverse`) or Wi-Fi. The tablet app decodes the stream and sends finger and S Pen input back, which the Mac turns into mouse events. The Android app has no dependencies. Build from source only; there are no releases.

## Requirements

- Apple Silicon Mac on macOS 26 or later
- Xcode command line tools (`swift`)
- Android SDK platform-tools (`adb`), needed for USB
- JDK 17, to build the Android app
- A tablet with USB debugging turned on (for USB)

The Mac app is signed by `mac/build.sh` with the first "Apple Development" certificate in your keychain, or with the identity in `MACPAD_SIGN_IDENTITY` if set. A stable identity keeps the Screen Recording and Accessibility grants across rebuilds. With no such certificate the script signs ad hoc, and the permissions reset on every rebuild. The app is not notarized.

## Build and install

Mac:

```sh
cd mac && ./build.sh --install
```

This builds `dist/MacPad.app`, copies it to `/Applications`, and launches it. Without `--install` it only builds `dist/MacPad.app`.

Android, with the tablet connected over USB:

```sh
cd android
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
./gradlew installDebug
```

`./gradlew assembleRelease` also works. The release build is minified and signed with the debug keystore (`build.gradle.kts` sets no other signing config), so it is for personal use only. Gradle needs the Android SDK location, either in `android/local.properties` (`sdk.dir=...`) or in `ANDROID_HOME`.

## First run on the Mac

MacPad lives in the menu bar only; it has no Dock icon. It enables Open at Login on first launch, and you can toggle it from the menu.

1. Open System Settings, Privacy & Security, and allow MacPad under Screen Recording and under Accessibility. The menu shows "Allow Screen Recording…" and "Allow Touch and Pen Control…" while either permission is missing.
2. Quit and reopen MacPad after granting Screen Recording.

The menu also has:

- **Desktop Size**: "More space" (the default; 1536 x 960 on the tablet's 2304 x 1440 panel) or "Larger text" (1152 x 720).
- **Paired Tablets**: "Forget <name>" for each tablet paired over Wi-Fi.
- **Open at Login** and **Quit MacPad**.

The menu also shows connection status and the Mac's Wi-Fi address.

## USB

1. On the tablet, enable Developer options and USB debugging.
2. Plug it into the Mac and accept the RSA fingerprint prompt once.

MacPad watches `adb track-devices`. When the tablet appears it runs `adb reverse tcp:47800 tcp:47800` and launches the tablet app. MacPad looks for `adb` in `$ANDROID_HOME/platform-tools`, `$ANDROID_SDK_ROOT/platform-tools`, `~/Library/Android/sdk/platform-tools`, `/opt/homebrew/bin`, and `/usr/local/bin`, in that order. If none is found the menu says so (`brew install android-platform-tools` provides one).

## Wi-Fi

Put the Mac and tablet on the same network and open MacPad on the tablet. The Mac advertises Bonjour `_macpad._tcp` on port 47801, and the tablet lists the Macs it finds.

The first connection to a Mac pairs the devices: both screens show a 6-digit code, and the Mac asks you to Allow. After that the session is encrypted with AES-GCM, using keys from a P-256 ECDH exchange.

If your router blocks multicast and no Macs appear, use "Connect by address" on the tablet and enter the address shown in the Mac menu ("Wi-Fi address: ..."). The tablet remembers the Macs it has paired with and their addresses. If the Mac's address changes, typing the new one reconnects without pairing again.

## Input

| Input | Action |
| --- | --- |
| S Pen hover | Moves the cursor |
| S Pen tip | Click and drag, with pressure and tilt |
| S Pen side button | Right-click |
| Palm | Rejected while the pen is near the screen |
| Finger tap | Click |
| Finger drag | Drag |
| Two-finger scroll | Scroll |
| Two-finger tap | Right-click |

Pinch and long-press are not supported.

## Behavior

- The display follows tablet rotation. In portrait it is 960 x 1536 (with More space).
- After a disconnect the display is kept for 10 seconds, so windows come back when the tablet reconnects.
- Pressing Back on the tablet pauses USB auto-connect until the app is reopened.
- One tablet at a time. If the same tablet reconnects, it replaces its old connection; a different tablet is refused while one is connected.

## Performance

Measured on this hardware with a 2304 x 1440 H.264 stream. Wi-Fi figures are from a home router; your network will change them.

| | USB | Wi-Fi |
| --- | --- | --- |
| Frame rate | ~57 fps | ~52-55 fps after warmup |
| Bitrate | ~10 Mbps typical (24 Mbps cap) | 5-8 Mbps (14 Mbps cap) |
| Mac encode | ~9.7-9.9 ms | ~9.7-9.8 ms |
| Tablet decode | ~20 ms typical (17.7-28.2) | ~23-27 ms |
| Round trip | 2.6-12.8 ms | 12-52 ms |

## Troubleshooting

- "Your Mac could not create the extra display", or screen capture errors: quit and reopen MacPad on the Mac, then connect again.
- No Macs listed over Wi-Fi: use "Connect by address" on the tablet.
- Logs on the Mac: `log show --predicate 'subsystem == "com.azhardhiaulhaq.macpad"' --info`
- Logs on the tablet: `adb logcat -s MacPad`

## Limits and risks

- The virtual display uses the private `CGVirtualDisplay` API, which a macOS update may break.
- Pen pressure and tilt reach only apps that read tablet events.
- No audio.
- The app is not notarized.

## Repository layout

- `mac/`: Swift menu bar app and `build.sh`
- `android/`: Kotlin tablet app (Gradle)
- `docs/PROTOCOL.md`: wire protocol
- `art/`: frog SVGs

## License

MIT
