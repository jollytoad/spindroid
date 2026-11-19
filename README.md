# SpinDroid

<p align="center">
  <img src="art/logo.svg" alt="The SpinDroid logo" width="160">
</p>

A SendSpin sender for Android.

> [!IMPORTANT]
> This project is a work in progress. It has no releases, and the
> screens and the features change without notice.

[HUMANS.md](HUMANS.md)

## The problem

I have subjected my family to a
[Music Assistant](https://www.music-assistant.io/) and some connected
players of various brands and protocols, including some SendSpin players.

My family like the 'Cast' experience, directly from their own phones.

I've not found an app that really fits their needs.

## How this app solves it

SpinDroid does not ask the app to cast. The app plays locally. SpinDroid
takes the sound at the mix and streams it, timestamped and encrypted, to
your [SendSpin](https://www.sendspin-audio.com/build/spec/) server. Every
media app on the phone becomes a source for the whole player set:

1. The app shows two cards. The Source card lists the media apps that hold a
   playing session. The Destination card lists the players your network
   offers.
2. You pick an app and a player, and press Connect.
3. The app captures the audio of the picked app and sends it to the server.
4. The server resamples the audio and distributes it to the picked player,
   or to any speaker group.

The phone appears in [Music Assistant](https://www.music-assistant.io/)
under Live Inputs, like any other source. Transport controls on the Source
card rows drive the picked app directly.

The app speaks the SendSpin `source@v1` protocol: the Noise `KKpsk2`
handshake, timestamped PCM audio, and the clock filter that keeps the phone
and the server in step. The protocol library lives in the `:sendspin` module
and has no Android dependency, so its tests run on the JVM.

## What you need

- An Android 13 phone or newer (API 33).
- A [Music Assistant](https://www.music-assistant.io/) server on your
  network. Music Assistant embeds a SendSpin server, the
  [aiosendspin](https://github.com/Sendspin/aiosendspin) reference
  implementation. The phone and the server must sit on the same network.
- A Music Assistant account to sign in, if you want the app to pair itself.
  The Destination card also offers pairing without an account.
- A physical device for the capture path. The emulator supports playback
  capture poorly.

## Install

There is no release build yet. Sideload the debug APK:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Build it yourself

The build needs a JDK 21, the Android SDK, and the Gradle wrapper that this
repository pins.

1. Install the JDK and the Android command line tools:

   ```sh
   brew install --cask temurin@21 android-commandlinetools
   ```

2. Install the SDK packages:

   ```sh
   export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
   sdkmanager --licenses
   sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
   ```

3. Point Gradle at the SDK. Put this in `local.properties` at the repository
   root, and keep the file out of version control:

   ```properties
   sdk.dir=/opt/homebrew/share/android-commandlinetools
   ```

4. Set the JDK and build:

   ```sh
   export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
   ./gradlew :app:assembleDebug
   ```

`pkgx.yaml` pins the full toolchain. Run `pkgx dev` if you use
[pkgx](https://pkgx.sh/) and want the pinned versions. The build does not
require it.

## Development

```sh
./gradlew :sendspin:test :mass:test   # protocol and Music Assistant tests, JVM only
./gradlew :app:test                   # app unit tests
./gradlew :app:runBdd                 # Gherkin suite on a connected device
```

- [plans/README.md](plans/README.md) - the project plan, the decisions, and
  the phase list.
- [plans/toolchain.md](plans/toolchain.md) - the full toolchain and SDK
  setup, including the sandbox workarounds.
- [AGENTS.md](AGENTS.md) - the instructions for coding agents.
- The protocol facts come from the
  [SendSpin specification](https://www.sendspin-audio.com/build/spec/).
  The closest reference implementations are
  [sendspin-mac](https://github.com/Sendspin/sendspin-mac) (Swift) and
  [aiosendspin](https://github.com/Sendspin/aiosendspin) (Python), the
  server inside Music Assistant.

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
