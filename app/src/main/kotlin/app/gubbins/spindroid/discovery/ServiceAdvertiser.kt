package app.gubbins.spindroid.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The advertisement of the app on the local network.
 *
 * The server finds the client over this advertisement, and then connects
 * to it. The advertisement only runs while the app listens for incoming
 * connections, because the specification forbids advertising and dialing
 * at the same time.
 */
class ServiceAdvertiser(
    context: Context,
    private val name: String,
    private val port: Int,
) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val registered = AtomicBoolean(false)
    private var listener: NsdManager.RegistrationListener? = null

    /** Whether the advertisement is running. */
    val isRegistered: Boolean get() = registered.get()

    /**
     * Starts the advertisement.
     *
     * The registration is asynchronous, so the listener reports when it is
     * running.
     */
    fun start(onRegistered: (NsdServiceInfo?) -> Unit = {}, onFailed: (String) -> Unit = {}) {
        if (registered.get()) return
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            port = this@ServiceAdvertiser.port
            setAttribute("path", PATH)
            setAttribute("name", name)
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registered.set(true)
                onRegistered(serviceInfo)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                registered.set(false)
                onFailed("The advertisement failed with the code $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                registered.set(false)
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                registered.set(false)
                onFailed("The advertisement could not be stopped")
            }
        }
        listener = registration
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
    }

    /** Stops the advertisement. */
    fun stop() {
        val registration = listener ?: return
        listener = null
        registered.set(false)
        try {
            manager.unregisterService(registration)
        } catch (exception: IllegalArgumentException) {
            Log.w(TAG, "The advertisement was already stopped", exception)
        }
    }

    companion object {
        private const val TAG = "SpinDroid"

        /** The service type the specification gives the clients. */
        const val SERVICE_TYPE = "_sendspin._tcp."

        /** The path of the WebSocket endpoint. */
        const val PATH = "/sendspin"

        /** The port the app listens on. */
        const val PORT = 8928
    }
}
