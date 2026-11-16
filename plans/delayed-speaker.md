# The delayed speaker

A follow-on mode to the per-app capture: the phone speaker itself joins the
SendSpin group. While the phone sends, its native playback goes silent, and
a delayed local copy plays on the speaker, in step with what the rest of the
group hears. This plan holds the design; the capture half is built and
documented in [per-app capture](per-app-capture.md).

## Why the speaker must be silenced

The SendSpin path adds latency: capture, network, Music Assistant, group
scheduling. Native playback is instant. If both run, the listener hears the
track twice, once now and once when the group gets there. Silencing the
native output removes the direct copy, and a delayed copy fills its place.

Two measured facts make this cheap:

1. The playback capture taps the mix before the volume fader. Muting
   `STREAM_MUSIC` silences the speaker while the capture keeps its full
   signal (verified round: stream index 0, capture level 71 %, 16 blocks
   per second).
2. Per-app capture removes the feedback risk: a capture that follows one
   app does not hear the phone's own delayed output. Only a whole-mix
   capture would feed itself.

## Stage 1: fixed-delay PoC

The quick version, to feel the behaviour before protocol work:

1. **Mute on send.** On capture start, read and store the current
   `STREAM_MUSIC` index, then mute the stream. On stop, restore the index.
2. **Crash safety.** The stored index is also written to disk, so a
   force-stop cannot leave the phone muted forever. The next app start
   restores it.
3. **The delayed local track.** The capture blocks go to the server as
   today, and the same blocks feed a local `AudioTrack` through a ring
   buffer with a fixed delay. The delay is a tunable constant, set by ear
   against the group. Drift over a long session is an accepted limit of a
   fixed delay; the clock work in stage 2 removes it.
4. **Mute honesty.** The system volume slider reads zero while sending, and
   the notification should say why. If the user unmutes mid-send, treat the
   unmute as a request for local playback and stop enforcing the mute.

The open question the PoC must settle first is the stream the delayed track
lives on. The media mute kills our own playback if it shares the media
stream. Candidates are a non-media usage, such as navigation guidance, or a
system volume group. The first device test is a two-minute case: muted
media, plus an `AudioTrack` on the alternative usage, listening for whether
the track stays audible. If no alternative escapes the media mute on this
device, the design pivots to a purer shape: mute everything, the phone
speaker becomes a group member only, with no simultaneous local copy.

## Stage 2: the player role

The fixed delay is a stand-in for the real schedule. In stage 2 the phone
declares the SendSpin player role beside its source role, so Music Assistant
sees the speaker as a player, includes it in groups, and drives it. The
blocks then play under `SessionClock` timing from the server, which is the
correct sync.

Two spike questions, one per shape:

1. **Dual roles on one connection.** The hello payload carries
   `player@v1_support` beside the stream offer, so one connection declaring
   source and player reads as possible. The spike answers whether the
   server accepts both roles announced at once.
2. **Second connection.** If it does not, the player is a second connection
   with its own id. The receive-and-schedule runtime then lives beside
   `SourceSession` with no shared state beyond identity.

The protocol pieces exist already: the player state and command payloads in
`sendspin-protocol/src/main/kotlin/com/sendspin/protocol/protocol/MessagePayloads.kt`,
the stream formats in the same module, and the clock in
`sendspin-protocol/src/main/kotlin/com/sendspin/protocol/time/`.

## Suggested scenarios, BDD order

1. "Sending silences the phone": an app plays, the phone sends, the media
   volume reads muted while the capture keeps reading, and the earlier
   index is back after a stop.
2. "A crash restores the volume": kill the app mid-send, relaunch, the
   stored index is restored.
3. "The delayed copy plays": capture runs, a local track is audible within
   the set delay, and the media stream stays muted.
4. Stage 2 scenarios follow the protocol core tests for the player role:
   the dual-role hello, the schedule under `SessionClock`, and the group
   join through Music Assistant.
