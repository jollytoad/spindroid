package com.sendspin.mass

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * One step of a Music Assistant setup flow.
 *
 * A setup flow is the guided set of forms that pairs a device or adds a
 * provider. The server sends one step at a time, and the client answers a
 * form with the values that the user chose.
 *
 * @param flowId the identifier of the flow that the step belongs to
 * @param stepId the name of the step, such as `enter_pin`
 * @param type the kind of step, such as `form` or `finish`
 * @param title the title to show, in the server's words
 * @param description the explanation to show, in the server's words
 * @param entries the fields of a form
 * @param errors the reasons that the server gave, by field name
 * @param reason the reason of an abort, in the server's words
 * @param result the object that a finished flow created or changed
 * @param progressText the state that a progress step reports
 * @param url the address of an external step, such as an OAuth page
 * @param copyText the value that an external step asks the user to copy
 */
@JsonClass(generateAdapter = true)
public data class SetupFlowStep(
    @Json(name = "flow_id") val flowId: String = "",
    @Json(name = "step_id") val stepId: String = "",
    val type: String = TYPE_UNKNOWN,
    val title: String? = null,
    val description: String? = null,
    val entries: List<SetupFlowEntry> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val reason: String? = null,
    val result: Map<String, String>? = null,
    @Json(name = "progress_text") val progressText: String? = null,
    val url: String? = null,
    @Json(name = "copy_text") val copyText: String? = null,
) {
    /** Whether the step is a form that waits for values. */
    public val isForm: Boolean get() = type == TYPE_FORM

    /** Whether the flow finished. */
    public val isFinished: Boolean get() = type == TYPE_FINISH

    /** Whether the flow ended without a result. */
    public val isAborted: Boolean get() = type == TYPE_ABORT

    /** Whether the server is working, and the client only waits. */
    public val isProgress: Boolean get() = type == TYPE_PROGRESS

    public companion object {
        /** The step holds fields that the user must answer. */
        public const val TYPE_FORM: String = "form"

        /** The step waits for something outside the app, such as an OAuth page. */
        public const val TYPE_EXTERNAL: String = "external"

        /** The server is working, and the client waits. */
        public const val TYPE_PROGRESS: String = "progress"

        /** The flow finished. */
        public const val TYPE_FINISH: String = "finish"

        /** The flow ended without a result. */
        public const val TYPE_ABORT: String = "abort"

        /** The type that this version of the app does not know. */
        public const val TYPE_UNKNOWN: String = "unknown"
    }
}

/**
 * One field of a setup form.
 *
 * @param key the name that the server reads the value by
 * @param type the kind of field, such as `string` or `label`
 * @param value the value that the server suggests
 * @param options the values that the user may choose
 */
@JsonClass(generateAdapter = true)
public data class SetupFlowEntry(
    val key: String,
    val type: String = "",
    val value: String? = null,
    val options: List<SetupFlowOption> = emptyList(),
) {
    /** The values that the entry accepts, when it offers a choice. */
    public val optionValues: List<String> get() = options.map { it.value }

    /** Whether the entry only shows text, and takes no value. */
    public val isTextOnly: Boolean get() = type in TEXT_ONLY_TYPES

    public companion object {
        /**
         * The entry kinds that show text and take no answer.
         *
         * A `pairing_code` entry is not here, because the operator types
         * the code that the device shows into that field.
         */
        public val TEXT_ONLY_TYPES: Set<String> = setOf(
            "label",
            "divider",
            "alert",
            "image",
            "url",
            "action",
            "icon",
        )
    }
}

/**
 * One value that a setup form offers.
 *
 * @param value the value that the server reads
 * @param label the name to show, in the server's words
 * @param disabled the server does not accept this value now
 */
@JsonClass(generateAdapter = true)
public data class SetupFlowOption(
    val value: String,
    val label: String? = null,
    val disabled: Boolean = false,
)

/** The outcome of a setup flow command. */
public sealed interface SetupFlowResult {
    /**
     * The server sent the next step.
     *
     * @param step the step that the client must render or answer
     */
    public data class Step(val step: SetupFlowStep) : SetupFlowResult

    /**
     * The server did not send a step.
     *
     * @param reason the reason the server gave, in its own words
     */
    public data class Failure(val reason: String) : SetupFlowResult
}

/** The outcome of an action that does not return a step. */
public sealed interface SetupActionResult {
    /** The server took the command. */
    public data object Success : SetupActionResult

    /**
     * The server did not take the command.
     *
     * @param reason the reason the server gave, in its own words
     */
    public data class Failure(val reason: String) : SetupActionResult
}

/** The envelope of the command that starts the setup of a player. */
@JsonClass(generateAdapter = true)
internal data class PlayerSetupRequest(
    @Json(name = "message_id") val messageId: String,
    val command: String,
    val args: PlayerSetupArgs,
)

/** The arguments of the command that starts the setup of a player. */
@JsonClass(generateAdapter = true)
internal data class PlayerSetupArgs(
    @Json(name = "player_id") val playerId: String,
)

/** The envelope of a command that names a running flow. */
@JsonClass(generateAdapter = true)
internal data class FlowIdRequest(
    @Json(name = "message_id") val messageId: String,
    val command: String,
    val args: FlowIdArgs,
)

/** The arguments of a command that names a running flow. */
@JsonClass(generateAdapter = true)
internal data class FlowIdArgs(
    @Json(name = "flow_id") val flowId: String,
)

/** The envelope of the command that answers a form. */
@JsonClass(generateAdapter = true)
internal data class FlowSubmitRequest(
    @Json(name = "message_id") val messageId: String,
    val command: String,
    val args: FlowSubmitArgs,
)

/** The arguments of the command that answers a form. */
@JsonClass(generateAdapter = true)
internal data class FlowSubmitArgs(
    @Json(name = "flow_id") val flowId: String,
    val values: Map<String, String>,
)
