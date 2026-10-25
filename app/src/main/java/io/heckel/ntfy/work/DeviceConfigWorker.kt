package io.heckel.ntfy.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.ui.PairingActivity
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * axon: agent channel — periodically applies the paired device's config so
 * agent-made changes (subscribe/mute, and removals when the owner granted
 * full management) land without the user opening the app.
 */
class DeviceConfigWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    init {
        Log.init(ctx)
    }

    override suspend fun doWork(): Result = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val repository = Repository.getInstance(applicationContext)
        val paired = repository.getPairedDevice() ?: return@withContext Result.success() // Nothing paired
        try {
            val api = ApiService(applicationContext)
            val changed = PairingActivity.applyDeviceConfig(
                repository, api, paired.baseUrl, paired.username, paired.token, paired.deviceId
            )
            if (changed > 0) {
                SubscriberServiceManager(applicationContext).refresh()
            }
            writeAppliedMirror(applicationContext, repository, paired.baseUrl, paired.deviceId)
            Result.success()
        } catch (e: ApiService.DeviceRevokedException) {
            // Revoked/unpaired: retrying every 15 min would hammer the server
            // forever — stop until the user re-pairs (audit A11/#2).
            Log.e(TAG, "Device token rejected (unpaired or revoked) — stopping config sync", e)
            Result.failure()
        } catch (e: Exception) {
            Log.w(TAG, "Device config sync failed (will retry on next period)", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "NtfyDeviceConfigWorker"

        /**
         * axon: applied-config mirror. After every sync, write what the device
         * ACTUALLY has subscribed to shared external storage so the owning
         * agent can read applied state without a server-side ack API (AX-4).
         * Best-effort: never breaks the sync cycle.
         */
        private suspend fun writeAppliedMirror(context: Context, repository: Repository, baseUrl: String, deviceId: String) {
            try {
                val dir = context.getExternalFilesDir(null) ?: return
                val topics = repository.getSubscriptions()
                    .filter { it.baseUrl == baseUrl && it.upAppId == null }
                    .map { it.topic }
                    .sorted()
                val obj = org.json.JSONObject()
                obj.put("device_id", deviceId)
                obj.put("base_url", baseUrl)
                obj.put("topics", org.json.JSONArray(topics))
                obj.put("ts", System.currentTimeMillis())
                val tmp = java.io.File(dir, "applied.json.tmp")
                tmp.writeText(obj.toString())
                val dst = java.io.File(dir, "applied.json")
                if (!tmp.renameTo(dst)) {
                    tmp.delete()
                }
                Log.d(TAG, "Applied mirror written: " + topics.size + " topics")
            } catch (e: Exception) {
                Log.w(TAG, "Applied mirror write failed (non-fatal)", e)
            }
        }
    }
}
