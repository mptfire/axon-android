package io.heckel.ntfy.ui

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

        // Deep link: axon://pair/<code>[?auto=1]. With auto=1 (fired by the
        // user's AI agent, which minted the code server-side) pairing runs
        // immediately — no tap. The code itself is the credential.
        val data = intent?.data
        val autoPair = data != null && data.scheme == "axon" && data.host == "pair" &&
            data.getQueryParameter("auto") == "1"
        if (data != null && data.scheme == "axon" && data.host == "pair") {
            codeView.setText(data.pathSegments.firstOrNull() ?: "")
        }
        if (autoPair && codeView.text.isNotBlank()) {
            pairButton.performClick()
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
                    launch(Dispatchers.Main) {
                        setBusy(false, progress, pairButton, statusView)
                        statusView.text = getString(R.string.pairing_success, username, added)
                        pairButton.text = getString(R.string.pairing_done)
                        pairButton.setOnClickListener { finish() }
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
