# Phase 3 - Polish

## Goal

Add the features that improve the daily experience of the app. None of them
is required for the core purpose.

## Deliverables

1. The dynamic pairing code flow with a 6-digit code and a QR code.
2. Signal reporting, so a server can start the source by itself.
3. The identity key in the Android Keystore.
4. Better reconnect behaviour, statistics, and a log view.
5. An F-Droid build.

## Tasks

### 1. The dynamic pairing code

This is the largest task in this phase. The code-based flows use a PAKE.

1. Read the PAKE section of the spec. The construction is
   CPACE-X25519-SHA512, from `draft-irtf-cfrg-cpace-21`. The server takes the
   role `A`, the client takes the role `B`.
2. Implement CPACE:
   - The pairing code is the `PRS` input, as ASCII digits.
   - The session identifier `sid` is the bytes `"sendspin-pair-pake-v1"`, then
     the Noise handshake hash `h`, then `pairing_index` as a big-endian uint32,
     then `round` as a big-endian uint32.
   - `CI` is empty. `ADa` is `"server"`. `ADb` is `"client"`.
   - Produce the four fields: `pake_msg_1`, `pake_msg_2`, `server_kc`, and
     `client_kc`. The tags are HMAC-SHA-512, so 64 bytes each.
3. Implement the dynamic pairing code flow:
   - The client generates `nonce_B` and sends
     `commit_B = SHA-256("sendspin-pair-commit-v1" || nonce_B)`.
   - The server sends `nonce_A` in its first `server/pair-init`.
   - The code is
     `SHA-256("sendspin-pairing-code-derive-v1" || h || nonce_A || nonce_B)`.
   - For the digits format, take the digest as a big-endian unsigned integer
     modulo 10 to the power 6, then pad it to 6 digits.
   - For the QR code format, take the first 24 bytes of the digest.
4. Implement the wrapping:
   - The key is `SHA-256(label || sid || ISK)`.
   - The label is `"sendspin-pair-psk-wrap-v1"` for the PSK and
     `"sendspin-pair-nonce-wrap-v1"` for the nonce.
   - Encrypt the 32-byte value with the connection AEAD, the derived key, a
     12-byte all-zero nonce, and empty associated data.
5. Implement the message flow:
   - `client/pair-pending`, `client/pair-init`, `server/pair-init`,
     `client/pair-auth`, `server/pair-auth`, `server/pair-confirm`,
     `client/pair-confirm`, `client/pair-retry`, `client/pair-finalize`,
     `server/pair-finalize`, `pair/abort`.
6. Implement the round limit. The spec recommends 20 rounds before the app
   holds back further attempts. Give the user a button to allow pairing again.
7. Show the 6-digit code on the pairing screen. Offer the QR code format as
   well.

### 2. Signal reporting

1. Advertise the `line_sense` feature in the `source@v1_support` object.
2. Watch the captured audio. Report `present` when sound arrived in the last
   2 seconds. Report `absent` otherwise.
3. Send `client/state` when the signal state changes.
4. Music Assistant uses this signal to start and stop the source by itself.

### 3. Key storage hardening

The plan stores the identity key and the PSKs in EncryptedSharedPreferences.
Android 13 supports X25519 in the Android Keystore, so the identity key can
move there.

1. Generate the identity key with `KeyPairGenerator` for `X25519` from the
   `AndroidKeyStore` provider, with the purpose `AGREE_KEY`.
2. Use the Keystore private key in the Noise handshake. The key never leaves
   the Keystore.
3. Keep the long-term PSKs in EncryptedSharedPreferences. They are symmetric
   keys, and the Keystore does not offer a better place for them.
4. Keep the EncryptedSharedPreferences path as a fallback, for devices where
   the Keystore has no X25519 support.
5. Test that the `client_id` stays the same after the move. A changed
   `client_id` loses the pairing records.

### 4. Operations

1. Add a reconnect backoff with a cap. Log each attempt.
2. Add a statistics panel:
   - The number of chunks sent and dropped.
   - The bytes sent and the bitrate.
   - The clock error and the number of resyncs.
3. Add a log view. Show the last few hundred protocol events.
4. Add a diagnostic export. Write the log to a file the user can share.
5. Handle the device rotation and the configuration change without dropping
   the stream.
6. Handle the phone waking from sleep. The MediaProjection token may be
   gone. Ask for consent again.

### 5. The F-Droid build

1. Review the licence of every dependency. Each one must be free software.
2. Write the app description for the listing. It must explain that the app
   captures audio and sends it to a server on the local network.
3. Make the build reproducible. Pin every version, including the build tools.
4. Remove anything that is not needed. Every extra dependency is extra
   review work.

## Exit criteria

1. A user can pair with a 6-digit code and with a QR code.
2. Music Assistant starts the source by itself when sound arrives.
3. The identity key lives in the Android Keystore.
4. The statistics panel shows a steady bitrate and a small clock error.
5. The F-Droid build installs and works the same as the sideloaded build.

## Risks

| Risk | Response |
| --- | --- |
| The CPACE implementation is wrong and the pairing fails in a subtle way | Test against the CPACE test vectors from the draft. Test against `aiosendspin`, which is the reference implementation. |
| The Keystore has no X25519 support on some device | Keep the EncryptedSharedPreferences path as a fallback. Test on several devices. |
| The scope of this phase grows | Keep the items independent. Ship them one at a time. |
