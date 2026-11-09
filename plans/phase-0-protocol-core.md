# Phase 0 - Project Skeleton and Protocol Core

## Goal

Build the project layout and the protocol core. Every part of the core gets a
unit test. No UI and no Android code at the end of this phase.

The core is the risk in this project. The Noise handshake, the clock filter,
and the message framing carry most of the difficulty. This phase removes that
risk before any Android work starts.

## Status

The phase is complete. Every task below is done, and the exit criteria all
hold. The test suite has 123 tests, and it runs on the JVM without an
emulator.

| Deliverable | Where |
| --- | --- |
| The project layout | `settings.gradle.kts`, `build.gradle.kts`, `app`, `sendspin-protocol` |
| The `KKpsk2` handshake, both roles, both suites | `sendspin-protocol/src/main/kotlin/com/sendspin/protocol/noise/` |
| The clock filter | `sendspin-protocol/src/main/kotlin/com/sendspin/protocol/time/` |
| The message types and the binary framing | `sendspin-protocol/src/main/kotlin/com/sendspin/protocol/protocol/` |
| The pairing token and the PSK store | `.../pairing/`, `.../psk/` |

### Notes on what the work found

Five things were harder than they looked, and each one is now covered by a
test.

1. **The cipher has to be initialised in the right mode.** The first version
   of `CipherState` always used the encrypt mode, so a decryption encrypted
   the ciphertext instead of opening it. The Noise vectors caught it.

2. **The `se` token of the second handshake message is `DH(s, re)`.** The
   initiator mixes its own static key with the responder's ephemeral key. The
   official vectors caught the wrong order.

3. **The X25519 byte order is the raw little-endian form.** The Java API
   uses a big-endian `BigInteger` for the u-coordinate and returns an X.509
   wrapper from `getEncoded()`. The code keeps the raw bytes and uses the
   DER-wrapped key specs, which Android's Conscrypt accepts as well. See
   [toolchain.md](toolchain.md).

4. **The clock filter rounds halves away from zero.** The C++ reference
   rounds that way, and the result has to match it to the microsecond.

5. **A missing field and a field that is null are different.** The
   specification says that omitting a role object leaves that role's state
   unchanged, while setting it to `null` clears it. A plain nullable field
   cannot carry that difference, so the state messages use `JsonOptional`,
   which holds three states. See
   `protocol/JsonOptional.kt`.

### The vectors that pin the implementation

| What | Where it comes from |
| --- | --- |
| The `KKpsk2` handshake and the transport messages | The `cacophony` corpus, for both cipher suites |
| X25519 | RFC 7748, sections 5.2 and 6.1 |
| The pairing token | The reference vectors in the pairing section of the specification |
| The clock filter | The C++ reference in the `Sendspin/time-filter` repository |
| The PSK identifier and the sentinel key | The constants in the specification |

### Other SendSpin implementations

The `Sendspin/sendspin-jvm` repository is a player-role client library. It
has no Noise layer, no pairing and no source role, so the crypto and the
framing here are its own work. Its clock filter is worth reading, because it
seeds the filter differently, on purpose: the reference algorithm assumes the
first innovation is small, which holds when the measurements come from the
`client/time` exchange but not when they come from a monotonic clock.

## Deliverables

1. A Gradle project with a `sendspin-protocol` module and an `app` module.
2. A tested Noise `KKpsk2` implementation for both roles.
3. A tested port of the clock filter.
4. Tested message types and binary framing.
5. A test suite that runs on the JVM without an emulator.

## Tasks

### 1. Project skeleton

1. Set up the toolchain. Run `pkgx dev` to install the tools from
   `pkgx.yaml`. Install the Android SDK with `sdkmanager`. See
   [toolchain.md](toolchain.md).
2. Create the root `settings.gradle.kts` and `build.gradle.kts`.
3. Create `gradle/libs.versions.toml` with a pinned version for each library.
4. Create the `sendspin-protocol` module as a pure Kotlin/JVM library.
5. Create the `app` module as an Android application.
6. Set `minSdk` to 33 and `compileSdk` and `targetSdk` to the current stable
   version.
7. Add the Gradle wrapper and check that `./gradlew build` succeeds on an empty
   project.
8. Add no crypto library. Android 13 provides X25519, ChaCha20-Poly1305, and
   AES-GCM through the platform JCA.

### 2. Encoding helpers

1. Write `base64url` encode and decode, with no padding.
2. Write `base32` encode and decode per RFC 4648, with the alphabet `A-Z` and
   `2-7`.
3. Write the pairing token codec:
   - Encode. Base32-encode the payload, strip the `=` padding, then map every
     `2` to `9`.
   - Decode. Trim the whitespace, upper-case the text, strip a leading `SP:`,
     map every `9` back to `2`, re-pad to a multiple of 8, then base32-decode.
   - Decode leniently. Reject an unknown version. Ignore payload bytes beyond
     the ones the version defines.
4. Test the encoder and decoder against the reference vector in the spec.
5. Test a round trip for every payload length from 0 to 64 bytes.

### 3. Noise primitives

1. Write HMAC-SHA-256 on top of `javax.crypto.Mac`.
2. Write HKDF as Noise defines it. HKDF produces two outputs.
3. Write the Noise symmetric state:
   - `initialize`: set the chaining key and the hash to the protocol name hash.
   - `mixKey`: apply HKDF with the key as the input key material.
   - `mixHash`: hash the input into the hash.
   - `mixKeyAndHash`: mix the key, then mix the hash.
   - `encryptAndHash` and `decryptAndHash`.
   - `split`: produce the two transport keys.
4. Write the AEAD wrapper. It takes a cipher name and produces the Noise
   nonce of four zero bytes and an eight-byte counter.
   - For ChaCha-Poly, the counter is little-endian.
   - For AES-GCM, the counter is big-endian.
5. Write the X25519 wrapper on top of `KeyAgreement` with the `XDH` algorithm.
   - Convert the public key to and from the 32-byte little-endian form that
     Noise uses. `XECPublicKey.getEncoded()` returns an X.509 wrapper, so do
     not use it.
   - Convert the private scalar to and from big-endian, because
     `XECPrivateKeySpec` uses that order.
6. Test the primitives against the Noise specification test vectors.
7. Keep the protocol module free of Android APIs. It must compile and run on a
   plain JVM. This keeps the tests fast and keeps the module reusable.

### 4. The `KKpsk2` handshake

The pattern is the one the Noise specification gives:

```
KKpsk2:
  -> s
  <- s
  ...
  -> e, es, ss
  <- e, ee, se, psk
```

1. Write the initiator side.
   - Message 1 carries the tokens `e`, `es` and `ss`.
   - Message 2 carries the tokens `e`, `ee`, `se` and `psk`.
2. Write the responder side.
   - Read message 1 and decrypt the payload. The key at this point does not
     depend on the PSK.
   - Read `psk_id` and `psk_category` from the payload.
   - Choose the PSK. Write message 2 with the `psk` token.
3. Support a custom prologue. The prologue is the raw bytes of `client/init`
   followed by the raw bytes of `server/init`.
4. Support a re-handshake. The prologue is the previous handshake hash.
5. Track the handshake hash, because the spec needs it for the CPace session
   identifier later.
6. Test against the official `KKpsk2` test vectors. The output must match byte
   for byte.

The `psk` token sits at the end of the second message, so the responder learns
which key to use from the payload of the first message. The `se` token of the
second message mixes the initiator's static key with the responder's ephemeral
key, which is `DH(s, re)`.

### 5. The clock filter

1. Port `Sendspin/time-filter` from C++ to Kotlin.
2. Port the burst strategy. The client sends bursts of eight exchanges, keeps
   the exchange with the smallest error, and then sends one exchange every
   9 seconds.
3. Implement both directions:
   - `computeClientTime` maps a server timestamp to the local clock.
   - The inverse maps a local capture time to the server clock. The source role
     needs this.
4. Port the expectations from the C++ tests.
5. Test that the filter converges and that the error settles below 1 ms.

### 6. Messages and framing

1. Define the message envelope. Every JSON message has a `type` and a
   `payload`.
2. Define the message types the app needs:
   - `client/init`, `server/init`, `noise/handshake`, `server/error`
   - `server/hello`, `client/hello`, `server/activate`
   - `client/time`, `server/time`, `client/state`, `server/state`
   - `server/command`, `client/command`, `client/goodbye`, `client/leave`
   - `client-stream/start`, `client-stream/end`
   - `group/update`, `server/unpair`
   - `client/pair-init`, `client/pair-finalize`, `server/pair-finalize`,
     `pair/abort`, `client/pair-pending`
3. Write the binary framing. The first byte is the message ID.
4. Write the fragmentation layer:
   - The first fragment is `[1][flags][orig_type][data]`.
   - Later fragments are `[1][flags][data]`.
   - Bit 1 of `flags` marks the first fragment. Bit 0 marks the last.
   - Allow only one fragmented message in flight per direction.
   - Close the connection on a malformed sequence.
5. Write the source chunk writer. Byte 0 is `12`, bytes 1 to 8 are a
   big-endian int64 timestamp.
6. Write the player chunk writer. Byte 0 is `4`, bytes 1 to 8 are the
   timestamp, bytes 9 to 12 are `send_ahead` as a big-endian uint32.
7. Test that a chunk round trips through the framing layer.

### 7. The cipher suite

1. Support both suites. The client picks one in `client/init`, so there is no
   negotiation.
2. Write a hardware AES benchmark. Measure the time for a fixed number of
   AES-GCM operations and compare it against a fixed threshold.
3. Use AES-GCM when the benchmark says the hardware is fast enough. Use
   ChaCha-Poly otherwise.
4. Cache the benchmark result, so it runs once per process.
5. Test that both suites complete a handshake against the test vectors.

### 8. The PSK store

1. Define an interface for the PSK store, so the protocol module stays free of
   Android code.
2. Define the records. A record holds the `server_id`, the long-term PSK, and
   the server name.
3. Implement the `psk_id` formula and the lookup by `psk_id`.
4. Implement the Sentinel fallback rule. A miss in the first handshake falls
   back. A miss in a re-handshake fails.
5. Implement the record capacity. The store holds at least 5 records. A new
   pairing evicts the least recently used record. Never evict a record that
   backs an open connection.

## Exit criteria

All six criteria hold.

1. `./gradlew :sendspin-protocol:test` passes. It runs 123 tests.
2. The Noise tests match the official vectors byte for byte.
3. Both cipher suites pass the vectors.
4. The clock filter tests pass.
5. The token tests match the reference vector.
6. The protocol module has no Android dependency. It builds as a plain
   Kotlin/JVM library, with only Moshi on its compile classpath.

## Risks

| Risk | Response |
| --- | --- |
| The Noise implementation does not match the vectors | Test early, in the first two days. The vectors are public. |
| The X25519 byte order is wrong | The vectors catch it. Write the conversion test first. |
| The clock filter port drifts from the C++ original | Port the C++ tests with it, not afterwards. |
| The JVM and Android differ in a crypto detail | Run the protocol tests on both. The JVM must give the same results as the device. |
