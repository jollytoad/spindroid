# Architecture

## Module layout

```
sendspin-sender/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
├── sendspin-protocol/          Pure Kotlin/JVM. No Android code.
│   ├── crypto/                 X25519, ChaCha20-Poly1305, AES-GCM, HKDF, HMAC, base32, base64url
│   ├── noise/                  KKpsk2 handshake, transport, re-handshake
│   ├── protocol/               Message types, binary framing, session state machines
│   ├── time/                   Kalman clock filter
│   └── codec/                  PCM framing
├── app/                        Android application
│   ├── capture/                MediaProjection capture
│   ├── service/                Foreground service
│   ├── discovery/              mDNS registration with NsdManager
│   ├── storage/                EncryptedSharedPreferences keys and settings
│   └── ui/                     Jetpack Compose screens
└── test-server/                Optional reference server for CI
```

The protocol module holds the hard parts. It has no Android code, so the
tests run on the JVM without an emulator.

The app targets Android 13, API 33, as its minimum version. The protocol
module runs on the JVM, so it must use the subset of the Java cryptography
API that both the JVM and Android 13 support.

## Why the protocol module is separate

The Noise handshake, the clock filter, and the session state machines carry
most of the risk. They also need precise timing and byte-level control.
Keeping them in a plain Kotlin module lets the tests run fast and lets the
same code serve both the source role and the server role.

## Session state machines

### Source session

This is a client session. It connects to a server and offers the `source@v1`
role.

```
IDLE
  -> CONNECTING          open the WebSocket
  -> HANDSHAKING         client/init, server/init, Noise messages 1 and 2
  -> HELLO               server/hello, client/hello, server/activate
  -> SYNCING             client/time and server/time until the filter converges
  -> READY               report available: true, wait for the server to send start
  -> STREAMING           client-stream/start, then audio chunks
  -> STOPPED             client-stream/end
  -> CLOSED              client/goodbye, close the WebSocket
```

### Server session

This is a server session. A player connects to the phone and the phone offers
the `player@v1` role.

```
IDLE
  -> HANDSHAKING         read client/init, send server/init, Noise messages 1 and 2
  -> HELLO               server/hello, read client/hello, server/activate
  -> SYNCING             answer client/time with server/time
  -> STREAMING           stream/start, then audio chunks
  -> CLOSED              client/goodbye, close the WebSocket
```

Both sessions share the Noise layer, the framing layer, the message types, and
the clock filter.

## Protocol facts that shape the code

### Transport

The WebSocket is plain `ws://`. After the handshake, every message is a binary
Noise transport message. The first decrypted byte is a message ID.

| IDs | Assignment |
| --- | --- |
| 0 | JSON message body |
| 1 | Fragmentation |
| 4 to 7 | Player role |
| 8 to 11 | Artwork role |
| 12 to 15 | Source role |
| 16 to 23 | Visualizer role |

A receiver must ignore a message ID it does not implement.

### Handshake

The pattern is `KKpsk2`. The cipher suite is `25519_ChaChaPoly_SHA256` or
`25519_AESGCM_SHA256`. The client picks the suite in `client/init`, so no
negotiation is needed. Every conformant server supports both. The app uses
AES-GCM by default, and the user can choose ChaCha-Poly.

The server is always the Noise initiator. The client is always the responder.
This holds even when the client opened the WebSocket.

The message order is fixed.

1. The client sends `client/init` as a cleartext text frame.
2. The server sends `server/init`, then Noise message 1.
3. The client sends Noise message 2.
4. Both sides switch to transport mode.
5. The server sends `server/hello`.
6. The client sends `client/hello`.
7. The server sends `server/activate`.

### Prologue

The Noise prologue is the exact bytes of `client/init` followed by the exact
bytes of `server/init`, as they were transmitted. Both sides hash the raw
bytes. A re-encoding of the parsed JSON breaks the handshake.

### PSK selection

Noise message 1 carries `psk_id` and `psk_category`.

| Category | Meaning |
| --- | --- |
| `lt` | Long-term PSK from a stored pairing record |
| `pr` | Pairing PSK |
| `sn` | Sentinel PSK |

The identifier formula is `base64url(SHA-256("sendspin-psk-id-v1" || PSK))`.
The result is 43 characters.

The Sentinel PSK is `SHA-256("sendspin-sentinel-psk-v1")`.

The PSK is mixed in at the `psk2` token, which falls after `ee` in Noise
message 2. The client can therefore read message 1 without the PSK, learn
`psk_id`, and then choose the PSK. A stock Noise library that takes the PSK
at construction cannot express this flow.

On a `psk_id` miss in the first handshake, the client falls back to the
Sentinel PSK and the session is unpaired. On a miss during a re-handshake, the
handshake fails.

### Source lifecycle

The default after the handshake is stopped. The server sends `server/command`
with `source.command` set to `start`. The client answers with
`client-stream/start`, then sends audio chunks. The client sends
`client-stream/end` when the stream stops.

A `client-stream/start` without a matching `start` from the server is a
protocol error.

### Source chunk

| Bytes | Content |
| --- | --- |
| 0 | Message type `12` |
| 1 to 8 | Big-endian int64 timestamp, in server time, in microseconds |
| 9 and up | Encoded audio frame |

The timestamp is the moment the first sample in the chunk was captured. A chunk
must be at most 150 ms. A chunk should be at least 5 ms, except the last chunk
before `client-stream/end`.

### Player chunk

| Bytes | Content |
| --- | --- |
| 0 | Message type `4` |
| 1 to 8 | Big-endian int64 timestamp, in server time, in microseconds |
| 9 to 12 | Big-endian uint32 `send_ahead`, in microseconds |
| 13 and up | Encoded audio frame |

The timestamp is the moment the first sample should leave the speaker. The
`send_ahead` field reports the lead the server had when it sent the chunk. The
field saturates at 0 and at 4294967295. A player must ignore both values when
it measures delay.

A server must not send a chunk longer than 150 ms. A server should not send a
chunk shorter than 15 ms, except the last one.

### Fragmentation

One Noise transport message holds at most 65518 bytes of application data.
Anything larger must be split.

- The first fragment is `[1][flags][orig_type][data]`.
- Later fragments are `[1][flags][data]`.
- Bit 1 of `flags` marks the first fragment. Bit 0 marks the last.
- Only one fragmented message may be in flight per direction.

### Codecs

The client may only announce a codec that the server listed in `server/hello`
under `source@v1_support.supported_codecs`. Every server accepts `flac` and
`pcm`. The client must be able to produce at least one of them.

The app sends PCM. PCM is little-endian signed integers. A 24-bit sample takes
3 bytes.

The app announces 48 kHz, 16-bit, stereo. The server resamples and transcodes,
so it accepts whatever the source announces.

The cost is bandwidth. PCM at 48 kHz, 16-bit, stereo uses about 192 kB/s.
FLAC would cut this, but it is not planned yet.

### Clock

The client sends `client/time` with `client_transmitted`. The server answers
with `server/time`, which carries `client_transmitted`, `server_received`, and
`server_transmitted`.

The client feeds the four timestamps into the clock filter. The filter tracks
the offset and the drift between the two clocks.

The reference filter has a burst strategy. The client sends bursts of eight
exchanges, keeps the one with the smallest error, and then sends one exchange
every 9 seconds.

A source must not report `available: true` until the filter has converged.

### Source timestamps

A source inverts the filter mapping. For a capture at local time `t_capture`,
it sends the `t_server` that maps to it. The inverse applies both the offset
and the drift.

### Pairing token

The token is the ASCII string `SP:`, then a version character, then a body.

| Version | Payload |
| --- | --- |
| `0` | The client public key (32 bytes), then the pairing PSK (32 bytes) |
| `1` | A per-session dynamic pairing code, in the QR code format |

The encoder base32-encodes the payload per RFC 4648, strips the `=` padding,
and maps every `2` to `9`. The decoder reverses this and stays lenient with
operator input.

Reference vector for `client_key = 0x00 0x01 ... 0x1f` and
`pairing_psk = 0xe0 0xe1 ... 0xff`:

```
SP:0AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQTCQKRMFYYDENBWHA5DYP6BYPC4PSOLZXH5DU6V97M5XXO74HR6LZ7J5PW674PT6X37T6757Y
```

### Pairing PSK flow

The server sends `server/activate` with `pairing.method` set to `pairing_psk`.
The client then sends `client/pair-init` and `client/pair-finalize` back to
back, with no wait for a reply in between. The `client/pair-finalize` carries
a new long-term PSK.

The server stores the record, confirms with `server/pair-finalize`, and starts
a re-handshake to the new long-term PSK.

### Re-handshake

A re-handshake is a new Noise handshake inside the transport. Its prologue is
the previous handshake hash. The server sends `server/activate` as its first
message under the new keys. The server must not start new application messages
after it sends Noise message 1.

### Privacy rule

A client with a privacy-sensitive input such as a microphone should ship with
unpaired access disabled. The captured audio would otherwise reach anyone on
the network. The app advertises `unpaired_access.enabled` as `false`.

### Discovery

| Side | Service type | Recommended port | TXT keys |
| --- | --- | --- | --- |
| Client | `_sendspin._tcp.local.` | 8928 | `path` = `/sendspin`, `name` |
| Server | `_sendspin-server._tcp.local.` | 8927 | `path` = `/sendspin`, `name` |

A client must not dial out to servers while it advertises `_sendspin._tcp`.

## Cryptography on Android

Android 13 provides every primitive the Noise handshake needs. No crypto
library is needed.

| Primitive | Java API | Available from |
| --- | --- | --- |
| X25519 key agreement | `KeyAgreement` with the `XDH` algorithm and `NamedParameterSpec.X25519` | API 33 |
| ChaCha20-Poly1305 | `Cipher` with `ChaCha20/Poly1305/NoPadding` | API 28 |
| AES-256-GCM | `Cipher` with `AES/GCM/NoPadding` | API 26 |
| SHA-256 | `MessageDigest` | API 1 |
| HMAC-SHA-256 | `Mac` with `HmacSHA256` | API 1 |

The protocol module runs on the JVM as well as on Android. The JVM has the
same APIs, so one code path covers both.

### The X25519 byte order

This is the one place where the Java API and the Noise format disagree. Get it
wrong and the handshake fails with no clear error.

- Noise uses the raw 32-byte public key, in the little-endian order of the
  X25519 u-coordinate.
- `XECPublicKey.getEncoded()` returns an X.509 wrapper. Do not use it.
- `XECPublicKey.getU()` returns the u-coordinate as a `BigInteger`, which is
  big-endian. Convert it back to 32 bytes in little-endian order.
- `XECPrivateKeySpec` takes the private scalar in big-endian order. Noise uses
  the little-endian order. Convert it.

The Noise test vectors catch this. Run them before anything else.

### Cipher suite choice

The client picks the suite in `client/init`, so no negotiation is needed.

| Suite | Cipher | Default |
| --- | --- | --- |
| `25519_AESGCM_SHA256` | AES-256-GCM | Yes |
| `25519_ChaChaPoly_SHA256` | ChaCha20-Poly1305 | Only when the user asks for it |

Detect hardware AES support with a short benchmark at startup. Measure the
time for a fixed number of AES-GCM operations, and compare it against a fixed
threshold. Cache the result, so the benchmark runs once.

## Android capture constraints

### System and app audio

| Requirement | Detail |
| --- | --- |
| Minimum version | Android 13, API 33. The capture API itself needs Android 10, API 29. |
| Permissions | `RECORD_AUDIO`, granted at runtime |
| Consent | A MediaProjection prompt, one time per session |
| Foreground service | Type `mediaProjection`. On Android 14 and higher it must start before `getMediaProjection()`. |
| Matched usages | `USAGE_MEDIA`, `USAGE_GAME`, `USAGE_UNKNOWN` |

Two limits apply.

1. DRM apps opt out of capture. Spotify and Netflix produce silence.
2. Many Android 14 devices block third-party playback capture and return
   silence.

### Local network access

Android 16, API 36, adds the `ACCESS_LOCAL_NETWORK` permission. The app needs
it to accept incoming connections.

### The foreground service notification

Android 13, API 33, made `POST_NOTIFICATIONS` a runtime permission. The
foreground service still runs without it, but the notification stays hidden.
Ask for the permission when the user starts the service. The service must work
either way.

## Capture pipeline

Both capture modes produce the same shape of data.

```
AudioRecord
  -> read into a ShortArray
  -> convert to a byte array of little-endian PCM
  -> hand to the protocol session with the capture timestamp
  -> the session encodes, frames, and sends
```

The capture loop and the network loop run separately. A slow network must not
stall the capture loop. When the network falls behind, the app drops audio
older than a small bound and resumes from live capture. This matches the
reference behaviour in `aiosendspin`.
