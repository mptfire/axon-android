package io.heckel.ntfy.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkerParameters
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.User
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.ui.PairingActivity
import io.heckel.ntfy.util.Log

/**
 * axon: completes a zero-input deep-link pairing outside any activity
 * lifecycle. PairingActivity (auto=1) stashes the claim request in prefs,
 * enqueues this worker, and finishes immediately — switching apps, a locked
 * screen, or the user closing the activity can no longer cancel the claim.
 * Retries with backoff until the pairing code expires (5 minutes).
 */
class DevicePairingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val baseUrl = prefs.getString(KEY_SERVER, null) ?: return Result.success()
        val code = prefs.getString(KEY_CODE, null) ?: return Result.success()
        val label = prefs.getString(KEY_LABEL, "") ?: ""
        val startedAt = prefs.getLong(KEY_TS, 0L)
        val api = ApiService(applicationContext)
        val repository = Repository.getInstance(applicationContext)
        return try {
            val claim = api.pairDevice(baseUrl, code, label)
            // Username for display; device tokens get the account with token
            // values stripped server-side
            val username = try {
                api.account(User(baseUrl, "", claim.token)).username ?: "user"
            } catch (e: Exception) {
                Log.w(TAG, "Account lookup failed after claim (continuing)", e)
                "user"
            }
            // axon: pair locally FIRST — everything below is best-effort. The
            // previous order meant any failure left the app paired on the
            // server but locally unpaired, and every agent-channel sync
            // silently skipped.
            repository.setPairedDevice(baseUrl, claim.device_id, username, claim.token)
            try {
                // Upsert: a pre-pairing manual login leaves a User row for
                // this server; plain insert would abort and leave the app
                // paired server-side but unpaired locally
                if (repository.getUser(baseUrl) != null) {
                    repository.updateUser(User(baseUrl, username, claim.token))
                } else {
                    repository.addUser(User(baseUrl, username, claim.token))
                }
            } catch (e: Exception) {
                Log.w(TAG, "User upsert after claim failed (continuing)", e)
            }
            try {
                PairingActivity.applyDeviceConfig(repository, api, baseUrl, username, claim.token, claim.device_id)
            } catch (e: Exception) {
                Log.w(TAG, "Config sync after pairing failed (scheduled sync will retry)", e)
            }
            SubscriberServiceManager(applicationContext).refresh()
            // Schedule the periodic agent-channel sync: an agent-driven pairing
            // may never open MainActivity
            androidx.work.WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                "axon-device-config",
                ExistingPeriodicWorkPolicy.KEEP,
                androidx.work.PeriodicWorkRequestBuilder<DeviceConfigWorker>(15, java.util.concurrent.TimeUnit.MINUTES).build()
            )
            prefs.edit().clear().apply()
            Log.i(TAG, "Deep-link pairing completed: " + claim.device_id)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Deep-link pairing attempt failed, will retry", e)
            if (startedAt > 0 && System.currentTimeMillis() - startedAt > CODE_TTL_MS) {
                prefs.edit().clear().apply()
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "NtfyPairingWorker"
        private const val PREFS = "axon_pairing"
        private const val CODE_TTL_MS = 5 * 60 * 1000L
        const val KEY_SERVER = "server"
        const val KEY_CODE = "code"
        const val KEY_LABEL = "label"
        const val KEY_TS = "ts"
    }
}
