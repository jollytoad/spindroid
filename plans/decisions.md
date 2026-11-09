# Decisions

## Resolved

These answers came from the project owner. The plans in this folder follow them.

### 1. The server is Music Assistant

The plan targets Music Assistant. It is the reference server, and it runs the
SendSpin Source plugin, which shows source clients under **Live Inputs**.

- The app dials `ws://<ma-host>:8927/sendspin`.
- The user pastes the pairing token into the Music Assistant pairing screen.
- The user then picks the phone as an input in Music Assistant.

### 2. The minimum version is Android 13, API 33

| Item | Value |
| --- | --- |
| `minSdk` | 33 |
| `targetSdk` | The current stable version at build time |
| `compileSdk` | The current stable version at build time |

This drops the work for API 29 to 32. It also removes the need for
BouncyCastle, because the platform provides every primitive the Noise
handshake needs.

### 3. No FLAC for now

PCM only. Every server accepts PCM, so the app works with this alone.

The cost is bandwidth. At 48 kHz, 16-bit, stereo, PCM uses about 192 kB/s,
which is 1.5 Mbit/s. That is fine on a home network.

The FLAC encoder stays out of the plan. The source role already supports a
codec header, so adding FLAC later means writing an encoder and filling the
header. No other part of the code changes.

### 4. Sideload first, F-Droid later

The first builds are a signed APK that the user installs by hand.

For the F-Droid build later, keep these points in mind:

- Use only libraries with a free software licence.
- Keep the dependency list small. Every dependency needs a licence review.
- Write the app description for the F-Droid listing. It must explain the audio
  capture.
- Keep the build reproducible.

### 5. The modes are exclusive, and relay is not a goal

The app runs either source mode or server mode. It never runs both.

The phone never forwards another client's audio to its players. That work is
not planned.

### 6. The pairing token target is not decided yet

The most likely targets are the Music Assistant web interface and the Music
Assistant mobile app. The token format is fixed by the spec, so this only
affects the instructions in the app.

### 7. The user interface stays minimal

The app shows only what the protocol needs. The extras stay out of the plan:

- No device picker.
- No per-player volume control in source mode.
- No display for server mode beyond the connected players.

### 8. Both cipher suites, with a default chosen for the hardware

The client picks the suite in `client/init`, so no negotiation is needed. The
server must support both suites, which every conformant server does.

| Suite | Use it when |
| --- | --- |
| `25519_AESGCM_SHA256` | The default. Android 13 devices nearly always have hardware AES-GCM. |
| `25519_ChaChaPoly_SHA256` | The user picks it, or the device has no hardware AES. |

Detect hardware AES support with a short benchmark at startup. Measure the
time for a fixed number of AES-GCM operations. Compare it against a fixed
threshold. Cache the result.

A benchmark adds a small startup cost and can vary between runs. Keep the
threshold conservative, so the app picks AES-GCM on most devices.

### 9. The app never plays audio

The phone holds the source role in source mode, and it is the server in server
mode. It never holds the player role.

### 10. Two package managers

| What | Source |
| --- | --- |
| JDK, Gradle, Kotlin, Python, uv, git, OpenSSL, jq, curl | pkgx, from `pkgx.yaml` |
| The Android SDK | Homebrew, then `sdkmanager` |

The SDK is a large shared install, and Homebrew keeps it current. Android
Studio can point at the same SDK root. The language toolchain stays in pkgx,
so the same file works in CI.

The Homebrew setup:

```sh
brew install --cask temurin@21
brew install --cask android-commandlinetools
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
sdkmanager --licenses
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

Use `temurin@21`, not the plain `temurin` cask. The plain cask now ships JDK
27.

See [toolchain.md](toolchain.md) for the details.

## Still open

### Where the user pastes the pairing token

See item 6 above. This is a copy and paste task, not a code task.

### The exact AudioRecord settings

The plan uses a voice source that avoids the automatic gain control. The
settings need a test on a real device. The goal is a flat signal with no
noise gate.

### The notification text and the privacy wording

The app must explain what it captures and where the audio goes. The wording
depends on the F-Droid listing and the sideload instructions.

### The app name and the package identifier

No decision yet. The package identifier should stay stable once the first
build is signed, because it names the pairing records on the device.
