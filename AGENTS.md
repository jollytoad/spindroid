# AGENTS.md

An Android app (`app.gubbins.spindroid`) that captures phone audio and streams
it into the SendSpin network. Source mode only is implemented; server mode is
planned.

Never read or write `HUMANS.md`. That file is for humans only.

## Modules

| Module | What it is |
| --- | --- |
| `:app` | The Android app. Compose UI. |
| `:sendspin` | Plain Kotlin/JVM protocol library (Noise, framing, clock filter). |
| `:mass` | Plain Kotlin/JVM client for the Music Assistant API. |

`:sendspin` and `:mass` have no Android dependency. Their tests run on the
JVM without a device or emulator.

Some files under `plans/` use stale module names (`:sendspin-protocol`,
`:music-assistant-client`) and stale packages (`com.sendspin.sender`).
The real modules are `:sendspin` and `:mass`; the real package is
`app.gubbins.*`. Trust `settings.gradle.kts` and the source trees.

## Commands

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./gradlew :app:assembleDebug            # build the debug APK
./gradlew :sendspin:test :mass:test     # JVM library tests
./gradlew :app:test                     # app unit tests (JUnit 5)
./gradlew :sendspin:test --tests "app.gubbins.sendspin.session.InteropTest"
./gradlew :app:runBdd                  # Gherkin suite on a connected device
```

Set `JAVA_HOME` before any Gradle call. The build needs JDK 21.

The Gradle configuration cache is on. Prefer inputs like `-P...` properties
over editing tasks, so the cache stays valid.

## BDD on a device

`./gradlew :app:runBdd` installs both APKs, runs the Cucumber instrumentation,
and leaves the app installed. Do not use `connectedDebugAndroidTest` for this:
it uninstalls the app when it ends, and `am instrument` exits zero even when
scenarios fail. `runBdd` parses the output and fails the build on failure.

- `ANDROID_SERIAL=<serial>` picks the device when several are connected.
- A plain `./gradlew :app:runBdd` with no `-Pbdd.tags` runs every scenario in
  one pass. Use that when a Music Assistant and an account are available; the
  two tag runs below split the same suite for machines without them.
- `-Pbdd.tags="not @requires-music-assistant"` skips the scenarios that need
  a Music Assistant on the LAN. Cucumber reports no tests then, which passes.
- Scenarios that sign in need an account. `runBdd` reads it from
  `bdd/credentials.properties` (gitignored, holds a password), and
  `-Pbdd.ma.user` / `-Pbdd.ma.password` override the file. Without an account
  those scenarios skip and the suite still passes.
- Gherkin files live in `bdd/` and are wired in as `androidTest` assets only.
  Never put them in the app APK.

The emulator supports playback capture poorly and passes mDNS unreliably.
Use a physical device for capture and discovery work.

## Nightly releases

`.github/workflows/nightly.yml` releases a dated debug APK when a
production file changed since the last nightly. Each release adds an
entry to `CHANGELOG.md` and pushes the file to `main` as
`github-actions[bot]`. Pull `main` before you push work that follows
a nightly, or the push is rejected.

## Sandbox limits (agent environment)

`pkgx` fails inside the agent sandbox with `Os { code: 17, kind:
AlreadyExists }`. That is a sandbox limit, not a broken install. The build
does not need `pkgx`; it runs on the Homebrew JDK and the Gradle wrapper.

The sandbox cannot write `~/.android`, which breaks
`:app:validateSigningDebug`. Point the Android tools at a gitignored scratch folder in the project instead:

```sh
export ANDROID_PREFS_ROOT="$PWD/.android-home"
mkdir -p "$ANDROID_PREFS_ROOT/.android"
```

`local.properties` holds `sdk.dir` and is machine-specific. Keep it out of
version control. Use `.tmp/` for scratch files; it is gitignored.

## Build quirks

- AGP 9 builds Kotlin itself. `:app` applies no Kotlin plugin. The
  `org.jetbrains.kotlin.android` plugin no longer exists.
- Plugin versions are hardcoded in the `buildscript` block of the root
  `build.gradle.kts`. Version catalog accessors are unavailable there. The
  Compose compiler version must match the Kotlin that AGP bundles (2.2.10).
- Unit tests run on JUnit 5. The Android Cucumber instrumentation runs on
  JUnit 4. Do not force one runtime on the other.
- `:sendspin` and `:mass` use Moshi with KSP codegen. No reflection at
  runtime.
- The debug signing key is `app/debug.keystore`, checked in. It signs debug
  builds only and carries no secret. Release builds use a key that is never
  checked in.
- `sendspin/src/test/.../session/InteropTest.kt` launches the independent
  Python server `tools/test_server.py` itself. The test skips when `python3`
  or its `cryptography` package is missing. `pkgx uvx --from aiosendspin
  aiosendspin` starts the real reference server when needed.

## Where to read more

- `plans/README.md` - project index, decisions, phase list.
- `plans/architecture.md`, `plans/decisions.md` - module layout and the
  reasons behind the design.
- `plans/toolchain.md` - SDK setup, versions, and the crypto differences
  between the JVM and Android (X25519, ChaCha20-Poly1305). The Conscrypt
  quirks in there are the reason the crypto code tries multiple algorithm
  names.
- `plans/bdd-testing.md`, `plans/testing-strategy.md` - the test layers.
  Treat them as a target, not as a description of what exists.
- The `android-cli` skill (`.agents/skills/android-cli/`) covers device and
  emulator work: install, screenshots, UI inspection. Load it before touching
  devices or AVDs.
