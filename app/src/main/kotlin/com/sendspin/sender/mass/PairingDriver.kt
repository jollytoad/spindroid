package com.sendspin.sender.mass

import android.util.Log
import com.sendspin.mass.MusicAssistantClient
import com.sendspin.mass.SetupFlowEntry
import com.sendspin.mass.SetupFlowResult
import com.sendspin.mass.SetupFlowStep

/**
 * Pairs the phone through the Music Assistant setup flow.
 *
 * Music Assistant pairs a device with a short code. The server shows the
 * form, the phone shows the code, and the server compares the two. The
 * driver answers every form that the flow sends, so the user only signs in.
 *
 * The driver runs on a thread of its own. The listener must be up before
 * it starts, because the server dials the phone and waits for it.
 *
 * The driver tolerates the steps that it does not know. A form that it
 * cannot answer aborts the flow, so the server does not keep it open, and
 * the reason reaches the screen.
 *
 * @param client the client for the server
 * @param token the token of the signed in account
 * @param playerId the player that the server knows the phone by
 * @param pairingCode the code that the phone shows, or null before the
 *   server starts the pairing
 * @param isPaired whether the phone's session is already paired, which
 *   makes an `already_paired` abort a success
 */
class PairingDriver(
    private val client: MusicAssistantClient,
    private val token: String,
    private val playerId: String,
    private val pairingCode: () -> String?,
    private val isPaired: () -> Boolean = { false },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Runs the flow until the phone is paired.
     *
     * @return the outcome, with the reason when the pairing failed
     */
    fun pair(): PairingResult {
        val deadline = now() + OVERALL_TIMEOUT_MILLIS
        var step = when (val started = client.startPlayerSetup(token, playerId)) {
            is SetupFlowResult.Step -> started.step
            is SetupFlowResult.Failure -> return failure(started.reason)
        }
        while (true) {
            Log.i(
                TAG,
                "Step ${step.stepId} (${step.type}) entries=${step.entries.map { it.key }} " +
                    "errors=${step.errors} reason=${step.reason}",
            )
            if (now() > deadline) {
                return fail(step, "Music Assistant did not finish the pairing in time.")
            }
            when {
                step.isForm -> {
                    if (step.errors.isNotEmpty()) {
                        return fail(step, step.errors.values.first())
                    }
                    val values = valuesFor(step, deadline)
                        ?: return fail(
                            step,
                            "Music Assistant asked for a value that the app cannot supply.",
                        )
                    step = when (val submitted = client.submitSetupFlow(token, step.flowId, values)) {
                        is SetupFlowResult.Step -> submitted.step
                        is SetupFlowResult.Failure -> return failure(submitted.reason)
                    }
                }

                step.isProgress -> {
                    sleep(POLL_MILLIS)
                    step = when (val current = client.getSetupFlow(token, step.flowId)) {
                        is SetupFlowResult.Step -> current.step
                        is SetupFlowResult.Failure -> return failure(current.reason)
                    }
                }

                step.isFinished -> return PairingResult.Paired

                step.isAborted -> {
                    // The server aborts with `already_paired` when it holds a
                    // record for the phone. The text depends on the server's
                    // language, so the session decides, not the words.
                    if (isPaired() || step.reason == REASON_ALREADY_PAIRED) {
                        return PairingResult.Paired
                    }
                    return PairingResult.Failed(
                        step.reason ?: "Music Assistant stopped the pairing.",
                    )
                }

                else -> return fail(
                    step,
                    "Music Assistant sent a step that the app does not know: ${step.type}",
                )
            }
        }
    }

    /**
     * The answers to one form.
     *
     * The flow names every field, and the app answers the fields that the
     * Sendspin provider uses. Any other field takes the value that the
     * server suggested, so a newer server does not break the pairing.
     */
    private fun valuesFor(step: SetupFlowStep, deadline: Long): Map<String, String>? {
        val values = linkedMapOf<String, String>()
        for (entry in step.entries) {
            if (entry.isTextOnly) continue
            val value = when (entry.key) {
                // The choice between connecting straight away and pairing
                // first. The app pairs, because that is the point of the flow.
                ENTRY_CONNECT_METHOD -> entry.optionValues.firstOrNull { it == CONNECT_PAIR }

                // The choice between pairing and declining the audio input.
                ENTRY_SOURCE_INPUT_ACTION -> entry.optionValues.firstOrNull { it == CONNECT_PAIR }

                // The dynamic PIN needs no code from the user, because the
                // phone shows the code. A server that offers only the plain
                // PIN method still pairs, because the phone derives the code
                // the same way.
                ENTRY_PAIRING_METHOD -> preferred(
                    entry,
                    PAIR_METHOD_DYNAMIC_PIN,
                    PAIR_METHOD_PIN,
                )

                ENTRY_PAIRING_PIN -> awaitPairingCode(deadline)

                // A field that this app does not know. The server's own
                // default is the safe answer.
                else -> entry.value ?: entry.optionValues.firstOrNull()
            } ?: return null
            values[entry.key] = value
        }
        return values
    }

    /** The first value that the entry offers, in the order of preference. */
    private fun preferred(entry: SetupFlowEntry, vararg values: String): String? {
        val offered = entry.optionValues
        return values.firstOrNull { it in offered }
    }

    /** Waits until the phone shows the pairing code. */
    private fun awaitPairingCode(deadline: Long): String? {
        while (now() < deadline) {
            pairingCode()?.let { return it }
            sleep(POLL_MILLIS)
        }
        return null
    }

    /** Aborts the flow and reports the reason. */
    private fun fail(step: SetupFlowStep, reason: String): PairingResult {
        if (isPaired()) return PairingResult.Paired
        if (step.flowId.isNotBlank()) client.abortSetupFlow(token, step.flowId)
        return PairingResult.Failed(reason)
    }

    /**
     * Reports a failure, or a success when the phone is already paired.
     *
     * The server drops a finished flow, so a later read of that flow fails.
     * The session tells whether the pairing worked, and it is the answer
     * that the user needs.
     */
    private fun failure(reason: String): PairingResult =
        if (isPaired()) PairingResult.Paired else PairingResult.Failed(reason)

    private companion object {
        const val TAG = "SendSpin"

        const val ENTRY_CONNECT_METHOD = "connect_method"
        const val ENTRY_SOURCE_INPUT_ACTION = "source_input_action"
        const val ENTRY_PAIRING_METHOD = "pairing_method"
        const val ENTRY_PAIRING_PIN = "pairing_pin"

        const val CONNECT_PAIR = "pair"
        const val PAIR_METHOD_DYNAMIC_PIN = "dynamic_pin"
        const val PAIR_METHOD_PIN = "pin"

        const val REASON_ALREADY_PAIRED = "already_paired"

        /** How long the whole flow may take. */
        const val OVERALL_TIMEOUT_MILLIS = 90_000L

        /** How long a wait sleeps between two checks. */
        const val POLL_MILLIS = 500L
    }
}

/** The outcome of an automatic pairing. */
sealed interface PairingResult {
    /** The server holds a pairing for the phone. */
    data object Paired : PairingResult

    /**
     * The pairing did not finish.
     *
     * @param reason the reason the server gave, or the step that failed
     */
    data class Failed(val reason: String) : PairingResult
}
