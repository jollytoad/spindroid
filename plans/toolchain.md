# Toolchain

The build needs a JDK, Gradle, the Android SDK, and a Python reference server.
Two package managers supply them.

| What | Source | Why |
| --- | --- | --- |
| JDK, Gradle, Kotlin, Python, uv, git, OpenSSL, jq, curl | pkgx, from `pkgx.yaml` | Pinned versions. No system changes. The same file works in CI. |
| The Android SDK | Homebrew, then `sdkmanager` | The SDK is a large shared install. Homebrew keeps it current, and Android Studio can point at the same root. |

## pkgx

The project root holds a `pkgx.yaml`. It declares the tools.

```yaml
dependencies:
  openjdk.org: ^21
  gradle.org: ^9
  kotlinlang.org: ^2
  gnu.org/make: latest
  python.org: ^3
  astral.sh/uv: latest
  git-scm.org: latest
  openssl.org: ^3
  stedolan.github.io/jq: latest
  curl.se: latest
```

Activate them with:

```sh
pkgx dev
```

Use `pkgx dev --shellcode` for a single shell session instead. It prints the
shell code and changes nothing on disk.

The `pkgx` package names differ from the command names. For example, the
command `sdkmanager` comes from the package `android.com/cmdline-tools`.

## The Android SDK

### Install

```sh
brew install --cask temurin@21
brew install --cask android-commandlinetools
```

`temurin@21` gives the system a JDK. The plain `temurin` cask now ships JDK
27. The Android Gradle Plugin needs JDK 17 or newer, and 21 is the safe
choice.

The `android-commandlinetools` cask sets the default SDK root to
`/opt/homebrew/share/android-commandlinetools`. It puts `sdkmanager`,
`avdmanager`, `lint`, `d8`, and `r8` into `/opt/homebrew/bin`.

### Install the SDK packages

```sh
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

sdkmanager --licenses
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

| Package | Why |
| --- | --- |
| `platforms;android-36` | The compile SDK. |
| `build-tools;36.0.0` | `aapt2`, `zipalign`, and the signer. |
| `platform-tools` | Gives `adb`. It also puts `adb` inside the SDK root, where Gradle looks for it. |

Install `platform-tools` through `sdkmanager`, not through the
`android-platform-tools` cask. The cask puts `adb` in `/opt/homebrew/bin`, but
Gradle looks for it inside the SDK root.

The emulator is optional. It needs a system image, and it supports playback
capture poorly. Use a physical device for the capture tests.

### Point Gradle at the SDK

Gradle reads the SDK path from `local.properties` in the project root.

```properties
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

Keep this file out of version control. It holds a path that is specific to
one machine.

## Component versions

| Component | Version | Note |
| --- | --- | --- |
| Android Gradle Plugin | 9.x | The current major release. |
| Gradle | 9.6.0 or newer | The wrapper pins the exact version. |
| JDK | 21 | The minimum is 17. |
| Kotlin | 2.3 or newer | The Kotlin Gradle plugin. |
| compileSdk | 36 | The highest level AGP 9 supports. |
| buildTools | 36.0.0 | The version AGP 9 expects. |
| minSdk | 33 | Android 13. |
| targetSdk | 36 | Match `compileSdk`. |

### A note on AGP 9 and Kotlin

AGP 9 builds the Kotlin for `com.android.application` and `com.android.library`
modules itself. The separate `org.jetbrains.kotlin.android` plugin is gone.

The `sendspin-protocol` module is a plain Kotlin library, not an Android
module. It keeps the `org.jetbrains.kotlin.jvm` plugin and is not affected.

## Commands

| Task | Command |
| --- | --- |
| Build the debug APK | `pkgx gradle :app:assembleDebug` |
| Run the protocol tests | `pkgx gradle :sendspin-protocol:test` |
| Build everything | `pkgx gradle build` |
| Install on a device | `adb install -r app/build/outputs/apk/debug/app-debug.apk` |
| Start the reference server | `pkgx uvx --from aiosendspin aiosendspin` |
| List the reference server's players | `pkgx uvx sendspin servers list` |

The Gradle wrapper downloads its own Gradle. The `gradle.org` package is a
fallback for when the wrapper cannot reach the network.

## The reference server

The tests need a SendSpin server. The Python reference implementation is the
one inside Music Assistant.

```sh
pkgx uvx --from aiosendspin aiosendspin --help
```

Use it to run the protocol tests against a known-good peer. The conformance
harness runs the same comparison automatically.

```sh
git clone https://github.com/Sendspin/conformance
pkgx uvx --from conformance conformance run --from aiosendspin --to sendspin-sender
```

## The sandbox and pkgx

The agent sandbox denies access to `~/Library/Application Support/`. The
`pkgx` command needs that path, so every `pkgx` call fails inside the sandbox
with:

```
Error: Os { code: 17, kind: AlreadyExists, message: "File exists" }
```

This is a sandbox limit, not a broken install. The `pkgx` command works
normally in a plain shell.

Two ways around it:

1. Give the agent access to the paths `pkgx` needs. These are `~/Library`,
   `~/.pkgx`, and `~/Library/Caches`.
2. Point `HOME` at a writable directory for the `pkgx` calls. This works, but
   it puts a second copy of every package in that directory.

```sh
HOME="$PWD/.tmp/pkgxhome" pkgx --query java
```

The Homebrew tools have no such problem. They live in `/opt/homebrew`, which
the sandbox can read.

## An independent check on the crypto

The Noise handshake has to match the specification byte for byte. `openssl`
gives an independent implementation to compare against, so a bug in one
implementation does not hide a bug in the other.

```sh
pkgx openssl version
```
