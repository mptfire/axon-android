package io.heckel.ntfy.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import com.google.android.material.color.DynamicColors
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class Application : Application() {
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val repository by lazy {
        val repository = Repository.getInstance(applicationContext)
        if (repository.getRecordLogs()) {
            Log.setRecord(true)
        }
        repository
    }

    override fun onCreate() {
        super.onCreate()
        maybeAutoPair() // axon: silent build-key pairing (private builds)
        if (repository.getDynamicColorsEnabled()) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            // If there's already a default network at registration time, registerDefaultNetworkCallback
            // will deliver an initial onAvailable for it. That's not a real transition, so skip the
            // first onAvailable in that case to avoid a spurious reconnect on every cold start.
            private var skipInitialAvailable = connectivityManager.activeNetwork != null

            override fun onAvailable(network: Network) {
                if (skipInitialAvailable) {
                    skipInitialAvailable = false
                    Log.d(TAG, "Skipping initial onAvailable for pre-existing default network ($network)")
                    return
                }
                // Force reconnect of all WebSocket/JSON connections so they're rebound to the new
                // default network. This catches Wi-Fi <-> cellular handoffs and similar transitions
                // where the underlying socket is bound to a network that's no longer the default.
                // Without this, broken connections would only be detected via the (potentially
                // long) ping/pong timeout.
                Log.i(TAG, "Default network available ($network); forcing reconnect of all connections")
                ioScope.launch {
                    repository.getSubscriptions()
                        .map { it.baseUrl }
                        .distinct()
                        .forEach { repository.incrementConnectionForceReconnectVersion(it) }
                    SubscriberServiceManager.refresh(this@Application)
                }
            }
            override fun onLost(network: Network) {
                // Once we've observed a loss, any subsequent onAvailable is a real transition.
                skipInitialAvailable = false
                Log.i(TAG, "Default network lost ($network); refreshing subscriber service")
                SubscriberServiceManager.refresh(this@Application)
            }
        })
    }

    companion object {
        private const val TAG = "NtfyApplication"
    }
}


// axon: zero-input pairing for private builds. Runs on every app entry point
// (activity, service, worker); does nothing once paired or when the build has
// no key. After pairing it applies the agent-channel config immediately, so a
// freshly installed app is fully configured from one single launch.
private fun Application.maybeAutoPair() {
    val key = getString(io.heckel.ntfy.R.string.axon_pairing_key)
    if (key.isBlank()) return
    val repository = io.heckel.ntfy.db.Repository.getInstance(this)
    if (repository.getPairedDevice() != null) return
    val baseUrl = getString(io.heckel.ntfy.R.string.app_base_url)
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        try {
            val api = io.heckel.ntfy.msg.ApiService(this@maybeAutoPair)
            val claim = api.pairDeviceWithBuildKey(baseUrl, key, android.os.Build.MODEL ?: "device")
            val username = try {
                api.account(io.heckel.ntfy.db.User(baseUrl, "", claim.token)).username ?: "user"
            } catch (e: Exception) {
                io.heckel.ntfy.util.Log.w("NtfyAutoPair", "Account lookup failed after claim (continuing)", e)
                "user"
            }
            // axon: pair locally FIRST — everything below is best-effort
            repository.setPairedDevice(baseUrl, claim.device_id, username, claim.token)
            try {
                // Upsert: a pre-pairing manual login leaves a User row for this
                // server; plain insert would abort and leave the app paired
                // server-side but unpaired locally
                if (repository.getUser(baseUrl) != null) {
                    repository.updateUser(io.heckel.ntfy.db.User(baseUrl, username, claim.token))
                } else {
                    repository.addUser(io.heckel.ntfy.db.User(baseUrl, username, claim.token))
                }
            } catch (e: Exception) {
                io.heckel.ntfy.util.Log.w("NtfyAutoPair", "User upsert after claim failed (continuing)", e)
            }
            io.heckel.ntfy.ui.PairingActivity.applyDeviceConfig(repository, api, baseUrl, username, claim.token, claim.device_id)
            io.heckel.ntfy.service.SubscriberServiceManager(this@maybeAutoPair).refresh()
            androidx.work.WorkManager.getInstance(this@maybeAutoPair).enqueueUniquePeriodicWork(
                "axon-device-config",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                androidx.work.PeriodicWorkRequestBuilder<io.heckel.ntfy.work.DeviceConfigWorker>(15, java.util.concurrent.TimeUnit.MINUTES).build()
            )
            io.heckel.ntfy.util.Log.i("NtfyAutoPair", "Device auto-paired via build key: " + claim.device_id)
        } catch (e: Exception) {
            io.heckel.ntfy.util.Log.w("NtfyAutoPair", "Auto-pairing failed (will retry on next launch)", e)
        }
    }
}
