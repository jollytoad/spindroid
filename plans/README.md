# SendSpin Sender for Android - Plan Index

An Android app that captures audio on the phone and sends it into the SendSpin
network.

## What the app does

The app runs in two modes.

**Source mode.** The app is a SendSpin `source@v1` client. It connects to a
SendSpin server such as Music Assistant and streams timestamped audio. The
server resamples the audio and distributes it to every player. Music Assistant
shows the phone under **Live Inputs**, so the user plays it to any speaker or
group.

**Server mode.** The app runs a SendSpin server. SendSpin players connect to
the phone and receive the captured audio directly. This mode works with no
Music Assistant installed.

Each mode has a connection-direction setting.

| Setting | Behaviour |
| --- | --- |
| Dial out | The app opens the WebSocket to a server address the user enters once. |
| Accept incoming | The app listens on the local network and advertises itself over mDNS. The other side dials the phone. |

The spec allows only one direction at a time. The app must stop advertising
when it dials out, and stop dialing out when it advertises. The app enforces
this rule in one place.

Phase 1 ships one direction only, and one capture source only. The phone
listens on the local network, the server dials in, and the phone captures the
system audio. The dial-out path and the microphone capture were dropped, and
the screen has no picker for either. The phone test never proved them. They
may come back in a later phase.

## Decisions taken

The full list is in [decisions.md](decisions.md). The short version:

| Topic | Decision | Reason |
| --- | --- | --- |
| Architecture | Both source mode and server mode in one app | The owner asked for both. |
| The modes | Exclusive. The app runs one at a time. | Relay is not a goal. |
| Target server | Music Assistant | It shows source clients under Live Inputs. |
| Capture | The system audio only | The microphone path was dropped in phase 1. The phone test never proved it. |
| Connection direction | Accept incoming only | The dial-out path was dropped in phase 1. The phone test never proved it. |
| Pairing | Pairing token first, dynamic code later | The token flow needs no extra crypto code. |
| Minimum version | Android 13, API 33 | The owner decided this. It removes the need for a crypto library. |
| Noise library | Hand-rolled `KKpsk2` on the platform JCA | The spec requires the PSK to be chosen after reading Noise message 1. Stock libraries bind the PSK at construction. |
| Cipher suite | Both suites. AES-GCM by default. | The client picks the suite, so no negotiation is needed. |
| Clock filter | Port of the C++ reference in `Sendspin/time-filter` | The spec names it as the reference implementation. |
| WebSocket, accept incoming | Java-WebSocket | Plain Java library. It runs on Android without a server framework. |
| JSON | Moshi with KSP codegen | No reflection at runtime. |
| Audio codec | PCM only | Every server accepts PCM. FLAC is not planned yet. |
| Key storage | EncryptedSharedPreferences | It holds the identity key and the PSKs. The spec requires them to survive reboots. |
| Distribution | Sideload first, F-Droid later | Keeps the first builds simple. |
| Player role | Not used | The phone never plays audio. |
| Toolchain | pkgx for the languages, Homebrew for the Android SDK | See [toolchain](toolchain.md). |

## Phase overview

| Phase | Scope | Depends on |
| --- | --- | --- |
| [Phase 0](phase-0-protocol-core.md) | Project skeleton and the tested protocol core | none |
| [Phase 1](phase-1-source-mode.md) | Source mode, the first working app | Phase 0 |
| [Phase 2](phase-2-server-mode.md) | Server mode and mDNS discovery | Phase 1 |
| [Phase 3](phase-3-polish.md) | Dynamic pairing code, signal reporting, operations | Phase 2 |

Phase 1 delivers the goal of the project on its own. Phase 2 adds the second
architecture.

## Other files

- [Architecture](architecture.md) - module layout, session state machines, and
  the protocol facts that shape the code.
- [Decisions](decisions.md) - the answers behind the plan, and the items still
  open.
- [Toolchain](toolchain.md) - the build tools, the Android SDK setup, and the
  commands for each task.
- [Testing strategy](testing-strategy.md) - how each part gets verified.
- [BDD testing](bdd-testing.md) - the Gherkin and Cucumber harness, the local
  runs, and the CI stages.
- [Media session metadata](media-session-metadata.md) - what Tidal and BBC
  Sounds publish about the playing track, from on-device probes.
- [Media transfer to Music Assistant](media-transfer-to-music-assistant.md) -
  the track, album, and radio transfer scenario over the MA API.
- [Per-app capture](per-app-capture.md) - the PoC that follows one media app,
  the phone proof runs, and what graduating it needs.
- [Route publishing](route-publishing.md) - the cast experiment and its
  negative result: discovery works, the apps do not bite.
- [The delayed speaker](delayed-speaker.md) - mute during send plus a local
  delayed copy, to put the phone speaker in the group.
- [The two-card sender UI](two-card-ux.md) - the Source and Destination
  cards, the state model, and the mock-up route for the primary screen.
- [Media control](media-control.md) - play, pause, and the other transport
  commands, addressed per app through the same session access.

## Sources

All protocol facts come from the SendSpin 1.0 specification at
`https://www.sendspin-audio.com/build/spec/`.

The closest reference implementations are:

- `Sendspin/sendspin-mac` - a Swift app that captures system audio and streams
  it to a SendSpin server. It is the closest match to this project.
- `Sendspin/aiosendspin` - the Python reference implementation. It is the
  server inside Music Assistant.
- `Sendspin/time-filter` - the C++ clock filter.
- `Sendspin/conformance` - the harness that tests implementations against each
  other.
