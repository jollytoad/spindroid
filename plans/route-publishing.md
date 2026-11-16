# Route publishing (cast) experiment

An experiment beside the capture path: instead of capturing the audio of a
media app, publish a SendSpin route, so the app's own target picker offers
SendSpin and the app hands its audio over. The result was negative, and the
uncommitted PoC code stays in the tree as the record of what was tried. This
plan saves the outcome from the conversation history.

## The idea

Android media apps list cast targets in two surfaces: the in-app cast button
that Google Play services fills by discovery, and the system output
switcher. A third-party app can publish into both with two different
provider services. If a media app picks our route, it streams its audio to
the route receiver, and SendSpin is in the path without any capture.

## What was built

Two providers, both in `app/src/main/kotlin/com/sendspin/sender/routing/`:

| File | Surface | Detail |
| --- | --- | --- |
| `SendSpinRouteService.kt` | androidx `MediaRouteProviderService` | publishes a live-audio route plus a remote-playback route control filter, exported under the `android.media.MediaRouteProviderService` action |
| `SendSpinSystemRouteService.kt` | platform `MediaRoute2ProviderService` | system router route with `DEVICE_TYPE` and the live audio feature; guards `setSuitabilityStatus`, which exists from API 36 only, so the code runs on the Android 14 device |

Supporting changes: both service declarations in the manifest, and the
`androidx.mediarouter:mediarouter:1.8.1` dependency in
`gradle/libs.versions.toml` and `app/build.gradle.kts`.

## What worked: discovery

The plumbing is correct on the Android 14 Nokia XR20. Both log lines
appeared during the runs:

```
A media app is reading the SendSpin routes
The system is reading the SendSpin routes
```

1. Google Play services bound the androidx provider and scanned the routes.
   This is the door the in-app cast pickers read through.
2. `dumpsys media_router` showed the system router carrying the published
   route. The platform side registered it.

So a route publisher that follows these two APIs is discoverable. The
failure is not in publishing.

## What did not work: the apps

The target pickers of the reference apps never showed SendSpin:

1. Tidal, with a track playing, listed only Google Cast devices in its
   in-app picker. The published route did not appear.
2. The system output switcher on Android 14 did not surface the route either.

## Why, and the untested lever

Tidal speaks the Google Cast protocol against real Cast devices. Its audio
flows from the app to the Cast device through server-side streams with DRM.
A published MediaRouter route is a different contract: the app is expected
to move its playback into it. Tidal offers no in-app path that does that for
a third-party route, so neither a route intent nor cast emulation can pull
its audio.

One lever was never testable: the API 36 `suitability status` call
(`setSuitabilityStatus`) marks a route as suitable for a specific app. The
Android 14 device cannot run it. It could change how the system switcher
ranks the route, but it does not change the app-side contract that Tidal
does not implement.

## The consequence

Route publishing is a dead end for moving a cast-style app's audio without
its cooperation. The viable path is the opposite direction: let the app play
locally and take the sound at the mix. That is the capture path, per
[per-app capture](per-app-capture.md), and the group-side design in the
[transfer plan](media-transfer-to-music-assistant.md). The routing code has
no plan to return; its cleanup is a straight deletion of the two files, the
manifest entries, and the mediarouter dependency, or a keep-dormant decision
at API 36 devices.

## PoC inventory

| File | State |
| --- | --- |
| `app/src/main/kotlin/com/sendspin/sender/routing/SendSpinRouteService.kt` | untracked, experimental |
| `app/src/main/kotlin/com/sendspin/sender/routing/SendSpinSystemRouteService.kt` | untracked, experimental |
| `app/src/main/AndroidManifest.xml` | carries the two routing service entries on top of the capture listener entry |
| `gradle/libs.versions.toml`, `app/build.gradle.kts` | carry the mediarouter dependency |
