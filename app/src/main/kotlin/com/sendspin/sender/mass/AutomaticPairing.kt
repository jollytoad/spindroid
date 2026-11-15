package com.sendspin.sender.mass

import android.content.Context
import android.content.Intent
import android.util.Log
import com.sendspin.mass.MusicAssistantClient
import com.sendspin.sender.service.SendSpinService
import com.sendspin.sender.session.PairingServiceHolder
import com.sendspin.sender.session.SessionStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pairs the phone right after the user signs in.
 *
 * The user should not paste a token after a sign in. The app starts the
 * listener that the server dials, waits for the advertisement, and runs
 * the Music Assistant setup flow. The user only signs in.
 *
 * Only an administrator may pair the phone this way, because the server
 * only lets an administrator change its players. An account without that
 * right keeps the manual token, which needs no right.
 *
 * @param context the context that starts the listener
 * @param accounts the manager that reports the sign ins
 * @param playerId the player that the server knows the phone by
 */
class AutomaticPairing(
    context: Context,
    private val accounts: MassAccountManager,
    private val playerId: String,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow<AutomaticPairingState>(AutomaticPairingState.Idle)

    /** The state of the automatic pairing. */
    val state: StateFlow<AutomaticPairingState> = mutableState.asStateFlow()

    /**
     * Starts to wait for a sign in.
     *
     * The sign in is the only moment that the app learns of a new account,
     * so the pairing starts from the sign in and not from the screen.
     */
    fun start() {
        scope.launch {
            accounts.signInEvents.collect { event ->
                mutableState.value = AutomaticPairingState.Idle
                if (!event.administrator) return@collect
                pair(event)
            }
        }
    }

    /** Forgets the last pairing, because the account signed out. */
    fun reset() {
        mutableState.value = AutomaticPairingState.Idle
    }

    /** Stops the work that is running. */
    fun close() {
        scope.cancel()
    }

    /**
     * Runs the pairing for one sign in.
     *
     * The listener must advertise the phone before the flow starts, because
     * the server dials the phone and waits for it.
     */
    private suspend fun pair(event: MassSignInEvent) {
        mutableState.value = AutomaticPairingState.Pairing
        // A capture already advertises the phone, so the pairing uses that
        // service and leaves it running.
        val captureRuns = SessionStateHolder.running.value
        if (!captureRuns) SendSpinService.startPairing(appContext)
        try {
            val listening = withTimeoutOrNull(LISTENER_TIMEOUT_MILLIS) {
                PairingServiceHolder.running.first { it }
            }
            if (listening == null) {
                fail(
                    PairingServiceHolder.error.value
                        ?: "Music Assistant did not find the phone in time.",
                )
                return
            }
            Log.i(TAG, "The listener runs, so the pairing starts")
            // Music Assistant keeps the player of a disconnected phone for a
            // moment, and the setup flow refuses that player because the
            // connection has no security yet. The flow waits for the new
            // session, so the server sees the phone as connected.
            val session = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
                SessionStateHolder.state.first { it.serverId != null }
            }
            if (session == null) {
                fail("Music Assistant did not connect to the phone in time.")
                return
            }
            val result = withContext(Dispatchers.IO) {
                PairingDriver(
                    client = MusicAssistantClient(event.serverUrl),
                    token = event.token,
                    playerId = playerId,
                    pairingCode = { SessionStateHolder.state.value.pairingCode },
                    isPaired = { SessionStateHolder.state.value.paired },
                ).pair()
            }
            Log.i(TAG, "The pairing finished: $result")
            when (result) {
                is PairingResult.Paired -> mutableState.value = AutomaticPairingState.Paired
                is PairingResult.Failed -> fail(result.reason)
            }
        } finally {
            if (!captureRuns) {
                appContext.stopService(Intent(appContext, SendSpinService::class.java))
            }
        }
    }

    /**
     * Reports a failure, and shows the manual token again.
     *
     * The session belongs to the failed attempt, so it goes. A session that
     * stays would hide the token and leave the user without a way to pair.
     */
    private fun fail(reason: String) {
        SessionStateHolder.reset()
        mutableState.value = AutomaticPairingState.Failed(reason)
    }

    private companion object {
        const val TAG = "SendSpin"

        /** How long the phone may take to advertise itself. */
        const val LISTENER_TIMEOUT_MILLIS = 20_000L

        /** How long Music Assistant may take to connect to the phone. */
        const val CONNECT_TIMEOUT_MILLIS = 20_000L
    }
}

/** The state of the automatic pairing. */
sealed interface AutomaticPairingState {
    /** No sign in has started a pairing. */
    data object Idle : AutomaticPairingState

    /** The app is pairing the phone. */
    data object Pairing : AutomaticPairingState

    /** The phone is paired. */
    data object Paired : AutomaticPairingState

    /**
     * The pairing did not finish.
     *
     * @param reason the reason the server gave, or the step that failed
     */
    data class Failed(val reason: String) : AutomaticPairingState
}
