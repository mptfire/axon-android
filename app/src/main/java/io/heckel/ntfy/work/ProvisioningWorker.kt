package io.heckel.ntfy.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.heckel.ntfy.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * axon: file-based zero-input pairing for agent-steered devices. The agent
 * (MCP) mints a pairing code, drops
 *
 *     <external-files-dir>/axon-provision.json
 *     {"server":"https://…","code":"xxxxxxxx","label":"…"}          (label optional)
 *
 * and restarts the app (or just lets the 15-min periodic tick fire). This
 * worker hands the claim to DevicePairingWorker — the exact same background
 * path as the auto=1 deep link — and removes the file. No screen, no deep
 * link, no build-time secrets; the code itself is the credential (single-use,
 * 5-minute TTL server-side).
 *
 * A malformed file is renamed to .bad instead of retried forever.
 */
class ProvisioningWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val dir = applicationContext.getExternalFilesDir(null) ?: return Result.success()
        val file = File(dir, PROVISION_FILE)
        if (!file.exists()) return Result.success()
        try {
            val obj = JSONObject(file.readText())
            val server = obj.optString("server").trim()
            val code = obj.optString("code").trim()
            val label = obj.optString("label", "agent").trim()
            if (server.isEmpty() || code.isEmpty()) {
                file.renameTo(File(dir, PROVISION_FILE + ".bad"))
                Log.w(TAG, "Provision file missing server/code — renamed .bad")
                return Result.success()
            }
            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(DevicePairingWorker.KEY_SERVER, server)
                .putString(DevicePairingWorker.KEY_CODE, code)
                .putString(DevicePairingWorker.KEY_LABEL, label)
                .putLong(DevicePairingWorker.KEY_TS, System.currentTimeMillis())
                .apply()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                "axon-pairing",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DevicePairingWorker>().build()
            )
            file.delete()
            Log.i(TAG, "Provision claim handed to DevicePairingWorker (server=" + server + ")")
        } catch (e: Exception) {
            Log.w(TAG, "Provision file unreadable — renamed .bad", e)
            try {
                file.renameTo(File(dir, PROVISION_FILE + ".bad"))
            } catch (_: Exception) {
            }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "NtfyProvisioningWorker"
        private const val PREFS = "axon_pairing"
        const val PROVISION_FILE = "axon-provision.json"

        /**
         * Idempotent: schedules the periodic provision check and runs one
         * immediate pass. Call from every app entry point.
         */
        fun ensureScheduled(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(
                "axon-provisioning",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ProvisioningWorker>(15, TimeUnit.MINUTES).build()
            )
            wm.enqueueUniqueWork(
                "axon-provisioning-now",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ProvisioningWorker>().build()
            )
        }
    }
}
