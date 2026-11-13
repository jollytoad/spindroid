# BDD and Gherkin Test Harness

This document records the recommended harness for the Gherkin feature files in
`bdd/`. It covers the frameworks, the repository layout, the local runs on an
emulator and a real device, and the CI stages.

It is a recommendation only. Nothing here is implemented yet.

`bdd/source.feature` is the first example. It describes onboarding behavior
that the app does not have yet. Treat it as a target specification and mark its
scenarios as `@wip` until the screen exists.

## Decision summary

| Topic | Decision | Reason |
| --- | --- | --- |
| Gherkin runner | Cucumber-JVM | It is the standard Gherkin runner for Kotlin and Java. |
| JVM test engine | JUnit 5 | The Kotlin modules already use JUnit 5. |
| Android test engine | JUnit 4 through Cucumber-Android | Cucumber-Android uses JUnit 4. Keep the two runtimes separate. |
| Compose driver | Compose UI Test | It reads Compose semantics directly and waits for the UI to settle. |
| System dialogs | UiAutomator | Espresso cannot touch other apps or system dialogs. |
| Espresso | Deferred | It cannot see Compose nodes. Revisit only for intent checks or classic Views. |
| Robolectric | Deferred | Compose support adds complexity. Use it only if the emulator loop is too slow. |
| Appium | Not used | The app is Android-only. Appium adds a server and offers no benefit here. |
| Feature files | One copy under `bdd/features` | The same specifications serve the JVM and Android layers. |
| Network in UI tests | Fakes | The emulator does not pass multicast reliably. |
| Capture tests | Real device | The emulator supports playback capture poorly. |
| CI emulator | Gradle build-managed devices | They provision, boot, and tear down the emulator. |
| CI device farm | Firebase Test Lab, optional | It gives physical-device coverage. It cannot reach a LAN server. |

Cucumber is not a test framework by itself. It parses the feature files and
runs the step definitions. JUnit, Compose UI Test, and UiAutomator do the
actual work.

## Layers

| Layer | What it covers | Runner | Driver | Where |
| --- | --- | --- | --- | --- |
| Protocol | Noise, framing, clock, state machines | Cucumber-JVM | Plain Kotlin | JVM |
| App logic | View state and screen mapping | Cucumber-JVM | Plain Kotlin | JVM |
| App UI | Onboarding, pairing, player picker | Cucumber-Android | Compose UI Test | Emulator |
| Android system | Permissions and consent dialogs | Cucumber-Android | UiAutomator | Emulator or device |
| Capture | MediaProjection, foreground service, audio | Cucumber-Android | UiAutomator and test hooks | Real device |
| End to end | Music Assistant and real speakers | Cucumber or manual | Real app and server | LAN |

Run each scenario at the lowest layer that can prove the behavior. Do not run
the same UI scenario at every layer.

## Dependencies

Pin the versions in `gradle/libs.versions.toml`. Cucumber-Android has its own
release line and bundles a Cucumber-JVM version. Check its POM for the bundled
version. At the time of writing, `cucumber-android` 7.19.x bundles
Cucumber-JVM 7.34.x. Use that JVM line in the pure JVM modules, so the step
syntax and the tag expressions match. Check the release notes before an
upgrade, because Cucumber-Android moves more slowly than Cucumber-JVM.

### JVM modules

- `io.cucumber:cucumber-java`
- `io.cucumber:cucumber-junit-platform-engine`
- `org.junit.platform:junit-platform-suite`
- The existing `org.junit.jupiter:junit-jupiter`
- The existing `org.junit.platform:junit-platform-launcher`

### Android module

- `io.cucumber:cucumber-android`
- `androidx.test:runner`
- `androidx.test:rules`
- `androidx.test.ext:junit`
- `androidx.compose.ui:ui-test-junit4`
- `androidx.compose.ui:ui-test-manifest` as a debug dependency
- `androidx.test.uiautomator:uiautomator`
- `androidx.test:orchestrator` through `androidTestUtil`

Set the instrumentation runner in `app/build.gradle.kts`:

```kotlin
defaultConfig {
    testInstrumentationRunner =
        "io.cucumber.android.runner.CucumberAndroidJUnitRunner"
}
```

The Android test module uses JUnit 4 because Cucumber-Android requires it. The
JVM modules stay on JUnit 5. Do not force one runtime on both.

Espresso is not in the first dependency set. Add `espresso-intents` only when
there is a step that checks or stubs an outgoing intent. Add `espresso-core`
only when a classic View screen exists.

## Repository layout

Keep one copy of every feature file.

```text
bdd/
  features/
    onboarding.feature
    pairing.feature
    discovery.feature
    playback.feature
    capture.feature

app/src/androidTest/kotlin/com/sendspin/sender/test/
  RunCucumberTest.kt

app/src/androidTest/kotlin/com/sendspin/sender/bdd/
  steps/
  support/

sendspin-protocol/src/test/kotlin/com/sendspin/protocol/bdd/
  RunCucumberTest.kt
  steps/
  support/
```

Put feature files under `bdd/features`. Add that directory to the JVM test
resources and the Android test assets. Do not copy the files into each module.

```kotlin
// app/build.gradle.kts
android {
    sourceSets {
        getByName("androidTest").assets.srcDir(rootProject.file("bdd"))
    }
}
```

```kotlin
// sendspin-protocol/build.gradle.kts
sourceSets {
    test {
        resources.srcDir(rootProject.file("bdd"))
    }
}
```

Cucumber-Android looks for the options class in the test application id package
by default. That package is `com.sendspin.sender.test`. Put the runner there,
or set the `optionsAnnotationPackage` instrumentation argument.

The Android cucumber options class only supplies the glue package and the
default tag filter:

```kotlin
@CucumberOptions(
    features = ["features"],
    glue = ["com.sendspin.sender.bdd.steps"],
    tags = "not @manual and not @wip",
)
class RunCucumberTest
```

The JVM modules use the JUnit Platform suite:

```kotlin
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(
    key = GLUE_PROPERTY_NAME,
    value = "com.sendspin.protocol.bdd.steps",
)
class RunCucumberTest
```

Feature files must go into `androidTest` assets. Do not put them in the app
APK.

If both layers need the same test state, put a plain Kotlin `World` class in a
small shared module. Do not share UI steps with JVM steps.

## Tags

Tag every scenario with the layer it needs.

| Tag | Meaning |
| --- | --- |
| `@protocol` | Protocol behavior on the JVM |
| `@ui` | Compose screen test |
| `@emulator` | Runs on an emulator |
| `@device` | Needs real hardware |
| `@fresh` | Needs clean app data |
| `@network` | Needs a real LAN and Music Assistant |
| `@manual` | Not automated |
| `@wip` | Not implemented yet |

Use tag expressions to select a run:

- Required CI: `not @manual and not @wip`
- Pull request UI job: `@ui and not @device and not @network`
- Device job: `@device and not @manual`
- Nightly LAN job: `@network and not @manual`
- Specification report: `@wip`

The specification report shows pending scenarios without failing the required
build.

## Test seams

The onboarding scenarios need control over discovery. At the moment
`MainActivity` constructs `MassDiscovery` directly. A UI test would otherwise
start real mDNS.

Add a small injection seam such as an `AppContainer` or a factory. Do not add
Hilt only for this.

Use the seam to replace these dependencies:

- Discovery, so a fake Music Assistant can appear on the network.
- The account store, so sign-in state is known.
- The player manager, so the picker has a known list.
- The capture controller, so a UI test does not need a real projection token.

For the example feature:

- `Given a Music Assistant is present` installs a fake discovery result.
- `Then the Music Assistant name is shown` asserts a Compose semantic node.
- Only the `@network` version uses real mDNS.

Add `Modifier.testTag(...)` to important controls. Text selectors break when
the copy changes. Assert exact copy only where the feature requires it.

## Fresh install

`Given a fresh install` needs clean app state. Use Android Test Orchestrator
with `clearPackageData: true`. It runs each scenario in its own instrumentation
and clears the target app data between runs.

```kotlin
android {
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }
    defaultConfig {
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }
}
```

Cucumber-Android works with the Orchestrator. Check that every scenario starts
from a state it sets up itself.

If clearing all app data is too slow for the suite, use a debug-only reset hook
that clears the settings and key stores. Keep the Orchestrator for the
`@fresh` scenarios.

`GrantPermissionRule` can grant `RECORD_AUDIO` and `POST_NOTIFICATIONS` before
a test. It cannot grant MediaProjection consent. That consent is not a
permission.

## Local runs

### JVM

Run the protocol and client tests:

```sh
./gradlew :sendspin-protocol:test :music-assistant-client:test
```

Wire a Gradle property to `cucumber.filter.tags` in the test task. Then select a
layer:

```sh
./gradlew :sendspin-protocol:test -Pbdd.tags="@protocol and not @wip"
```

Start the reference server before the integration scenarios:

```sh
python3 tools/test_server.py --port 8927 --pairing-psk <value>
```

The JVM tests reach it at `127.0.0.1:8927`.

### Emulator

Install a system image and create an AVD:

```sh
sdkmanager "system-images;android-36;google_apis;arm64-v8a"
avdmanager create avd -n sendspin-api36 \
  -k "system-images;android-36;google_apis;arm64-v8a" \
  -d pixel_6
emulator -avd sendspin-api36
```

Use `arm64-v8a` on Apple Silicon and `x86_64` on Intel.

Run the UI scenarios:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.tags="@ui and not @device"
```

For a repeatable device, add a Gradle build-managed device:

```kotlin
android {
    testOptions {
        managedDevices {
            localDevices {
                create("pixel6Api36") {
                    device = "Pixel 6"
                    apiLevel = 36
                    systemImageSource = "aosp"
                }
            }
        }
    }
}
```

Then run:

```sh
./gradlew :app:pixel6Api36DebugAndroidTest
```

On a CI runner without a GPU, add:

```sh
-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
```

Prefer `aosp-atd` where an image exists for the API level. Check the current
ATD API support before you rely on it.

Emulator limits:

- The app reaches the host test server at `10.0.2.2`, not `127.0.0.1`.
- mDNS discovery is unreliable. Use the fake discovery object.
- Playback capture is poor. Do not prove the capture path here.

### Real device

Connect the phone and select it:

```sh
adb devices
export ANDROID_SERIAL=<serial>
```

Run the device scenarios:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.tags="@device and not @manual"
```

Or run the instrumentation directly:

```sh
adb shell am instrument -w \
  -e tags "@device" \
  com.sendspin.sender.test/io.cucumber.android.runner.CucumberAndroidJUnitRunner
```

On the real device:

- Use UiAutomator to accept the notification permission and the
  MediaProjection prompt.
- Put the phone and the laptop or Music Assistant server on the same Wi-Fi.
- Use the laptop's LAN address for the test server.
- Play a known tone to test capture. Assert the chunk count, the sample count,
  and the capture status. Do not rely on listening to the speaker.
- Test screen-off and battery-saver cases with `adb shell input keyevent 26`
  and the battery settings.
- Tag OEM-specific consent flows as `@manual` if UiAutomator is not reliable.

MediaProjection consent varies by OEM. Do not promise a fully automated
consent flow on every phone.

## CI

Use three stages.

1. **Pull request, fast.** JVM unit tests and JVM Cucumber scenarios. No
   emulator. This catches protocol regressions in minutes.
2. **Pull request, UI.** One emulator API level through Gradle build-managed
   devices. Run `@ui and not @device and not @network`.
3. **Nightly or release.** API 33, 34, and 36 emulator matrix. Run `@device` on
   a self-hosted phone or a device farm. Run `@network` only on a self-hosted
   runner with Music Assistant on the same LAN.

For GitHub Actions:

- Use `ubuntu-latest` with KVM enabled for the emulator.
- Use `actions/setup-java` and an Android SDK setup action.
- Add the swiftshader flag when the runner has no GPU.
- Upload JUnit XML, Cucumber JSON, screenshots, and `logcat` on failure.
- Use the Orchestrator or Gradle sharding for slow suites.

Cucumber-Android does not support the HTML and XML report plugins in the same
way as Cucumber-JVM. Keep the JSON output and turn it into a report on the
host.

Firebase Test Lab is useful for physical-device coverage. It cannot reach a
Music Assistant server on your LAN. Keep LAN scenarios on a self-hosted
device.

## Espresso

Espresso stays out for now. It cannot see individual Compose nodes, and it
cannot press system dialog buttons.

Reconsider it in two cases:

1. A step must check or stub an outgoing intent. Use `espresso-intents`.
2. A classic View screen or WebView enters the app. Use `espresso-core` or
   `espresso-web`.

Do not use Espresso to fake a real MediaProjection session. A stubbed result
has no valid system token, so the capture service fails when it calls
`getMediaProjection(...)`. Inject a fake capture controller instead.

`ActivityScenario`, `GrantPermissionRule`, and the Orchestrator are AndroidX
Test tools. They are often mentioned with Espresso, but they are separate.

## Risks

- Cucumber-Android is JUnit 4 based and moves more slowly than Cucumber-JVM.
  Prove the build with AGP 9.5 before writing many steps.
- The project has no `androidTest` source set and no test runner today. This
  starts from zero.
- Feature files in Android assets need explicit source-set configuration.
- MediaProjection consent varies by OEM.
- The emulator cannot prove playback capture or real mDNS.
- Assertions on exact UI copy are brittle. Prefer test tags for actions.
- Cucumber gathers undefined steps as failures. Keep planned scenarios tagged
  `@wip` and report them separately.

## Adoption order

1. Add the Cucumber dependencies and the `androidTest` source set.
2. Put the feature files under `bdd/features` and tag the example `@wip`.
3. Add one Cucumber runner and one smoke scenario: the app opens and the title
   is shown.
4. Add the fake discovery seam and the test tags before writing onboarding
   steps.
5. Add one JVM Cucumber scenario against `tools/test_server.py`.
6. Add the emulator Gradle task and one CI job.
7. Add real-device and LAN scenarios only after the emulator suite is stable.
8. Revisit Espresso, Robolectric, and a device farm only when a concrete need
   appears.
