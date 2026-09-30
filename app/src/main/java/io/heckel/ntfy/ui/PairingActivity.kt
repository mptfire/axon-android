package io.heckel.ntfy.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.db.User
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.randomSubscriptionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * axon: device pairing (agent channel, see docs/agents.md).
 *
 * Reached via the axon://pair/<code> deep link, or opened manually to paste a
 * code. The code is minted by the user's agent (MCP request_pairing) or the
 * web UI. This activity exchanges it for a device-scoped token, stores it as
 * the app's credentials for the server, and applies the device config
 * (subscriptions, mutes) from the agent channel. The human tap on "Pair" is
 * the consent step — minting the code alone grants nothing.
 */
class PairingActivity : AppCompatActivity() {
    private lateinit var repository: Repository
    private lateinit var api: ApiService
    private var wasAutoPair = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.init(this)
        setContentView(R.layout.activity_pairing)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        repository = Repository.getInstance(this)
        api = ApiService(this)

        val baseUrlView = findViewById<EditText>(R.id.pairing_base_url)
        val codeView = findViewById<EditText>(R.id.pairing_code)
        val labelView = findViewById<EditText>(R.id.pairing_label)
        val statusView = findViewById<TextView>(R.id.pairing_status)
        val progress = findViewById<ProgressBar>(R.id.pairing_progress)
        val pairButton = findViewById<Button>(R.id.pairing_button)

        baseUrlView.setText(getString(R.string.app_base_url))

        // Deep link: axon://pair/<code>[?auto=1][&server=https://…][&label=…].
        // With auto=1 (fired by the user's AI agent, which minted the code
        // server-side) pairing runs immediately — no tap. server=/label= make
        // that zero-input end to end: the claim target is the very server that
        // minted the code, and codes are single-use with a 5-minute TTL, so
        // this is equivalent to a human typing the URL into these fields. The
        // code itself is the credential.
        val data = intent?.data
        val autoPair = data != null && data.scheme == "axon" && data.host == "pair" &&
            data.getQueryParameter("auto") == "1"
        wasAutoPair = autoPair
        if (data != null && data.scheme == "axon" && data.host == "pair") {
            codeView.setText(data.pathSegments.firstOrNull() ?: "")
            data.getQueryParameter("server")?.trim()?.takeIf { it.isNotEmpty() }?.let { baseUrlView.setText(it) }
            data.getQueryParameter("label")?.trim()?.takeIf { it.isNotEmpty() }?.let { labelView.setText(it) }
        }
        if (autoPair && codeView.text.isNotBlank()) {
            // axon: complete the claim in the background — the activity may be
            // gone within seconds (user switches apps, screen off, incoming
            // call). WorkManager owns the claim now and retries until the code
            // expires; this activity just gets out of the way.
            val prefs = getSharedPreferences("axon_pairing", Context.MODE_PRIVATE)
            prefs.edit()
                .putString(io.heckel.ntfy.work.DevicePairingWorker.KEY_SERVER, baseUrlView.text.toString().trim())
                .putString(io.heckel.ntfy.work.DevicePairingWorker.KEY_CODE, codeView.text.toString().trim())
                .putString(io.heckel.ntfy.work.DevicePairingWorker.KEY_LABEL, labelView.text.toString().trim())
                .putLong(io.heckel.ntfy.work.DevicePairingWorker.KEY_TS, System.currentTimeMillis())
                .apply()
            androidx.work.WorkManager.getInstance(this).enqueueUniqueWork(
                "axon-pairing",
                androidx.work.ExistingWorkPolicy.REPLACE,
                androidx.work.OneTimeWorkRequestBuilder<io.heckel.ntfy.work.DevicePairingWorker>()
                    .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
            )
            finish()
            return
        }

        pairButton.setOnClickListener {
            val baseUrl = baseUrlView.text.toString().trim()
            val code = codeView.text.toString().trim()
            val label = labelView.text.toString().trim()
            if (baseUrl.isEmpty() || code.isEmpty()) {
                statusView.text = getString(R.string.pairing_error_fields)
                return@setOnClickListener
            }
            setBusy(true, progress, pairButton, statusView)
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val claim = api.pairDevice(baseUrl, code, label)
                    // Username for display; device tokens get the account with
                    // token values stripped server-side
                    val username = try {
                        api.account(User(baseUrl, "", claim.token)).username
                    } catch (e: Exception) {
                        "user"
                    }
                    repository.addUser(User(baseUrl, username, claim.token))
                    repository.setPairedDevice(baseUrl, claim.device_id, username, claim.token)
                    val added = applyDeviceConfig(repository, api, baseUrl, username, claim.token, claim.device_id)
                    SubscriberServiceManager(this@PairingActivity).refresh()
                    // Schedule the periodic agent-channel sync here too: an
                    // agent-driven pairing may never open MainActivity
                    androidx.work.WorkManager.getInstance(this@PairingActivity).enqueueUniquePeriodicWork(
                        "axon-device-config",
                        androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                        androidx.work.PeriodicWorkRequestBuilder<io.heckel.ntfy.work.DeviceConfigWorker>(15, java.util.concurrent.TimeUnit.MINUTES).build()
                    )
                    launch(Dispatchers.Main) {
                        setBusy(false, progress, pairButton, statusView)
                        statusView.text = getString(R.string.pairing_success, username, added)
                        if (wasAutoPair) {
                            // Agent-driven: flash the result and dismiss — no
                            // lingering screen, no tap needed
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish() }, 1500)
                        } else {
                            pairButton.text = getString(R.string.pairing_done)
                            pairButton.setOnClickListener { finish() }
                        }
                    }
                } catch (e: ApiService.PairingInvalidException) {
                    launch(Dispatchers.Main) {
                        setBusy(false, progress, pairButton, statusView)
                        statusView.text = getString(R.string.pairing_error_invalid)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Pairing failed", e)
                    launch(Dispatchers.Main) {
                        setBusy(false, progress, pairButton, statusView)
                        statusView.text = getString(R.string.pairing_error_server, e.message)
                    }
                }
            }
        }
    }

    private fun setBusy(busy: Boolean, progress: ProgressBar, button: Button, status: TextView) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        button.isEnabled = !busy
        if (busy) status.text = getString(R.string.pairing_in_progress)
    }

    companion object {
        private const val TAG = "NtfyPairing"

        /**
         * Apply the agent-channel config: add missing subscriptions and update
         * mute state. Never removes local subscriptions — removal stays a human
         * action in the app UI. Returns the number of subscriptions added.
         */
        suspend fun applyDeviceConfig(repository: Repository, api: ApiService, baseUrl: String, username: String, token: String, deviceId: String): Int {
            val user = User(baseUrl, username, token)
            val config = try {
                api.deviceConfig(user, deviceId)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read device config", e)
                null
            } ?: return 0
            // Full management (config.manage == "full", set by the owner via the
            // agent channel): subscriptions absent from the config are removed.
            // Default stays add-only — removal remains a human action unless the
            // owner explicitly granted full control for this device.
            // Full management removals apply to THIS channel's server only —
            // foreign servers (added below) keep their human-managed state
            if (config.manage == "full") {
                val wanted = config.subscriptions.orEmpty().filter { it.base_url.isNullOrEmpty() || it.base_url == baseUrl }.map { it.topic }.toSet()
                val locals = repository.getSubscriptions().filter { it.baseUrl == baseUrl && it.upAppId == null }
                locals.filter { it.topic !in wanted }.forEach { repository.removeSubscription(it) }
            }
            var added = 0
            config.subscriptions.orEmpty().forEach { sub ->
                if (sub.topic.isEmpty()) return@forEach
                // Cross-server: the config may manage subscriptions on other
                // ntfy servers too (base_url per entry); auth comes from the
                // credentials the app already stores per server.
                val subBaseUrl = sub.base_url?.takeIf { it.isNotBlank() } ?: baseUrl
                val existing = repository.getSubscription(subBaseUrl, sub.topic)
                if (existing == null) {
                    repository.addSubscription(
                        Subscription(
                            id = randomSubscriptionId(),
                            baseUrl = subBaseUrl,
                            topic = sub.topic,
                            instant = false,
                            dedicatedChannels = false,
                            mutedUntil = if (sub.muted == true) Long.MAX_VALUE else 0L,
                            minPriority = sub.min_priority ?: Repository.MIN_PRIORITY_USE_GLOBAL,
                            autoDelete = Repository.AUTO_DELETE_USE_GLOBAL,
                            insistent = Repository.INSISTENT_MAX_PRIORITY_USE_GLOBAL,
                            lastNotificationId = null,
                            icon = null,
                            upAppId = null,
                            upConnectorToken = null,
                            displayName = null,
                            totalCount = 0,
                            newCount = 0,
                            lastActive = 0L
                        )
                    )
                    added++
                } else {
                    // Sync every agent-managed per-topic setting; anything the
                    // config omits keeps its current (possibly human-set) value
                    val mutedUntil = when (sub.muted) {
                        true -> Long.MAX_VALUE
                        false -> 0L
                        null -> existing.mutedUntil
                    }
                    val minPriority = sub.min_priority ?: existing.minPriority
                    val autoDelete = sub.auto_delete_seconds ?: existing.autoDelete
                    val insistent = when (sub.insistent) {
                        true -> 1
                        false -> 0
                        null -> existing.insistent
                    }
                    val displayName = sub.display_name ?: existing.displayName
                    if (existing.mutedUntil != mutedUntil || existing.minPriority != minPriority ||
                        existing.autoDelete != autoDelete || existing.insistent != insistent ||
                        existing.displayName != displayName
                    ) {
                        repository.updateSubscription(
                            existing.copy(
                                mutedUntil = mutedUntil,
                                minPriority = minPriority,
                                autoDelete = autoDelete,
                                insistent = insistent,
                                displayName = displayName
                            )
                        )
                    }
                }
            }
            return added
        }
    }
}
