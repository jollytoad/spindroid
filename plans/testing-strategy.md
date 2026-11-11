# Testing Strategy

## Layers

| Layer | What it covers | Where it runs |
| --- | --- | --- |
| Unit | Noise, clock filter, encoding, framing, state machines | JVM, no emulator |
| Integration | A full session against the reference server | JVM or CI |
| Conformance | The official cross-implementation harness | CI |
| Device | The Android capture, the permissions, the foreground service | Physical device |
| End to end | The user flow with Music Assistant | Manual, on a LAN |

## Unit tests

### Noise

1. Run the official `KKpsk2` test vectors from cacophony. The output must
   match byte for byte.
2. Run the vectors for both cipher suites.
3. Test the X25519 byte order. The vectors catch it, so run them before
   anything else.
4. Test the prologue binding. Change one byte of the prologue and confirm the
   handshake fails.
5. Test the PSK timing. Confirm the client can read message 1 without knowing
   the PSK.
6. Test the Sentinel fallback. A `psk_id` miss in the first handshake must
   complete with the Sentinel PSK. A miss in a re-handshake must fail.
7. Test the re-handshake. The prologue is the previous handshake hash.
8. Test the transport. A tampered ciphertext must fail to decrypt.
9. Test the nonce counter. A repeated or out-of-order ciphertext must fail.
10. Run the protocol tests on a JVM and on a device. The results must be the
    same.

### Clock filter

1. Port the expectations from the C++ reference tests.
2. Test that the filter converges with a simulated offset and drift.
3. Test the inverse mapping. A round trip through both directions must
   return the original time within a microsecond.
4. Test the burst strategy. The bursts must start no more than 10 seconds
   apart.

### Encoding

1. Test the pairing token against the reference vector in the spec.
2. Test a round trip for every payload length from 0 to 64 bytes.
3. Test the lenient decoder. It must accept whitespace, lower case, and a
   missing `SP:` prefix.
4. Test the decoder rejects an unknown version.

### Framing

1. Test that a chunk round trips through the framing layer.
2. Test the fragmentation. Split a large message and reassemble it.
3. Test the malformed sequences. Each one must close the connection:
   - A first fragment while a fragmented message is in flight.
   - A non-first fragment with none in flight.
   - A normal message while a fragmented message is in flight.
   - A reserved flag bit that is set.
   - An `orig_type` of `1`.
4. Test that a message with an unknown ID is ignored.

### State machines

1. Test that the client does not stream before the server sends `start`.
2. Test that a second `start` does not reopen the stream.
3. Test that `stop` clears the pending start authorization.
4. Test that the client does not report `available: true` before the filter
   converges.
5. Test that the client sends `client-stream/end` when the source role leaves
   `active_roles`.

## Integration tests

1. Run a session against the `aiosendspin` server.
2. Assert the handshake completes and the roles activate.
3. Assert the clock converges.
4. Assert the source appears in the server's client list.
5. Assert the audio arrives at a loopback sink and matches the captured
   samples.
6. Test the reconnection. Drop the network and confirm the client comes back
   with the same pairing record.

## Conformance harness

1. Use the `Sendspin/conformance` harness. It runs every implementation
   against every other one and publishes a results matrix.
2. Add an adapter for this project.
3. Run it against `aiosendspin` for every phase that changes the protocol.
4. Fix every failure before moving to the next phase.

## Device tests

Use a physical device. The emulator supports playback capture poorly.

| Android version | What to check |
| --- | --- |
| API 33 | The minimum version. Playback capture. |
| API 34 | The MediaProjection rules changed. The foreground service must start first. |
| API 35 | The foreground service rules changed again. |
| API 36 | The `ACCESS_LOCAL_NETWORK` permission and the local network restrictions. |

Checks on each version:

1. The MediaProjection consent prompt appears and works.
2. The foreground service starts with the right type and does not crash.
3. The notification appears and the stop button works.
4. The app keeps streaming with the screen off.
5. The app keeps streaming with the battery saver on.
6. The app connects with both cipher suites, whatever the hardware benchmark
   picks.

## Cipher suite tests

1. Run the full handshake with `25519_AESGCM_SHA256`.
2. Run the full handshake with `25519_ChaChaPoly_SHA256`.
3. Confirm the hardware benchmark gives the same answer on two runs of the
   same device.
4. Confirm the server accepts the suite the client picks. Every conformant
   server supports both, so this should always pass.

## End to end tests

1. Start Music Assistant.
2. Pair the phone with the pairing token.
3. Confirm the phone appears under Live Inputs.
4. Play the phone as an input and listen on a speaker.
5. Stop the input and confirm the stream stops.
6. Disconnect the network and confirm the app recovers.
7. In server mode, connect a SendSpin player to the phone and confirm the
   audio plays.

## Tests that need the capture path

These need a phone. A test that cannot run on the JVM belongs here.

1. The capture makes the chunks it should. The chunker holds twenty
   milliseconds of audio, so the count of the chunks must match the count
   of the samples. A capture that loses half of a read still sounds like
   music, and only the count gives it away.
2. The capture reads a direct buffer. A buffer in the heap makes every read
   fail with the code -2.
3. The capture hands over every sample of the read. A block of frames needs
   one short for every sample of every channel.
4. The time of a sample follows from the frame count. Two blocks of the
   same length carry the same time, whatever the clock says.

## Test data

1. A 1 kHz sine wave at 48 kHz, stereo, 16-bit. It makes a dropped sample
   obvious.
2. A 440 Hz tone with a 3 second gap. It tests the signal reporting.
3. A file that plays for 10 minutes. It tests the backlog guard and the
   long-run stability.
4. A clipping test file. It tests that the capture path does not distort.
