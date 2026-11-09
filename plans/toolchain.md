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

The versions below are the ones the project builds with. They are pinned in
`build.gradle.kts` and `gradle/libs.versions.toml`.

| Component | Version | Note |
| --- | --- | --- |
| Android Gradle Plugin | 9.5.0 | The current stable release. |
| Gradle | 9.9.0 | The wrapper pins the exact version. AGP 9.5 needs 9.6 or newer. |
| JDK | 21 | The minimum is 17. The `temurin@21` cask. |
| Kotlin | 2.3.21 | The Kotlin Gradle plugin. |
| KSP | 2.3.13 | Only the protocol module uses it, for the Moshi adapters. |
| Moshi | 1.15.2 | The JSON codec. |
| JUnit | 5.14.4 | The test framework. |
| compileSdk | 36 | The highest level AGP 9 supports. |
| buildTools | 36.0.0 | The version AGP 9 expects. |
| minSdk | 33 | Android 13. |
| targetSdk | 36 | Match `compileSdk`. |

The plugin versions sit in the `buildscript` block of the root
`build.gradle.kts`. A version catalog cannot be read from inside a
`buildscript` block, so those three versions are written out there and the
catalog holds only the libraries.

### A note on AGP 9 and Kotlin

AGP 9 builds the Kotlin for `com.android.application` and `com.android.library`
modules itself. The separate `org.jetbrains.kotlin.android` plugin is gone.

The `sendspin-protocol` module is a plain Kotlin library, not an Android
module. It keeps the `org.jetbrains.kotlin.jvm` plugin and is not affected.

### A note on the debug signing key

The debug build signs with `app/debug.keystore`, a key in the project rather
than in `~/.android`. The build tool would otherwise write the key to the
user's home directory, which is not always available. The key signs debug
builds only, so it carries no secret. A release build uses its own key, which
is never checked in.

## Commands

| Task | Command |
| --- | --- |
| Build the debug APK | `./gradlew :app:assembleDebug` |
| Run the protocol tests | `./gradlew :sendspin-protocol:test` |
| Build everything | `./gradlew build` |
| Install on a device | `adb install -r app/build/outputs/apk/debug/app-debug.apk` |
| Start the reference server | `pkgx uvx --from aiosendspin aiosendspin` |
| List the reference server's players | `pkgx uvx sendspin servers list` |

The Gradle wrapper downloads its own Gradle. The `gradle.org` package is a
fallback for when the wrapper cannot reach the network.

Set `JAVA_HOME` before building, so the build uses the JDK 21 that the
Android tools need:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
```

## The crypto, and where the platforms differ

The protocol module uses no crypto library. Everything comes from the
platform providers, and the JVM and Android differ in two places that matter.

### X25519

The Noise keys are the raw little-endian bytes of RFC 7748: the private key
is the 32-byte scalar, and the public key is the 32-byte u-coordinate. The
Java API does not use that order, and two ways of converting exist.

`XECPublicKeySpec` takes the u-coordinate as a `BigInteger`, which is
big-endian, so the bytes have to be reversed. The JDK also accepts the
u-coordinate directly in the X.509 form, where the last 32 bytes are the raw
little-endian value.

Android's Conscrypt provider rejects `XECPublicKeySpec` and
`XECPrivateKeySpec` outright. Its key factory accepts only
`X509EncodedKeySpec` and `PKCS8EncodedKeySpec`. The code in
`X25519.kt` uses the DER-wrapped key specs, which both platforms accept and
which carry the raw bytes unchanged.

`KeyPairGenerator` for `XDH` picks X25519 on its own. Android's generator
rejects an `AlgorithmParameterSpec`, so no parameter is passed.

### ChaCha20-Poly1305

The JDK names the transformation `ChaCha20-Poly1305` or
`ChaCha20-Poly1305/None/NoPadding`. Android's Conscrypt registers
`ChaCha20/Poly1305/NoPadding` and points the plain name at it.

The key algorithm name differs as well. The JDK expects
`ChaCha20-Poly1305`, while Conscrypt expects the base name `ChaCha20`. The
code in `CipherState.kt` tries each name on a fresh cipher and keeps the one
that works.

The nonce is the same on both platforms: four zero bytes, then the counter.
The counter is little-endian for ChaCha20-Poly1305 and big-endian for
AES-GCM, as the Noise specification defines in sections 12.3 and 12.4.

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

The build does not need `pkgx`. It runs on the Homebrew JDK and the Gradle
wrapper:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./gradlew :sendspin-protocol:test
```

## The sandbox and the Android preferences folder

The sandbox also denies access to `~/.android`. The Android Gradle plugin
reads that folder for the debug keystore and for its usage metrics. The
metrics failure is only a warning, but the keystore failure stops the build
with:

```
Execution failed for task ':app:validateSigningDebug'
> java.io.IOException: Operation not permitted
```

The task compares the configured keystore against the default one in
`~/.android`, and the comparison fails because the folder cannot be read.

Point the plugin at a writable folder instead:

```sh
export ANDROID_PREFS_ROOT="$PWD/.android-home"
mkdir -p "$ANDROID_PREFS_ROOT/.android"
./gradlew :app:assembleDebug
```

`ANDROID_PREFS_ROOT` is the standard Android environment variable for this.
A plain shell has a writable `~/.android`, so it does not need the variable.

## An independent check on the crypto

The Noise handshake has to match the specification byte for byte. The `cacophony`
corpus holds the official test vectors, and the Python `noiseprotocol` library
gives a second implementation to compare against, so a bug in one
implementation does not hide a bug in the other.

```sh
curl -sL https://raw.githubusercontent.com/haskell-cryptography/cacophony/master/vectors/cacophony.txt -o cacophony.json
```

The vectors for the `KKpsk2` pattern cover both cipher suites. The
`init_static` and `resp_static` fields hold the private static keys, and
`init_remote_static` and `resp_remote_static` hold the public keys of the
other party.

The Swift reference implementation that Music Assistant and the SendSpin
projects share lives in the `Sendspin/sendspin-mac` repository. Its
`NoiseTests.swift` carries the SendSpin specific vectors, with a prologue of
the raw bytes of the init messages.

