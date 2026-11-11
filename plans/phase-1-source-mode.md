# Phase 1 - Source Mode

## Goal

Deliver the first working app. The phone captures audio and sends it to a
SendSpin server. Music Assistant shows the phone under Live Inputs, and the
user plays the phone's audio on any speaker or group.

This phase delivers the goal of the project on its own. Phase 2 is optional
extra work.

## Status

The code is complete. Every task below is done, and the test suite has 187
tests. The tests run on the JVM without an emulator, and they cover the
session, the pairing, the framing, the resampling, and the admission rules.

The app has one path only. The phone captures the system audio, waits for
the server to dial it, and sends the audio to that server. The microphone
capture and the dial-out path were dropped, because the phone test never
proved them. The screen has no picker for either.

The app is installed on a real phone. The server pairs with it, the clock
settles below a millisecond, the capture follows the sound that the phone
plays, and the audio comes out of the browser. The sound was distorted, and
the capture was the cause. It threw away half of every read. The phone now
sends every sample it captures, and the browser plays what the phone plays.

| Exit criterion | State |
| --- | --- |
| The app builds and installs on a physical device running Android 13 or later | Done. The app builds, installs, and runs on the test phone. |
| Music Assistant shows the phone under Live Inputs after the user pastes the pairing token | Done. Music Assistant paired with the phone through `pairing_psk` on the test phone. |
| The user can select the phone as an input in Music Assistant, and the audio plays on a speaker | Done on the phone. The browser plays the music the phone plays, without the jitter over the bass. |
| The clock error settles below 1 ms in the status panel | Done on the phone. The panel showed 723 to 996 µs. The filter settles at a few tens of microseconds over a local socket. |
| The app survives a screen-off and a network drop. It reconnects on its own. | Open. The phone keeps the port open and the advertisement up, so the server can dial in again. The server has to make the new connection, and that is not proven on the phone. |
| The app uses AES-GCM by default and falls back to ChaCha-Poly on a device without hardware AES | Written. The benchmark picks the suite, and the client offers the other one when the server refuses. |

| Deliverable | Where |
| --- | --- |
| The client session | `sendspin-protocol/src/main/kotlin/com/sendspin/protocol/session/SourceSession.kt` |
| The pairing flow and the PSK store | `.../psk/`, `.../pairing/` |
| The capture, the resampler, and the chunker | `app/.../capture/`, `sendspin-protocol/.../codec/` |
| The foreground service | `app/.../service/SendSpinService.kt` |
| The incoming path and the advertisement | `app/.../net/`, `app/.../discovery/` |
| The screen | `app/.../ui/MainScreen.kt` |
| A second implementation of the server side | `tools/test_server.py` |

### Notes on what the work found

Several things were harder than they looked. Most are now covered by a test.

1. **The framing layer adds the message type byte.** The session sends a
   JSON body without a type, and the fragmenter puts the type in front of
   it. Adding the byte twice produced a message the server could not read.

2. **The cipher counts the messages it carries.** A Noise channel decrypts
   in order, so a server cannot pick the last message out of a queue. The
   test server and the tests both drain the messages in the order they
   arrived.

3. **The audio chunk timestamp is a big-endian 64-bit number.** The
   reference implementation writes it that way. The Kotlin does the same,
   and the independent server in `tools/test_server.py` was the thing that
   got it wrong.

4. **The clock filter ignores a measurement with no error.** A round trip
   that took no time at all gives an error of zero, and the filter drops
   that sample. The filter needs three accepted samples before the session
   reports itself available, and each of them comes from a burst of eight
   exchanges.

5. **The app must send chunks of a fixed length.** The capture hands over
   whatever the device gives, which may be far more than the 150
   milliseconds the specification allows. The chunker gathers the samples
   until a chunk is full.

6. **The server may refuse the cipher suite.** The client measures the
   cipher on the device and picks the faster suite. When the server refuses
   it anyway, the client offers the other one, once.

7. **A server name can contain a space.** The pairing store wrote its
   records with spaces between the fields, so a name such as
   `Music Assistant (studio)` made the long-term record unreadable. The
   re-handshake after the pairing then failed, and Music Assistant dropped
   the connection. The store now writes a versioned record with a base64url
   name and key, and `PskRecordCodecTest` holds it there.

8. **The accept-incoming path needs the same clock and capture loop as the
   dial-out path.** The session reports itself available only after the
   clock converges, and Music Assistant sends the start command after that.
   Without the poll timer and the captured audio, the connection stayed up
   but no audio moved.

9. **The stream must not wait for the first audio.** The client opened the
   stream with the first captured block. Music Assistant gives the source
   about five seconds to answer the start command, and it reports "did not
   start streaming" when the answer is late. A source that is silent, for
   example because the phone plays nothing, never sent the answer. The
   client now opens the stream at once, and the audio follows.

10. **One path is enough.** The app had a microphone capture and a dial-out
    path next to the ones that work. Neither was ever proved on a phone,
    and both added a picker to the screen. They were dropped. The phone now
    captures the system audio and waits for the server to dial it, and the
    screen has one button. The dial-out path also needed a reconnect policy
    and a second transport, so it took the most code of the three.

11. **A WebSocket library can pass a null socket.** The library calls the
    error handler with no socket when the bind fails. The Kotlin override
    declared the parameter as non-null, so the branch that reports a failed
    bind never ran. The parameter is nullable now, and the failure reaches
    the status panel.

12. **A frame is not a sample.** A frame holds one sample for each channel,
    so a block of frames needs two shorts for every frame. The capture made
    its array with one short for every frame, so it held half the audio. The
    copy from the buffer filled that half and left the rest behind, and
    nothing said so. The server then got twenty milliseconds of audio for
    every forty milliseconds of time. It read that as a sample rate of half
    the announced rate, so it stretched the stream to fit, and the stretch
    is what the browser played. The bass took the worst of it, because a
    stretched low note changes more in phase than a stretched high one.

13. **The time of a sample follows from the frame count, not from the
    clock.** The capture worked the time of a sample out from the clock at
    the moment the read returned, less the length of the block. That
    estimate moves with the moment the read happens to return, so the times
    of the chunks wobbled by a millisecond or so. The server anchors the
    stream on those times, so the wobble reached the sound. The first
    sample now keeps the time the first read gave it, and every later
    sample follows from the number of frames before it.

14. **A wrong stream shows up in the numbers, not in the sound.** The
    browser hears the tune with a jittery bass over it, and the phone
    plays the same music cleanly from its own speaker. That tells the
    fault is in what the phone sends, but not where. A temporary dump of
    every chunk settled it in one run: the chunk count was half of what
    the chunker should make, and the timestamps jumped by thirty-two
    milliseconds again and again. The dump is gone now, but it is in the
    history. The commit that added it is `0b0d267`, and reverting that one
    commit brings it back.

15. **An empty activity set does not mean the client is unapproved.** The
    specification gates unpaired access on an operator approval, and it says
    that the approval is thrown away on a successful pairing. A paired phone
    therefore needs no approval, and Music Assistant never asks for one. The
    server declares the `playback` activity only for a client that sits in an
    active playback group. A source phone is never in one, so the server
    leaves the activity set empty and carries the approval in the active
    roles instead. The app read the activity set and told the user that the
    server had not approved the phone, while the phone was streaming. The
    app's own code gave the answer: it sends no sample at all unless the
    source role is active, so a running stream is the proof that the server
    had approved the phone. The status panel now follows the roles, and the
    line about approval is gone.

### The second implementation

`tools/test_server.py` is a server that was written from the specification
rather than from the Kotlin code. The interop test
(`sendspin-protocol/src/test/kotlin/com/sendspin/protocol/session/InteropTest.kt`)
drives the session against it over a real socket, and checks the whole
exchange: the handshake with the pairing key, the hello, the clock
synchronisation, the state report, the stream start, and the audio chunks.

The test skips itself when the machine has no Python, or no `cryptography`
package for it.

## Deliverables

1. A client session that offers the `source@v1` role.
2. A capture screen that starts the system audio capture.
3. A pairing screen that shows the pairing token.
4. A foreground service that keeps the app alive while it streams.
5. A status screen that shows the connection, the pairing state, and the
   stream state.

## Tasks

### 1. The client session

1. Write the session class. It owns the WebSocket, the Noise channel, the
   clock filter, and the source state.
2. Write the session so that it does not know how the connection is made.
   The application supplies the transport, so the same session runs over
   any socket.
3. Write the handshake path:
   - Send `client/init` with the `client_id`, the version `1`, and the suite
     `25519_ChaChaPoly_SHA256`.
   - Read `server/init`. Stop on `server/error`.
   - Read Noise message 1. Choose the PSK. Send Noise message 2 with the
     payload `{}`.
4. Write the hello path:
   - On `server/hello`, read the server name and the supported source codecs.
   - Send `client/hello` with the name, the device info, the supported roles,
     the source support object, the supported pair methods, and the unpaired
     access setting.
   - On `server/activate`, record the active roles and the activities.
5. Write the clock sync loop. Use the burst strategy from the clock filter.
6. Write the state reporting. Send `client/state` with `available` and the
   `source` object. Report `available: true` only after the filter converges.
7. Write the source role:
   - On `server/command` with `source.command` set to `start`, open the
     stream at once. The server waits only a few seconds for the answer.
   - Send `client-stream/start` with the format of the stream. The format
     must be one of the codecs the server listed.
   - A start that arrives before the clock settles is kept. The stream opens
     when the clock converges.
   - Send audio chunks. Keep each chunk between 5 ms and 150 ms.
   - On `server/command` with `source.command` set to `stop`, send
     `client-stream/end` if the stream is open.
   - When the source role leaves `active_roles`, stop sending and send
     `client-stream/end`.
8. Write the goodbye path. Send `client/goodbye` and close the WebSocket.
9. Handle the re-handshake. The prologue is the previous handshake hash.
10. Ignore unknown message types and unknown payload fields.

### 2. Pairing

1. Generate the identity key pair on first run. Store the private key in
   EncryptedSharedPreferences.
2. Generate the pairing PSK on first run. Store it in
   EncryptedSharedPreferences. The spec says it must not be a fixed default
   shared across devices.
3. Build the pairing token. The payload is the client public key, then the
   pairing PSK.
4. Show the token on a screen. Offer a copy button.
5. Handle the pairing flow. On a `server/activate` with
   `pairing.method` set to `pairing_psk`:
   - Check that the matched PSK is the pairing PSK. Abort with
     `method_not_supported` otherwise.
   - Generate a new long-term PSK.
   - Send `client/pair-init` and `client/pair-finalize` back to back.
   - On `server/pair-finalize`, store the pairing record for the server.
6. Handle `server/unpair` by removing the record.
7. Keep the pairing token stable. The pairing PSK never changes, so the token
   never changes.

### 3. Capture

1. Write a capture interface. It produces PCM blocks with a capture timestamp.
2. Write the system audio capture:
   - Request `RECORD_AUDIO` at runtime. Android asks for it for a record
     that reads what the system is playing.
   - Show the MediaProjection consent prompt.
   - Start the foreground service of type `mediaProjection` before calling
     `getMediaProjection()`.
   - Build the `AudioPlaybackCaptureConfiguration` with the matched usages
     `USAGE_MEDIA`, `USAGE_GAME`, and `USAGE_UNKNOWN`.
   - Build the `AudioRecord` with `setAudioPlaybackCaptureConfig`.
   - Register a `MediaProjection.Callback` and stop the capture on `onStop`.
   - Read into a direct buffer. A buffer in the heap makes every read fail
     with the code -2.
   - Give the block one short for every sample of every channel. A frame
     holds one sample for each channel, so a shorter block takes only the
     first half of the read and drops the rest without a word.
   - Stamp the block with the time of its first sample, not the time of the
     read. The time follows from the number of frames before the sample, so
     it does not wobble with the moment the read returns.
3. Write the resampler. The capture rate and the announced rate may differ.
   The server resamples, so the app must send one rate consistently. Pick the
   capture rate and announce it. The plan uses 48 kHz, 16-bit, stereo, which
   costs about 192 kB/s.
4. Write the backlog guard. When the network stalls, drop audio older than a
   small bound and resume from live capture. Use the same bound as the
   reference implementation: 500 ms.

### 4. The foreground service

1. Declare the service with the type `mediaProjection`.
2. Declare the permissions `FOREGROUND_SERVICE`,
   `FOREGROUND_SERVICE_MEDIA_PROJECTION`, and `POST_NOTIFICATIONS`.
3. Ask for `POST_NOTIFICATIONS` at runtime. Android 13 made it a runtime
   permission. The service must work without it, but the notification stays
   hidden.
4. Post a notification that shows the current state. The user must be able to
   stop the stream from the notification.
5. Keep the service alive while the session is active.

### 5. The user interface

The interface stays minimal. It shows only what the user needs to pair, to
capture, and to see the state.

1. Write the main screen. It shows the connect button and the status line.
   There is no picker, because the app has one capture source and one
   connection direction.
2. Write the pairing section. It shows the token and a copy button.
3. Write the status panel. It shows:
   - The server name and the connection state.
   - The pairing state and the paired server names.
   - The clock error in microseconds.
   - The stream state and whether the server asked for audio.
4. Save the settings. Store the cipher suite.

### 6. Discovery and the incoming connection

1. Register `_sendspin._tcp.local.` with NsdManager while the capture runs.
2. Listen for incoming WebSocket connections on the local port and run a
   source session for each. Apply the priority rules from the spec: one
   admitted connection at a time, ranked by activity.
3. Start the advertisement only after the socket listens. A server that
   reads the advertisement and dials at once would otherwise find a closed
   port.

### 7. Music Assistant specifics

1. Listen on the port 8928 and wait for the server to dial
   `ws://<the-phone>:8928/sendspin`. The server finds the phone over the
   advertisement, so both must be on the same network.
2. Write the pairing instructions into the app. The user pastes the token into
   the Music Assistant pairing screen.
3. Tell the user where to find the input. The phone appears under **Live
   Inputs** in Music Assistant, once the SendSpin Source plugin is active.
4. Follow the active roles, not the activities. The server approves a client
   by activating the source role. A source phone is never in a playback group,
   so the server declares no activities. An empty activity set is not a
   missing approval, and the status panel must not say that it is.

## Exit criteria

1. The app builds and installs on a physical device running Android 13 or
   later.
2. Music Assistant shows the phone under Live Inputs after the user pastes the
   pairing token.
3. The user can select the phone as an input in Music Assistant, and the audio
   plays on a speaker.
4. The clock error settles below 1 ms in the status panel.
5. The app survives a screen-off and a network drop. The server can dial in
   again without the user doing anything.
6. The app uses AES-GCM by default and falls back to ChaCha-Poly on a device
   without hardware AES.

## Risks

| Risk | Response |
| --- | --- |
| The system audio capture returns silence on the test device | Test this in the first week. Many Android 14 devices block it. The status panel shows the capture level, so a silent capture is easy to tell apart from a broken one. |
| DRM apps produce silence | Document the limit in the app. Show the capture level in the status panel. |
| The MediaProjection consent flow fails on Android 14 and higher | Start the foreground service before `getMediaProjection()`. Test on API 34. |
| The clock never converges | Check the burst strategy and the burst interval. The spec wants bursts no more than 10 seconds apart. |
| The app is killed in the background | Use a foreground service. Test with the battery saver on. |
| The hardware AES benchmark picks the wrong suite | The two suites both work. A wrong pick costs performance, not correctness. |
| The notification stays hidden | Android 13 made `POST_NOTIFICATIONS` a runtime permission. Ask for it. The service works either way. |
| The server does not dial in again after a drop | The phone keeps the port open and the advertisement up. If the server does not retry, the user has to start the playback again in Music Assistant. |
