# Phase 2 - Server Mode and Discovery

## Goal

Add the second architecture. The phone runs a SendSpin server. SendSpin players
connect to the phone and receive the captured audio directly. This mode works
with no Music Assistant installed.

Also add the mDNS discovery for both roles.

## Deliverables

1. A server session that offers the `player@v1` role.
2. An inbound WebSocket host on the phone.
3. mDNS registration for `_sendspin-server._tcp.local.`.
4. Server-side pairing with the pairing PSK method.

## Note on scope

This phase is roughly twice the work of Phase 1. The server side needs the
Noise handshake as the initiator, the player role, the group, the stream
setup, and the clock mapping in the reverse direction.

Skip this phase if the goal is met by Phase 1.

## Tasks

### 1. The server session

1. Write the session class. It owns the WebSocket, the Noise channel, the
   clock filter, and the player state.
2. Write the handshake path:
   - Read `client/init`. Stop with `server/error` if the version or the suite
     is wrong.
   - Send `server/init` with the `server_id` and the version `1`.
   - Read Noise message 1. Choose the PSK. Send Noise message 2.
   - The prologue is the raw bytes of `client/init` followed by the raw bytes
     of `server/init`.
3. Write the hello path:
   - Send `server/hello` with the server name.
   - Read `client/hello`. Read the player support object and the supported
     pair methods.
   - Send `server/activate` with the activities `playback` and the active role
     `player@v1`.
4. Write the clock sync. Answer `client/time` with `server/time`. Run the same
   filter, used in the other direction.
5. Write the player state handling. Read the `player` object from
   `client/state`:
   - `output_delay_ms`
   - `required_lead_time_ms`
   - `min_buffer_ms`
   - `supported_formats` and the preferred `format`
   - `supported_commands`
6. Write the stream setup. Send `stream/start` with the player object. The
   format must be one the player listed in `supported_formats`. Prefer the
   player's own preference. Otherwise take the first entry the server can
   produce.
7. Write the audio delivery:
   - Send chunks of type `4`.
   - Keep each chunk between 15 ms and 150 ms.
   - Set `send_ahead` to the lead the server had when it sent the chunk.
   - Respect the player's `buffer_capacity`. Do not send a chunk that would
     push the count over the cap.
   - Keep the queued audio at or above the player's `min_buffer_ms`.
8. Write the group handling. Send `group/update` with the group id, the group
   name, and the playback state.
9. Write the goodbye path. Handle `client/goodbye` and close the WebSocket.

### 2. The inbound WebSocket host

1. Host a WebSocket server on the local port. Use Java-WebSocket.
2. Run one server session per connection.
3. Write the priority rules. The spec allows one admitted connection at a
   time for a client. A server may hold several players. Group them by the
   `switch` command.
4. Send `group/update` when a player joins or leaves.

### 3. Discovery

1. Register `_sendspin-server._tcp.local.` with NsdManager in server mode.
   Use the recommended port 8927 and the TXT keys `path` = `/sendspin` and
   `name`.
2. Offer a port setting, because 8927 may already be taken.
3. Stop the registration when the app leaves server mode.
4. Keep the source registration separate. The app must not advertise
   `_sendspin._tcp` while it dials out to a server, and it must not advertise
   `_sendspin-server._tcp` while it accepts incoming player connections in
   source mode.

### 4. Server-side pairing

1. Give the operator a place to paste the player's pairing token.
2. Extract the client public key and the pairing PSK from the token.
3. On the first handshake with that player, use the pairing PSK.
4. On a `server/activate` with `pairing.method` set to `pairing_psk`, wait for
   `client/pair-init` and `client/pair-finalize`.
5. Store the long-term PSK from `client/pair-finalize` against the player's
   `client_id`.
6. Send `server/pair-finalize` and start a re-handshake to the new long-term
   PSK.
7. Require an operator approval before activating the player role on an
   unpaired connection. The spec requires this for the server side.

### 5. The audio codec

The app sends PCM in server mode too. The player picks the format it wants in
its `player@v1_support.supported_formats`, and the phone must produce one of
them.

1. Announce PCM in `stream/start`. The plan uses 48 kHz, 16-bit, stereo.
2. Resample if the player asks for a different rate. The capture runs at
   48 kHz, so a player at 44.1 kHz needs a resampler here.
3. Mix down to mono if the player asks for one channel.
4. Send no codec header. PCM needs none.
5. Leave FLAC out. Add it later if a player refuses PCM, or if the bandwidth
   turns out to matter. The source role already supports a codec header, so
   adding FLAC later means writing an encoder and filling the header. No other
   part of the code changes.

### 6. The user interface

The interface stays minimal.

1. Add a mode switch. The app runs either source mode or server mode. It never
   runs both.
2. Write the server screen. It shows the connected players and their state.
3. Add the local port setting.
4. Add the player pairing section. It shows the accepted players and a button
   to forget one.

## Exit criteria

1. A SendSpin player connects to the phone over the local network.
2. The player plays the audio the phone captures.
3. The phone shows the player in the server screen.
4. The phone registers `_sendspin-server._tcp.local.` and a player finds it by
   discovery.
5. The phone registers `_sendspin._tcp.local.` in source mode, and Music
   Assistant finds it and pairs with it.
6. The app never runs in both modes at the same time.

## Risks

| Risk | Response |
| --- | --- |
| The server side of the protocol is larger than expected | Keep Phase 1 shippable on its own. Do not start Phase 2 until Phase 1 is stable. |
| The player and the phone disagree about the clock | Use the same filter on both sides. Check the `send_ahead` measurement first. |
| The phone runs out of memory when several players connect | Cap the per-player buffer. The `buffer_capacity` from the player limits this. |
| Android blocks the inbound WebSocket port | Request the `ACCESS_LOCAL_NETWORK` permission on Android 16. |
| A player asks for a format the phone cannot produce | The plan covers PCM at any rate and one or two channels. Add a resampler for other rates. |
| The resampler introduces audible artefacts | Measure the output against the input. A poor resampler is more obvious on music than a small clock error. |
