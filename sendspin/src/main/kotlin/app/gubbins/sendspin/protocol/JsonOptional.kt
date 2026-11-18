package app.gubbins.sendspin.protocol

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * A JSON field that can be missing, present and null, or present with a
 * value.
 *
 * The specification tells those first two apart. In a `server/state` message,
 * omitting a role object leaves that role's state unchanged, while setting
 * the same object to `null` clears it. A plain nullable field cannot carry
 * that difference, because both cases leave the field null in the data
 * class.
 */
public sealed class JsonOptional<out T> {
    /** The field was not in the JSON object. */
    public data object Absent : JsonOptional<Nothing>()

    /** The field was in the JSON object. The value may be null. */
    public data class Present<out T>(val value: T?) : JsonOptional<T>()
}

/** Whether the field was in the JSON object, whatever its value was. */
public val JsonOptional<*>.isPresent: Boolean
    get() = this is JsonOptional.Present

/**
 * The value of the field, or null when the field was absent.
 *
 * A field that was present and null also gives null, so use [isPresent] when
 * the difference matters.
 */
public fun <T> JsonOptional<T>.valueOrNull(): T? =
    (this as? JsonOptional.Present)?.value

/**
 * The value of the field, or [fallback] when the field was absent or null.
 */
public fun <T> JsonOptional<T>.valueOr(fallback: T): T =
    (this as? JsonOptional.Present)?.value ?: fallback

/**
 * The Moshi factory for [JsonOptional].
 *
 * Register it on the [Moshi] builder. The adapter for a field is only asked
 * for the value when the key is in the JSON object, so a missing key takes
 * the default of the field, which is [JsonOptional.Absent].
 */
public class JsonOptionalAdapterFactory : JsonAdapter.Factory {
    override fun create(
        type: Type,
        annotations: Set<Annotation>,
        moshi: Moshi,
    ): JsonAdapter<*>? {
        if (type !is ParameterizedType) return null
        if (type.rawType != JsonOptional::class.java) return null
        val inner = moshi.adapter<Any?>(type.actualTypeArguments[0])
        return OptionalAdapter(inner)
    }

    private class OptionalAdapter(
        private val inner: JsonAdapter<Any?>,
    ) : JsonAdapter<JsonOptional<Any?>>() {
        override fun fromJson(reader: JsonReader): JsonOptional<Any?> {
            // A null value is a field that is present with a null value,
            // which is not the same as a field that is missing.
            if (reader.peek() == JsonReader.Token.NULL) {
                reader.nextNull<Any?>()
                return JsonOptional.Present(null)
            }
            return JsonOptional.Present(inner.fromJson(reader))
        }

        override fun toJson(writer: JsonWriter, value: JsonOptional<Any?>?) {
            when (value) {
                null, is JsonOptional.Absent -> {
                    // The writer leaves nulls out by default, so the field
                    // never reaches the wire.
                    val previous = writer.serializeNulls
                    writer.serializeNulls = false
                    writer.nullValue()
                    writer.serializeNulls = previous
                }

                is JsonOptional.Present -> {
                    val inner = value.value
                    if (inner == null) {
                        // The field is present and null, which the writer
                        // has to be told to write out.
                        val previous = writer.serializeNulls
                        writer.serializeNulls = true
                        writer.nullValue()
                        writer.serializeNulls = previous
                    } else {
                        this.inner.toJson(writer, inner)
                    }
                }
            }
        }
    }
}
