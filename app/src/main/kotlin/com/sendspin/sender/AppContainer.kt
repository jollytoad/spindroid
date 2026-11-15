package com.sendspin.sender

import android.content.Context
import com.sendspin.sender.mass.MassDiscovery
import com.sendspin.sender.mass.MusicAssistantDiscovery

/**
 * The dependencies that the app builds at start up.
 *
 * The screen needs to see a Music Assistant that it cannot reach during a
 * test. The network search is the only dependency with that problem today,
 * so the container holds one factory for it. A test replaces the factory
 * before the activity starts.
 */
object AppContainer {
    /**
     * Makes the search for Music Assistant.
     *
     * The app uses the real mDNS search. A UI test swaps in a fake, because
     * an emulator does not pass multicast reliably, and a test must not
     * depend on what the rest of the network holds.
     */
    var discoveryFactory: (Context) -> MusicAssistantDiscovery = { context ->
        MassDiscovery(context)
    }
}
