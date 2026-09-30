package dev.quire.compose.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/**
 * The device's current transport, and a standing subscription to its changes.
 *
 * Why Kotlin and not Rust: there is no Rust-side way to ask which transport the
 * default route uses. `ConnectivityManager` and `NetworkCapabilities` are the
 * platform's answer, and the platform is Kotlin (ADR-0032).
 *
 * Why it matters: LAN sync's cadence is a policy decision, and a phone's honest
 * answer differs from a desktop's. A round costs the two devices on a home LAN
 * a few milliseconds and a phone's battery on mobile data something real, so
 * the phone runs its aggressive cadence *because* it is on Wi-Fi rather than
 * always. On Wi-Fi this shell is indistinguishable from the desktop.
 *
 * [isOnWifi] answers "right now", for the initial value. [observe] is the
 * standing side: the sync page has to learn about a change without the user
 * going to the page, which is the same reason the engine starts at launch.
 */
object Network {

    /**
     * Whether the device's *default* network is Wi-Fi.
     *
     * The default network rather than "is there a Wi-Fi interface", because
     * having a Wi-Fi radio is not the same as routing over it — a phone can hold
     * a saved SSID while its default route is cellular, and asking about the
     * interface in that case would dial the LAN over mobile data every ten
     * seconds, which is exactly the outcome this whole mechanism exists to
     * prevent.
     *
     * `NET_CAPABILITY_VALIDATED` is deliberately not required: a LAN the router
     * has no internet for is still a LAN, and both devices are on it. Being
     * unable to reach the internet does not stop two devices from finding each
     * other.
     */
    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Call [onChange] whenever the default network's transport changes.
     *
     * Registered on `registerDefaultNetworkCallback`, which is API 24 — exactly
     * this app's `minSdk`, so there is no compat path to write and no case
     * where the callback silently does not exist.
     *
     * Returns a lambda that unregisters. It must be called when the process no
     * longer wants updates, or the callback holds the `ConnectivityManager`'s
     * registration for the life of the process — the one leak-shaped decision in
     * this file.
     */
    fun observe(context: Context, onChange: (Boolean) -> Unit): () -> Unit {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return {}
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                // A default-network callback fires for the network becoming
                // usable, not for a transport flipping on an already-usable one
                // (Wi-Fi → cellular hands over through `onLost`/`onAvailable`
                // here, but a network gaining Wi-Fi while already validated does
                // not always). Re-reading the *current* capabilities and
                // reporting the fact — rather than the event's guess — is what
                // makes the callback answer the question the policy asks.
                onChange(wifi)
            }

            override fun onLost(network: Network) {
                // The lost network may or may not have been the default by now,
                // so answer about the default rather than about this one.
                onChange(isOnWifi(context))
            }
        }
        // `registerDefaultNetworkCallback` takes no `NetworkRequest` — which is
        // exactly the point of it: it follows the *default* route, the one the
        // cadence asks about, and not every network the device can see. Building
        // a request here and passing it would have been `registerNetworkCallback`,
        // which fires for a Wi-Fi network the device is on while still routing
        // over cellular — the answer this must not give.
        //
        // The Handler overload is the API 24 form; callbacks arrive on the main
        // looper, which is where the bridge call wants to be.
        cm.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        return { cm.unregisterNetworkCallback(callback) }
    }
}
