# Per-app capture

A PoC mode beside the whole-system capture: the user ticks the media apps to
follow, and the capture takes the sound of those apps alone. The work is
uncommitted PoC state in the tree. This plan records how it works, what the
phone runs proved, and what graduating it to a feature needs.

The metadata that the same media sessions publish (title, artist, ids,
adverts) is a separate topic, covered in
[media-session-metadata.md](media-session-metadata.md).

## How the selection reaches the capture

The flow has four parts, all in the uncommitted changes:

1. **Sessions with metadata.** `PerAppCapture` reads the media sessions with
   `MediaSessionManager.getActiveSessions`. The call needs a
   `NotificationListenerService`, which is the `PlayingAppsListener` stub in
   the same package, and a one-time grant of notification access. The list
   keeps every session that publishes media metadata, playing ones first,
   drops our own package, and resolves each app's name and uid through the
   package manager.
2. **Selection.** The ticked package names live in a state flow, in memory.
   The `Capture PoC: apps to follow` card on the main screen shows the
   listed apps with checkboxes, plus Refresh, Follow all, and a shortcut to
   the notification access settings.
3. **Uid resolution.** At capture start the service resolves the selected
   package names to uids with `PackageManager.getPackageUid`, fresh each
   time. A uid over zero goes into the set; an unresolvable package is
   dropped, because a uid of zero would name the system.
4. **The capture filter.** `SystemAudioCapture` builds the
   `AudioPlaybackCaptureConfiguration` with `addMatchingUid` alone when uids
   are selected, and with the usage filter when none are. The two filters
   must not combine: a player matches either way, so the usage list would
   pull every other app back in.

## What the phone runs proved

Runs on the Nokia XR20 against the three reference apps, one ticked app per
round, capture started through the picker card, Music Assistant paired over
the automatic pairing.

| Round | Tick | Capture filter log | Level while playing | Level after pause |
| --- | --- | --- | --- | --- |
| Tidal, music track | `com.aspiro.tidal`, uid 10263 | `[10263]` | 14 % | 0 % |
| Spotify, podcast episode | `com.spotify.music`, uid 10266 | `[10266]` | 28 % | 0 % |
| BBC Sounds, live radio | `com.bbc.sounds`, uid 10460 | `[10460]` | 55 % | 0 % |

Every round held a steady 16 blocks per second. Two stronger facts came from
cross-checks on the Spotify and BBC rounds:

1. **Pause is silence with flow.** A paused app stops the sound but not the
   block flow: the capture keeps reading 16 blocks per second of digital
   silence, and the level meter reads 0 %. A reader that wants a signal
   must look at the level, not at the block count.
2. **The filter is exclusive.** With Spotify ticked and paused, live radio
   from BBC Sounds played on the phone speaker and the level stayed 0 %.
   A playing app that is not ticked is invisible to the capture.

## Behaviour facts a real feature must keep

1. **The selection resolves by package name.** Uids are per install. Spotify
   got 10266 at install, and the probe-era runs showed a different uid for a
   previous install. A stored uid goes stale at reinstall or clear-data; the
   resolved-by-name design avoids it, and the phone runs confirmed the flow
   with a fresh install.
2. **The filter applies at capture start.** The configuration belongs to the
   media projection, so ticks taken while a capture runs change the next
   capture only. The card text says so: "The next capture follows N app(s)
   only."
3. **The selection is memory only.** A force-stop clears it back to the
   whole phone. Persisting the selection is part of graduating the PoC.
4. **Idle apps list too.** The first PoC kept the picker to sessions in
   `STATE_PLAYING`, and a paused app dropped off the list. A follow-up
   changed the rule: the picker lists every session that carries metadata,
   playing ones first and idle ones after, so a user can pre-tick a paused
   app and the capture follows it from the moment it plays. The metadata
   also puts a now-playing line on each row.
5. **Idle apps stay hidden from the capture.** Listing is not capturing:
   a pre-ticked app produces silence until it plays, which is the point of
   the pre-tick.
5. **Apps without a media session stay invisible.** Some games play without
   a session. The whole-mix capture covers them; the per-app picker cannot.

## The mute interaction

There is no public per-app mute on Android. The mute lever stays the media
stream, and it fits per-app capture for one measured reason: the playback
capture taps the mix before the volume fader. Muting `STREAM_MUSIC`
silences every app on the speaker while the capture keeps reading the
ticked apps at full strength. Verified round: muted stream at index 0,
capture level 71 %, 16 blocks per second. The combination "follow one app,
silence the phone, and let a player role or a delayed local track put the
phone in the group" stands on these two behaviours. See
[media-transfer plan](media-transfer-to-music-assistant.md) for the group
side.

## What graduating the PoC needs

1. A failing BDD scenario first, in the project style: "the capture follows
   the ticked app alone" - tick an app, connect, playing app raises the
   level, an unticked playing app leaves it at 0 %.
2. Persist the selection (and restore it after an unclean exit if the mute
   mode lands with it).
3. Re-apply the filter on reconnect: today a dropped projection needs a new
   consent round anyway, so the filter rebuild is free there, but the
   selection-to-start contract must be stated in the UI.
4. Replace the PoC card with real UI: paused apps as disabled rows, the
   notification access grant flow in place of the bare settings shortcut,
   and per-app now-playing lines from the metadata work.
5. Decide the interaction with the whole-mix capture from phase 1: a radio
   button between "whole phone" and "chosen apps", or a checkbox list where
   no ticks means the whole phone as today.

## PoC inventory

The uncommitted files that carry the PoC:

| File | Part |
| --- | --- |
| `app/src/main/kotlin/com/sendspin/sender/capture/PerAppCapture.kt` | session listing with metadata, selection, uid resolution, transport control, `PlayingAppsListener` |
| `app/src/main/kotlin/com/sendspin/sender/capture/SystemAudioCapture.kt` | the `addMatchingUid` filter, unchanged usage path |
| `app/src/main/kotlin/com/sendspin/sender/service/SendSpinService.kt` | passes the selected uids at capture start |
| `app/src/main/kotlin/com/sendspin/sender/ui/MainScreen.kt` | the `Capture PoC: apps to follow` card, with the now-playing lines and the Play and Pause buttons |
| `app/src/main/AndroidManifest.xml` | the `PlayingAppsListener` declaration. The manifest also carries the two routing service entries from the separate experiment; see [route publishing](route-publishing.md). |

The grant for testing came from
`adb shell cmd notification allow_listener
com.sendspin.sender/.capture.PlayingAppsListener`. A real user grants the
same access under the notification access settings.
