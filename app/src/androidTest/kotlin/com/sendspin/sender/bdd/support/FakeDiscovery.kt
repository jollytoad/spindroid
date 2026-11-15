package com.sendspin.sender.bdd.support

import com.sendspin.sender.mass.DiscoveredServer
import com.sendspin.sender.mass.MusicAssistantDiscovery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A Music Assistant that a test puts on the network.
 *
 * An emulator does not pass multicast reliably, and a test must not depend
 * on what the rest of the network holds. The steps publish the server they
 * need, and the screen shows it as if the mDNS search had found it.
 */
class FakeDiscovery : MusicAssistantDiscovery {
    private val mutableServer = MutableStateFlow<DiscoveredServer?>(null)
    private val mutableError = MutableStateFlow<String?>(null)

    override val server: StateFlow<DiscoveredServer?> = mutableServer.asStateFlow()
    override val error: StateFlow<String?> = mutableError.asStateFlow()

    override fun start() = Unit

    override fun stop() = Unit

    /** Puts a server on the network. */
    fun publish(discovered: DiscoveredServer) {
        mutableServer.value = discovered
    }

    /** Removes the server, because a fresh install starts with none. */
    fun reset() {
        mutableServer.value = null
        mutableError.value = null
    }
}

/**
 * The fake that the rule and the steps share.
 *
 * The rule builds it before the activity starts, and the steps change it
 * afterwards, so both must reach the same object.
 */
object FakeDiscoveryHolder {
    val discovery = FakeDiscovery()
}
