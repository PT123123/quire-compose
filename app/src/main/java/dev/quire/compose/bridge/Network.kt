package dev.quire.compose.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

/** LAN transport selection and a standing subscription to network changes. */
object Network {

    /**
     * The Wi-Fi driver filters incoming broadcast and multicast packets to
     * save power, and it does so per-process: without this lock the sync
     * listener binds its UDP port and then hears nothing — two phones on the
     * same Wi-Fi would both announce into the void and never meet. Acquiring
     * it is what LocalSend-class apps do before they can see each other at
     * all, so discovery does the same: held for the whole process run, taken
     * once (reference-counted off, so repeated calls are free), and released
     * by the system when the process dies.
     */
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * Take the multicast lock so discovery's listener receives what the LAN
     * sends. Safe to call on every sync start; only the first takes a lock.
     */
    @Suppress("DEPRECATION") // WifiManager is the only door to a MulticastLock.
    fun acquireMulticastLock(context: Context) {
        if (multicastLock?.isHeld == true) return
        val wifi =
            context.applicationContext.getSystemService(WifiManager::class.java) ?: return
        val lock = wifi.createMulticastLock("quire-sync-discovery").apply {
            setReferenceCounted(false)
        }
        lock.acquire()
        multicastLock = lock
    }

    /**
     * Whether a physical Wi-Fi network is available for LAN sync. The default
     * network can be a VPN tunnel even while Wi-Fi is the path to nearby peers.
     * Validation is not required: an internet-free router is still a LAN.
     */
    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return wifiNetwork(cm) != null
    }

    /** Bind this app's future sockets to the physical Wi-Fi network for LAN sync. */
    fun bindProcessToLanWifi(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        cm.bindProcessToNetwork(wifiNetwork(cm))
    }

    @Suppress("DEPRECATION")
    private fun wifiNetwork(cm: ConnectivityManager): Network? = cm.allNetworks.firstOrNull { network ->
        val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /**
     * Watch both the default route and physical Wi-Fi. The latter matters when
     * a VPN remains the default while the device joins or leaves a LAN.
     */
    fun observe(context: Context, onChange: (Boolean) -> Unit): () -> Unit {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return {}
        val handler = Handler(Looper.getMainLooper())

        val defaultCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                bindProcessToLanWifi(context)
                onChange(isOnWifi(context))
            }

            override fun onLost(network: Network) {
                bindProcessToLanWifi(context)
                onChange(isOnWifi(context))
            }
        }
        val wifiCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network) ?: return
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) return
                bindProcessToLanWifi(context)
                onChange(true)
            }

            override fun onLost(network: Network) {
                bindProcessToLanWifi(context)
                onChange(isOnWifi(context))
            }
        }

        cm.registerDefaultNetworkCallback(defaultCallback, handler)
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build(),
            wifiCallback,
            handler,
        )
        return {
            cm.unregisterNetworkCallback(defaultCallback)
            cm.unregisterNetworkCallback(wifiCallback)
        }
    }
}
