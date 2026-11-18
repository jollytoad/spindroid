package app.gubbins.sendspin.protocol

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import java.nio.charset.StandardCharsets
import okio.Buffer

/**
 * Reads and writes the JSON messages.
 *
 * Every message has a `type` and a `payload`. The payload shape depends on
 * the type, so the codec keeps a table of adapters, one per message type.
 *
 * The bytes a client sends are hashed into the handshake prologue, so the
 * encoder must be stable: the same message always produces the same bytes.
 *
 * The envelope is written and read by hand rather than through a generic
 * map. A generic map would turn every number into a double, which loses the
 * microsecond timestamps that the protocol carries.
 */
public class MessageCodec(
    moshi: Moshi = Moshi.Builder().add(JsonOptionalAdapterFactory()).build(),
) {
    /** The payload type of one message, and the adapter that reads it. */
    private class PayloadType(
        val payloadClass: Class<*>,
        val adapter: JsonAdapter<Any>,
    )

    private val payloads: Map<String, PayloadType> = buildPayloads(moshi)

    /**
     * Encodes a message.
     *
     * @param type the message type
     * @param payload the message body
     * @throws IllegalArgumentException when the payload does not match the
     *   type
     */
    public fun <T : Any> encode(type: String, payload: T): ByteArray {
        val entry = payloads[type]
            ?: throw IllegalArgumentException("There is no payload type for $type")
        if (!entry.payloadClass.isInstance(payload)) {
            throw IllegalArgumentException(
                "The payload of $type must be a ${entry.payloadClass.simpleName}",
            )
        }
        val buffer = Buffer()
        val writer = JsonWriter.of(buffer)
        writer.beginObject()
        writer.name("type").value(type)
        writer.name("payload")
        entry.adapter.toJson(writer, payload)
        writer.endObject()
        return buffer.readByteString().toByteArray()
    }

    /**
     * Decodes a message.
     *
     * @param type the message type
     * @param bytes the message bytes
     * @throws IllegalArgumentException when the message is malformed or the
     *   payload does not match the type
     */
    @Suppress("UNCHECKED_CAST")
    public fun <T : Any> decode(type: String, bytes: ByteArray): T {
        val entry = payloads[type]
            ?: throw IllegalArgumentException("There is no payload type for $type")
        if (typeOf(bytes) != type) {
            throw IllegalArgumentException("The message is not a $type")
        }
        val reader = JsonReader.of(Buffer().write(bytes))
        try {
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "payload" -> {
                        val payload = entry.adapter.fromJson(reader)
                            ?: throw IllegalArgumentException("The payload of $type is malformed")
                        return payload as T
                    }

                    else -> reader.skipValue()
                }
            }
        } catch (exception: IllegalArgumentException) {
            throw exception
        } catch (exception: Exception) {
            throw IllegalArgumentException("The message is not valid JSON", exception)
        }
        throw IllegalArgumentException("The message has no payload")
    }

    /**
     * Reads the type out of a message without decoding the payload.
     *
     * The handshake needs the type of the first messages before it can
     * decode them, because the body shape depends on the type.
     */
    public fun typeOf(bytes: ByteArray): String {
        val reader = JsonReader.of(Buffer().write(bytes))
        var type: String? = null
        try {
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "type" -> type = reader.nextString()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        } catch (exception: IllegalArgumentException) {
            throw exception
        } catch (exception: Exception) {
            throw IllegalArgumentException("The message is not valid JSON", exception)
        }
        return type ?: throw IllegalArgumentException("The message has no type")
    }

    private companion object {
        fun buildPayloads(moshi: Moshi): Map<String, PayloadType> {
            @Suppress("UNCHECKED_CAST")
            fun <T : Any> payload(clazz: Class<T>): PayloadType =
                PayloadType(clazz, moshi.adapter(clazz) as JsonAdapter<Any>)

            return mapOf(
                MessageType.CLIENT_INIT to payload(ClientInitPayload::class.java),
                MessageType.SERVER_INIT to payload(ServerInitPayload::class.java),
                MessageType.NOISE_HANDSHAKE to payload(NoiseHandshakePayload::class.java),
                MessageType.SERVER_ERROR to payload(ServerErrorPayload::class.java),
                MessageType.SERVER_HELLO to payload(ServerHelloPayload::class.java),
                MessageType.CLIENT_HELLO to payload(ClientHelloPayload::class.java),
                MessageType.SERVER_ACTIVATE to payload(ServerActivatePayload::class.java),
                MessageType.CLIENT_TIME to payload(ClientTimePayload::class.java),
                MessageType.SERVER_TIME to payload(ServerTimePayload::class.java),
                MessageType.CLIENT_STATE to payload(ClientStatePayload::class.java),
                MessageType.SERVER_STATE to payload(ServerStatePayload::class.java),
                MessageType.SERVER_COMMAND to payload(ServerCommandPayload::class.java),
                MessageType.CLIENT_COMMAND to payload(ClientCommandPayload::class.java),
                MessageType.CLIENT_GOODBYE to payload(ClientGoodbyePayload::class.java),
                MessageType.CLIENT_LEAVE to payload(ClientLeavePayload::class.java),
                MessageType.CLIENT_STREAM_START to payload(ClientStreamStartPayload::class.java),
                MessageType.CLIENT_STREAM_END to payload(ClientStreamEndPayload::class.java),
                MessageType.STREAM_START to payload(StreamStartPayload::class.java),
                MessageType.STREAM_END to payload(StreamEndPayload::class.java),
                MessageType.STREAM_CLEAR to payload(StreamClearPayload::class.java),
                MessageType.GROUP_UPDATE to payload(GroupUpdatePayload::class.java),
                MessageType.SERVER_UNPAIR to payload(ServerUnpairPayload::class.java),
                MessageType.CLIENT_PAIR_INIT to payload(ClientPairInitPayload::class.java),
                MessageType.CLIENT_PAIR_PENDING to payload(ClientPairPendingPayload::class.java),
                MessageType.CLIENT_PAIR_FINALIZE to payload(ClientPairFinalizePayload::class.java),
                MessageType.SERVER_PAIR_FINALIZE to payload(ServerPairFinalizePayload::class.java),
                MessageType.SERVER_PAIR_INIT to payload(ServerPairInitPayload::class.java),
                MessageType.SERVER_PAIR_AUTH to payload(ServerPairAuthPayload::class.java),
                MessageType.CLIENT_PAIR_AUTH to payload(ClientPairAuthPayload::class.java),
                MessageType.SERVER_PAIR_CONFIRM to payload(ServerPairConfirmPayload::class.java),
                MessageType.CLIENT_PAIR_CONFIRM to payload(ClientPairConfirmPayload::class.java),
                MessageType.CLIENT_PAIR_RETRY to payload(ClientPairRetryPayload::class.java),
                MessageType.PAIR_ABORT to payload(PairAbortPayload::class.java),
            )
        }
    }
}
