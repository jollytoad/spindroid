package com.sendspin.sender.mass

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Finds Music Assistant on the local network.
 *
 * The server advertises itself as `_mass._tcp.local.`, and the record gives
 * the address of its web interface. The phone needs that address to sign in,
 * because the address of the server is not something the user should have
 * to read off a screen.
 */
class MassDiscovery(
    context: Context,
) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.DiscoveryListener? = null
    private var resolver: NsdManager.ResolveListener? = null

    /**
     * Looks for the server.
     *
     * The first server that answers wins. A network can hold more than one
     * Music Assistant, and the phone cannot tell them apart, so the first
     * one is as good as any.
     *
     * @param onFound the base address of the server, such as
     *   `http://192.168.8.242:8095`
     * @param onFailed the reason the search failed
     */
    fun discover(onFound: (String) -> Unit, onFailed: (String) -> Unit) {
        stop()
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolve(serviceInfo, onFound, onFailed)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String?) = Unit

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                onFailed("The search for Music Assistant failed with the code $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
        }
        listener = discovery
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    /** Stops the search. */
    fun stop() {
        resolver?.let {
            try {
                manager.stopServiceResolution(it)
            } catch (exception: IllegalArgumentException) {
                Log.w(TAG, "The resolution was already stopped", exception)
            }
        }
        resolver = null
        listener?.let {
            try {
                manager.stopServiceDiscovery(it)
            } catch (exception: IllegalArgumentException) {
                Log.w(TAG, "The search was already stopped", exception)
            }
        }
        listener = null
    }

    /**
     * Resolves one service.
     *
     * The browse only gives the name of the service. The address comes from
     * the resolve, which asks the server directly.
     */
    private fun resolve(
        serviceInfo: NsdServiceInfo,
        onFound: (String) -> Unit,
        onFailed: (String) -> Unit,
    ) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "The address of Music Assistant could not be read: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val address = addressOf(serviceInfo)
                if (address == null) {
                    onFailed("Music Assistant gave no address")
                    return
                }
                onFound("http://$address:${serviceInfo.port}")
            }
        }
        resolver = resolveListener
        manager.resolveService(serviceInfo, resolveListener)
    }

    /**
     * The address of a resolved service.
     *
     * The service may hold several addresses. The phone talks over the
     * local network, so the first IPv4 address is the one to use. A server
     * that holds only IPv6 still works, so the first address of any kind
     * is the fallback.
     */
    private fun addressOf(serviceInfo: NsdServiceInfo): String? {
        val addresses = hostAddressesOf(serviceInfo)
        val chosen = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
        return chosen?.hostAddress
    }

    /**
     * The addresses of a resolved service.
     *
     * Android 14 replaced the single host with a list of addresses, so both
     * are read. The old one is deprecated but the app still runs on Android
     * 13.
     */
    @Suppress("DEPRECATION")
    private fun hostAddressesOf(serviceInfo: NsdServiceInfo): List<InetAddress> {
        val many = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfo.hostAddresses
        } else {
            null
        }
        if (!many.isNullOrEmpty()) return many
        val one = serviceInfo.host ?: return emptyList()
        return listOf(one)
    }

    companion object {
        private const val TAG = "SendSpin"

        /** The service type that Music Assistant advertises itself under. */
        const val SERVICE_TYPE: String = "_mass._tcp."
    }
}
