"""A SendSpin server, for testing the client against an independent implementation.

The server speaks the same protocol as the Kotlin client, but it is written
from the specification rather than from the Kotlin code. That way a mistake in
one implementation does not hide a mistake in the other.

The Noise handshake follows the `KKpsk2` pattern that the specification
defines. The details that are easy to get wrong are:

- the prologue is the raw bytes of the `client/init` and `server/init`
  messages, so the server uses the text it received rather than a
  re-encoding of it,
- the static keys are hashed in the order the server's own key first,
- the PSK is mixed in with `mix_key_and_hash`, which produces three
  outputs,
- the cipher nonce is four zero bytes followed by the counter in
  little-endian order.

Run it with:

    python3 tools/test_server.py --port 8927 --pairing-psk <base64url>
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import hmac
import json
import logging
import os
import struct
import sys
import time
from typing import Any

try:
    from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
    from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
except ImportError:  # pragma: no cover
    sys.exit("The server needs the cryptography package.")

LOG = logging.getLogger("sendspin")

PROTOCOL_VERSION = 1
PROTOCOL_NAME = "Noise_KKpsk2_25519_ChaChaPoly_SHA256"

SENTINEL_PSK = hashlib.sha256(b"sendspin-sentinel-psk-v1").digest()
PSK_ID_LABEL = b"sendspin-psk-id-v1"

# The binary message identifiers.
MSG_JSON = 0
MSG_FRAGMENT = 1
MSG_SOURCE_AUDIO = 12

# The size of an X25519 key, in bytes.
KEY_SIZE = 32


def b64url(data: bytes) -> str:
    """Encodes bytes the way the protocol does: base64url without padding."""
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def unb64url(text: str) -> bytes:
    """Decodes base64url text without padding."""
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def psk_id(psk: bytes) -> str:
    """The identifier of a key, as the server names it."""
    return b64url(hashlib.sha256(PSK_ID_LABEL + psk).digest())


def raw_public(key: X25519PrivateKey) -> bytes:
    """The public key of a private key, in the raw form that Noise uses."""
    return key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)


def dh(private: bytes, public: bytes) -> bytes:
    """The X25519 shared secret.

    The public key is the raw u-coordinate, so it is read as a public key
    and never as a private scalar.
    """
    return X25519PrivateKey.from_private_bytes(private).exchange(
        X25519PublicKey.from_public_bytes(public)
    )


def hkdf(chaining_key: bytes, input_key_material: bytes, outputs: int) -> list[bytes]:
    """The HKDF that the Noise specification defines, not the one in RFC 5869."""
    temp_key = hmac.new(chaining_key, input_key_material, hashlib.sha256).digest()
    result = []
    previous = b""
    for index in range(1, outputs + 1):
        previous = hmac.new(temp_key, previous + bytes([index]), hashlib.sha256).digest()
        result.append(previous)
    return result


class CipherState:
    """The AEAD state that Noise calls a `CipherState`.

    It holds a key and a nonce counter. Each message uses the current
    counter value and then increments it. A state without a key passes the
    data through unchanged, which is what the handshake does before the
    first key is mixed in.
    """

    def __init__(self, key: bytes | None) -> None:
        self.key = key
        self.nonce = 0

    def _nonce_bytes(self) -> bytes:
        # Four zero bytes, then the counter in little-endian order.
        return b"\x00" * 4 + struct.pack("<Q", self.nonce)

    def encrypt(self, associated_data: bytes, plaintext: bytes) -> bytes:
        if self.key is None:
            return plaintext
        result = ChaCha20Poly1305(self.key).encrypt(self._nonce_bytes(), plaintext, associated_data)
        self.nonce += 1
        return result

    def decrypt(self, associated_data: bytes, ciphertext: bytes) -> bytes:
        if self.key is None:
            return ciphertext
        result = ChaCha20Poly1305(self.key).decrypt(self._nonce_bytes(), ciphertext, associated_data)
        self.nonce += 1
        return result

    def copy(self) -> "CipherState":
        """A copy with the same key and the same counter value."""
        other = CipherState(self.key)
        other.nonce = self.nonce
        return other


class SymmetricState:
    """The chaining key and the hash, as the Noise specification defines them."""

    def __init__(self, protocol_name: str) -> None:
        name = protocol_name.encode("utf-8")
        if len(name) <= 32:
            self.hash = name + b"\x00" * (32 - len(name))
        else:
            self.hash = hashlib.sha256(name).digest()
        self.chaining_key = self.hash
        self.cipher = CipherState(None)

    def mix_hash(self, data: bytes) -> None:
        self.hash = hashlib.sha256(self.hash + data).digest()

    def mix_key(self, input_key_material: bytes) -> None:
        output = hkdf(self.chaining_key, input_key_material, 2)
        self.chaining_key = output[0]
        self.cipher = CipherState(output[1])

    def mix_key_and_hash(self, input_key_material: bytes) -> None:
        output = hkdf(self.chaining_key, input_key_material, 3)
        self.chaining_key = output[0]
        self.mix_hash(output[1])
        self.cipher = CipherState(output[2])

    def encrypt_and_hash(self, plaintext: bytes) -> bytes:
        ciphertext = self.cipher.encrypt(self.hash, plaintext)
        self.mix_hash(ciphertext)
        return ciphertext

    def decrypt_and_hash(self, ciphertext: bytes) -> bytes:
        plaintext = self.cipher.decrypt(self.hash, ciphertext)
        self.mix_hash(ciphertext)
        return plaintext

    def split(self) -> tuple[CipherState, CipherState]:
        output = hkdf(self.chaining_key, b"", 2)
        return CipherState(output[0]), CipherState(output[1])

    def copy(self) -> "SymmetricState":
        """A copy of the state.

        Reading a message changes the state, so a second attempt at reading
        the same message has to start from the state as it was before the
        first attempt.
        """
        other = SymmetricState.__new__(SymmetricState)
        other.chaining_key = self.chaining_key
        other.hash = self.hash
        other.cipher = self.cipher.copy()
        return other


class Handshake:
    """The `KKpsk2` handshake, on the server side.

    The server is the initiator: it writes the first message and reads the
    second one.
    """

    def __init__(
        self,
        static_private: bytes,
        remote_static_public: bytes,
        prologue: bytes,
        ephemeral_private: X25519PrivateKey | None = None,
    ) -> None:
        self.static_private = static_private
        self.remote_static_public = remote_static_public
        self.state = SymmetricState(PROTOCOL_NAME)
        # The prologue binds the handshake to the two init messages.
        self.state.mix_hash(prologue)
        # The pre-message. The initiator's static key comes first, then the
        # responder's.
        self.state.mix_hash(raw_public(X25519PrivateKey.from_private_bytes(static_private)))
        self.state.mix_hash(remote_static_public)
        # The ephemeral key may be given, so a second attempt at reading the
        # second message uses the same one. A different key would give a
        # different shared secret, and the message would not decrypt.
        self.ephemeral_private = ephemeral_private or X25519PrivateKey.generate()
        self.ephemeral_public = raw_public(self.ephemeral_private)
        # The state as it was before the second message, for the PSK retry.
        self.state_before_message2: SymmetricState | None = None

    def write_message1(self, payload: bytes) -> bytes:
        self.state.mix_hash(self.ephemeral_public)
        self.state.mix_key(self.ephemeral_public)
        # The "es" token.
        self.state.mix_key(dh(self.ephemeral_private.private_bytes_raw(), self.remote_static_public))
        # The "ss" token.
        self.state.mix_key(dh(self.static_private, self.remote_static_public))
        ciphertext = self.state.encrypt_and_hash(payload)
        self.state_before_message2 = self.state.copy()
        return self.ephemeral_public + ciphertext

    def read_message2(self, message: bytes, psk: bytes) -> bytes:
        remote_ephemeral = message[:KEY_SIZE]
        self.state.mix_hash(remote_ephemeral)
        self.state.mix_key(remote_ephemeral)
        self.state.mix_key(dh(self.ephemeral_private.private_bytes_raw(), remote_ephemeral))
        self.state.mix_key(dh(self.static_private, remote_ephemeral))
        # The "psk" token.
        self.state.mix_key_and_hash(psk)
        return self.state.decrypt_and_hash(message[KEY_SIZE:])

    def try_read_message2(self, message: bytes, psks: list[bytes]) -> tuple[bytes, bytes]:
        """Reads the second message, trying each key in turn.

        The first key is the one the server named. The second is the
        Sentinel key, which the client falls back to. Each attempt starts
        from the state as it was before the first one, because a failed read
        changes the state.
        """
        saved = self.state_before_message2
        if saved is None:
            raise ValueError("The first message must be written first")
        for psk in psks:
            self.state = saved.copy()
            try:
                return self.read_message2(message, psk), psk
            except Exception:  # noqa: BLE001
                continue
        raise ValueError("The second handshake message did not decrypt")

    def split(self) -> tuple[CipherState, CipherState]:
        """The transport keys. The server sends with the first one."""
        return self.state.split()


class Report:
    """The record of what the server saw, for the test to read.

    Every line holds a few `key=value` pairs, so the test can read it
    without a JSON parser.
    """

    def __init__(self, path: str | None) -> None:
        self.path = path
        self.lines: list[str] = []

    def write(self, event: str, **fields: Any) -> None:
        parts = [f"event={event}"]
        for key, value in fields.items():
            if isinstance(value, bool):
                text = "true" if value else "false"
            elif value is None:
                text = "none"
            elif isinstance(value, (dict, list)):
                text = json.dumps(value, separators=(",", ":"))
            else:
                text = str(value)
            parts.append(f"{key}={text}")
        line = " ".join(parts)
        LOG.info("%s", line)
        self.lines.append(line)
        if self.path:
            with open(self.path, "a", encoding="utf-8") as handle:
                handle.write(line + "\n")


class Connection:
    """One client, and the protocol exchange with it."""

    def __init__(self, server: "SendSpinServer", websocket: Any) -> None:
        self.server = server
        self.websocket = websocket
        self.send_cipher = CipherState(None)
        self.receive_cipher = CipherState(None)
        self.client_id = ""
        self.client_hello: dict[str, Any] | None = None
        self.client_state: dict[str, Any] | None = None
        self.stream_format: dict[str, Any] | None = None
        self.audio_chunks = 0
        self.audio_bytes = 0
        self.first_timestamp = 0
        self.last_timestamp = 0
        self.time_exchanges = 0
        self.pairing_psk = ""
        self.audio_requested = False
        # The keys the re-handshake needs.
        self.server_static_private = b""
        self.client_static_public = b""
        self.handshake_hash = b""
        self.long_term_psk = b""

    async def handle(self) -> None:
        try:
            await self.handshake()
            await self.exchange()
        except asyncio.CancelledError:
            raise
        except Exception as error:  # noqa: BLE001
            LOG.info("The connection ended: %s", error)

    async def handshake(self) -> None:
        """The cleartext part of the handshake."""
        client_init_text = await self.websocket.recv()
        if isinstance(client_init_text, bytes):
            raise ValueError("The first message was not text")
        client_init = json.loads(client_init_text)
        if client_init.get("type") != "client/init":
            raise ValueError("The first message was not a client/init")
        self.client_id = client_init["payload"]["client_id"]
        suite = client_init["payload"].get("suite", "")
        if suite != "25519_ChaChaPoly_SHA256":
            raise ValueError(f"The client asked for the suite {suite}")
        self.server.report.write(
            "client-init", client_id=self.client_id, suite=suite, version=client_init["payload"].get("version")
        )

        server_static = X25519PrivateKey.generate()
        server_id = b64url(raw_public(server_static))
        server_init = json.dumps(
            {"type": "server/init", "payload": {"server_id": server_id, "version": PROTOCOL_VERSION}},
            separators=(",", ":"),
        )
        await self.websocket.send(server_init)

        # The prologue is the raw bytes of the two init messages.
        prologue = client_init_text.encode("utf-8") + server_init.encode("utf-8")
        noise = Handshake(server_static.private_bytes_raw(), unb64url(self.client_id), prologue)
        first = noise.write_message1(
            json.dumps(
                {"psk_id": psk_id(self.server.pairing_psk), "psk_category": "pr"},
                separators=(",", ":"),
            ).encode("utf-8")
        )
        await self.websocket.send(
            json.dumps({"type": "noise/handshake", "payload": {"data": b64url(first)}}, separators=(",", ":"))
        )

        answer_text = await self.websocket.recv()
        if isinstance(answer_text, bytes):
            raise ValueError("The handshake answer was not text")
        answer = json.loads(answer_text)
        if answer.get("type") != "noise/handshake":
            raise ValueError("The handshake answer was not a noise/handshake")
        message2 = unb64url(answer["payload"]["data"])

        # The client may fall back to the Sentinel key, so both are tried.
        try:
            payload, used = noise.try_read_message2(
                message2, [self.server.pairing_psk, SENTINEL_PSK]
            )
        except Exception as error:
            raise ValueError(f"The client did not answer with a key the server holds: {error}")
        self.pairing_psk = "pairing" if used == self.server.pairing_psk else "sentinel"

        send_cipher, receive_cipher = noise.split()
        self.send_cipher = send_cipher
        self.receive_cipher = receive_cipher
        # The re-handshake that follows the pairing needs these three.
        self.server_static_private = server_static.private_bytes_raw()
        self.client_static_public = unb64url(self.client_id)
        self.handshake_hash = noise.state.hash
        self.server.report.write("handshake-complete", psk=self.pairing_psk, server_id=server_id)

        # The server greets the client first. The client answers with its
        # own hello, and the server then asks to pair.
        await self.send_hello()

    async def exchange(self) -> None:
        """The encrypted part of the connection."""
        while True:
            frame = await self.websocket.recv()
            if isinstance(frame, str):
                raise ValueError("A cleartext message arrived after the handshake")
            plaintext = self.receive_cipher.decrypt(b"", frame)
            await self.handle_message(plaintext)

    async def handle_message(self, plaintext: bytes) -> None:
        """Handles one decrypted message."""
        if not plaintext:
            return
        message_type = plaintext[0]
        if message_type == MSG_FRAGMENT:
            # The server does not expect fragmented messages from a client
            # in this test, so they are reported and skipped.
            self.server.report.write("fragment-ignored", size=len(plaintext))
            return
        if message_type == MSG_SOURCE_AUDIO:
            self.read_audio(plaintext[1:])
            return
        if message_type != MSG_JSON:
            self.server.report.write("binary-ignored", message_type=message_type, size=len(plaintext))
            return
        message = json.loads(plaintext[1:].decode("utf-8"))
        kind = message.get("type", "")
        payload = message.get("payload", {})
        self.server.report.write("message", type=kind)

        if kind == "client/hello":
            self.client_hello = payload
            await self.ask_to_pair()
        elif kind == "client/time":
            await self.answer_time(payload)
        elif kind == "client/state":
            self.client_state = payload
            self.server.report.write("client-state", available=payload.get("available"))
            await self.maybe_start()
        elif kind == "client-stream/start":
            self.stream_format = payload.get("source")
            self.server.report.write("stream-start")
        elif kind == "client-stream/end":
            self.server.report.write("stream-end")
        elif kind == "client/pair-init":
            self.server.report.write("pair-init", index=payload.get("pairing_index"))
        elif kind == "client/pair-finalize":
            self.server.report.write("pair-finalize")
            self.long_term_psk = unb64url(payload["long_term_psk"])
            await self.send({"type": "server/pair-finalize", "payload": {}})
            await self.rehandshake()
        elif kind == "client/goodbye":
            self.server.report.write("goodbye", reason=payload.get("reason"))
        elif kind == "client-stream/anything":
            pass

    async def ask_to_pair(self) -> None:
        """Asks the client to pair.

        The client only offers the source role on a connection that a
        pairing opened, so the server pairs first and then asks for the
        audio.
        """
        await self.send(
            {
                "type": "server/activate",
                "payload": {
                    "activities": ["pairing"],
                    "active_roles": [],
                    "pairing": {"method": "pairing_psk"},
                },
            }
        )

    async def rehandshake(self) -> None:
        """Runs a new handshake in the channel, to move to the paired key.

        The prologue is the hash of the previous handshake. The client's
        answer comes under the old keys, and the activation that follows
        comes under the new ones.
        """
        noise = Handshake(self.server_static_private, self.client_static_public, self.handshake_hash)
        first = noise.write_message1(
            json.dumps(
                {"psk_id": psk_id(self.long_term_psk), "psk_category": "lt"},
                separators=(",", ":"),
            ).encode("utf-8")
        )
        await self.send({"type": "noise/handshake", "payload": {"data": b64url(first)}})

        frame = await self.websocket.recv()
        if isinstance(frame, str):
            raise ValueError("A cleartext message arrived during the re-handshake")
        plaintext = self.receive_cipher.decrypt(b"", frame)
        message = json.loads(plaintext[1:].decode("utf-8"))
        if message.get("type") != "noise/handshake":
            raise ValueError("The client did not answer the re-handshake")
        noise.read_message2(unb64url(message["payload"]["data"]), self.long_term_psk)

        self.send_cipher, self.receive_cipher = noise.split()
        self.handshake_hash = noise.state.hash
        self.server.report.write("rehandshake-complete")
        await self.activate_source()

    async def send_hello(self) -> None:
        """Greets the client."""
        await self.send(
            {
                "type": "server/hello",
                "payload": {
                    "name": "Interop Server",
                    "source@v1_support": {"supported_codecs": ["flac", "pcm"]},
                },
            }
        )

    async def answer_time(self, payload: dict[str, Any]) -> None:
        """Answers a clock exchange."""
        self.time_exchanges += 1
        received = self.server.now_micros()
        if self.time_exchanges <= 2:
            self.server.report.write(
                "time-exchange",
                client_transmitted=payload["client_transmitted"],
                server_received=received,
                difference=received - payload["client_transmitted"],
            )
        await self.send(
            {
                "type": "server/time",
                "payload": {
                    "client_transmitted": payload["client_transmitted"],
                    "server_received": received,
                    "server_transmitted": received + 1,
                },
            }
        )

    async def activate_source(self) -> None:
        """Activates the source role.

        The activation comes before the clock synchronisation, because the
        client only starts the clock exchanges once the role is active.
        The connection is on the paired key by now, so no activity is
        declared. A source is never in a playback group, so the server has
        no playback purpose to declare.
        """
        await self.send(
            {
                "type": "server/activate",
                "payload": {"activities": [], "active_roles": ["source@v1"]},
            }
        )

    async def maybe_start(self) -> None:
        """Asks for the audio, once the client reports itself available."""
        if self.client_hello is None or self.client_state is None:
            return
        if not self.client_state.get("available"):
            return
        if self.audio_requested:
            return
        self.audio_requested = True
        await self.send(
            {
                "type": "server/command",
                "payload": {"source": {"command": "start"}},
            }
        )

    def read_audio(self, payload: bytes) -> None:
        """Reads a source audio message.

        The body is the timestamp of the first sample, in microseconds,
        followed by the audio. The timestamp is a big-endian 64-bit number,
        the way the reference implementation writes it.
        """
        if len(payload) < 8:
            return
        timestamp = struct.unpack(">q", payload[:8])[0]
        self.offer_audio(timestamp, payload[8:])

    async def send(self, message: dict[str, Any]) -> None:
        """Sends a JSON message in the encrypted channel."""
        body = json.dumps(message, separators=(",", ":")).encode("utf-8")
        await self.websocket.send(self.send_cipher.encrypt(b"", bytes([MSG_JSON]) + body))

    def offer_audio(self, timestamp_micros: int, payload: bytes) -> None:
        """Records a source audio message that the client sent."""
        self.audio_chunks += 1
        self.audio_bytes += len(payload)
        if self.audio_chunks == 1:
            self.first_timestamp = timestamp_micros
        self.last_timestamp = timestamp_micros
        self.server.report.write(
            "audio",
            chunks=self.audio_chunks,
            bytes=self.audio_bytes,
            timestamp=timestamp_micros,
        )


class SendSpinServer:
    """The server that the clients connect to."""

    def __init__(self, port: int, pairing_psk: bytes, epoch: int, report_path: str | None) -> None:
        self.port = port
        self.pairing_psk = pairing_psk
        self.epoch = epoch
        self.report = Report(report_path)
        self.start_ns = time.monotonic_ns()
        self.connection: Connection | None = None

    def now_micros(self) -> int:
        """The server's clock, in microseconds.

        The epoch is set from the command line, so the client and the server
        run on the same clock and the offset between them stays small.
        """
        return self.epoch + (time.monotonic_ns() - self.start_ns) // 1000


async def main() -> None:
    import websockets

    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8927)
    parser.add_argument("--pairing-psk", type=str, default="")
    parser.add_argument("--epoch", type=int, default=0)
    parser.add_argument("--report", type=str, default="")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    pairing_psk = unb64url(args.pairing_psk) if args.pairing_psk else os.urandom(32)
    server = SendSpinServer(args.port, pairing_psk, args.epoch, args.report or None)

    async def handler(websocket: Any) -> None:
        connection = Connection(server, websocket)
        server.connection = connection
        await connection.handle()

    async with websockets.serve(handler, "127.0.0.1", args.port):
        LOG.info("the server listens on port %d", args.port)
        LOG.info("the pairing key is %s", b64url(pairing_psk))
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
