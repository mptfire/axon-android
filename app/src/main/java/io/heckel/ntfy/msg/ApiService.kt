package io.heckel.ntfy.msg

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.db.User
import io.heckel.ntfy.service.NotAuthorizedException
import io.heckel.ntfy.util.ALL_PRIORITIES
import io.heckel.ntfy.util.HttpUtil
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.PRIORITY_DEFAULT
import io.heckel.ntfy.util.topicUrl
import io.heckel.ntfy.util.topicUrlAuth
import io.heckel.ntfy.util.topicUrlJson
import io.heckel.ntfy.util.topicUrlJsonPoll
import okhttp3.Call
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.IOException
import java.net.URLEncoder

class ApiService(private val context: Context) {
    private val repository = Repository.getInstance(context)
    private val gson = Gson()
    private val parser = NotificationParser()

    suspend fun publish(
        baseUrl: String,
        topic: String,
        user: User? = null,
        message: String,
        title: String = "",
        priority: Int = PRIORITY_DEFAULT,
        tags: List<String> = emptyList(),
        delay: String = "",
        body: RequestBody? = null,
        filename: String = "",
        click: String = "",
        attach: String = "",
        email: String = "",
        call: String = "",
        markdown: Boolean = false,
        onCancelAvailable: ((cancel: () -> Unit) -> Unit)? = null // Called when the HTTP request was started and cancellable (caller can cancel)
    ) {
        val url = topicUrl(baseUrl, topic)
        val query = mutableListOf<String>()
        if (priority in ALL_PRIORITIES) {
            query.add("priority=$priority")
        }
        if (tags.isNotEmpty()) {
            query.add("tags=${URLEncoder.encode(tags.joinToString(","), "UTF-8")}")
        }
        if (title.isNotEmpty()) {
            query.add("title=${URLEncoder.encode(title, "UTF-8")}")
        }
        if (delay.isNotEmpty()) {
            query.add("delay=${URLEncoder.encode(delay, "UTF-8")}")
        }
        if (filename.isNotEmpty()) {
            query.add("filename=${URLEncoder.encode(filename, "UTF-8")}")
        }
        if (click.isNotEmpty()) {
            query.add("click=${URLEncoder.encode(click, "UTF-8")}")
        }
        if (attach.isNotEmpty()) {
            query.add("attach=${URLEncoder.encode(attach, "UTF-8")}")
        }
        if (email.isNotEmpty()) {
            query.add("email=${URLEncoder.encode(email, "UTF-8")}")
        }
        if (call.isNotEmpty()) {
            query.add("call=${URLEncoder.encode(call, "UTF-8")}")
        }
        if (markdown) {
            query.add("markdown=true")
        }
        if (body != null) {
            query.add("message=${URLEncoder.encode(message.replace("\n", "\\n"), "UTF-8")}")
        }
        val urlWithQuery = if (query.isNotEmpty()) {
            url + "?" + query.joinToString("&")
        } else {
            url
        }
        val customHeaders = repository.getCustomHeaders(baseUrl)
        val request = HttpUtil.requestBuilder(urlWithQuery, user, customHeaders)
            .put(body ?: message.toRequestBody())
            .build()
        Log.d(TAG, "Publishing to $request")
        val httpCall = HttpUtil.longCallClient(context, baseUrl).newCall(request)
        onCancelAvailable?.invoke { httpCall.cancel() } // Notify caller that HTTP request can now be canceled
        httpCall.execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw UnauthorizedException(user)
            } else if (response.code == 413) {
                throw EntityTooLargeException()
            } else if (!response.isSuccessful) {
                // Try to parse error response from server
                val errorBody = response.body.string()
                val apiError = try {
                    gson.fromJson(errorBody, ErrorResponse::class.java)
                } catch (e: Exception) {
                    null
                }
                if (apiError?.error != null && apiError.code != null) {
                    throw ApiException(apiError.error, apiError.code)
                }
                throw Exception("Unexpected response ${response.code} when publishing to $url")
            }
            Log.d(TAG, "Successfully published to $url")
        }
    }

    suspend fun poll(subscription: Subscription): List<Notification> {
        val subscriptionId = subscription.id
        val baseUrl = subscription.baseUrl
        val topic = subscription.topic
        val sinceVal = subscription.lastNotificationId ?: SINCE_ALL
        val url = topicUrlJsonPoll(baseUrl, topic, sinceVal)
        Log.d(TAG, "Polling topic $url")

        val user = repository.getUser(baseUrl)
        val customHeaders = repository.getCustomHeaders(baseUrl)
        val request = HttpUtil.requestBuilder(url, user, customHeaders).build()
        HttpUtil.defaultClient(context, baseUrl).newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Unexpected response ${response.code} when polling topic $url")
            }
            val body = response.body.string().trim()
            if (body.isEmpty()) return emptyList()
            val notifications = body.lines().mapNotNull { line ->
                parser.parse(line, subscriptionId = subscriptionId, baseUrl = baseUrl)
            }

            Log.d(TAG, "Notifications: $notifications")
            return notifications
        }
    }

    suspend fun subscribe(
        baseUrl: String,
        topics: String,
        since: String?,
        user: User?
    ): Pair<Call, BufferedSource> {
        val sinceVal = since ?: SINCE_ALL
        val url = topicUrlJson(baseUrl, topics, sinceVal)
        Log.d(TAG, "Opening subscription connection to $url")
        val customHeaders = repository.getCustomHeaders(baseUrl)
        val request = HttpUtil.requestBuilder(url, user, customHeaders).build()
        val call = HttpUtil.subscriberClient(context, baseUrl).newCall(request)
        val response = call.execute()
        if (!response.isSuccessful) {
            val code = response.code
            val message = response.message
            response.close()
            if (code == 401 || code == 403) {
                throw NotAuthorizedException(code, message)
            }
            throw IOException("Unexpected response $code when subscribing")
        }
        return Pair(call, response.body.source())
    }

    suspend fun checkAuth(baseUrl: String, topic: String, user: User?): Boolean {
        if (user == null) {
            Log.d(TAG, "Checking anonymous read against ${topicUrl(baseUrl, topic)}")
        } else {
            Log.d(TAG, "Checking read access for user ${user.username} against ${topicUrl(baseUrl, topic)}")
        }
        val url = topicUrlAuth(baseUrl, topic)
        val customHeaders = repository.getCustomHeaders(baseUrl)
        val request = HttpUtil.requestBuilder(url, user, customHeaders).build()
        HttpUtil.defaultClient(context, baseUrl).newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                return true
            } else if (user == null && response.code == 404) {
                return true // Special case: Anonymous login to old servers return 404 since /<topic>/auth doesn't exist
            } else if (response.code == 401 || response.code == 403) { // See server/server.go
                return false
            }
            throw Exception("Unexpected server response ${response.code}")
        }
    }

    // axon: agent channel — pairing flow (see docs/agents.md). Exchanges a
    // one-time pairing code for a device-scoped token. No credentials needed:
    // the code is the credential, the human tap is the consent.
    suspend fun pairDevice(baseUrl: String, code: String, label: String): PairResponse {
        val url = baseUrl.trimEnd('/') + "/v1/device/claim"
        val body = gson.toJson(mapOf("code" to code, "label" to label))
        val request = HttpUtil.requestBuilder(url).post(body.toRequestBody()).build()
        HttpUtil.defaultClient(context, baseUrl).newCall(request).execute().use { response ->
            val text = response.body.string()
            if (response.code == 400) {
                throw PairingInvalidException()
            } else if (!response.isSuccessful) {
                throw IOException("Unexpected response ${response.code} when pairing")
            }
            return gson.fromJson(text, PairResponse::class.java)
        }
    }

    // axon: silent build-key pairing (private builds). The key is baked into
    // the APK next to the server URL; the server mints a device-scoped token.
    suspend fun pairDeviceWithBuildKey(baseUrl: String, key: String, label: String): PairResponse {
        val url = baseUrl.trimEnd('/') + "/v1/device/claim-build"
        val body = gson.toJson(mapOf("key" to key, "label" to label))
        val request = HttpUtil.requestBuilder(url).post(body.toRequestBody()).build()
        HttpUtil.defaultClient(context, baseUrl).newCall(request).execute().use { response ->
            val text = response.body.string()
            if (response.code == 400 || response.code == 404) {
                throw PairingInvalidException()
            } else if (!response.isSuccessful) {
                throw IOException("Unexpected response ${response.code} when pairing")
            }
            return gson.fromJson(text, PairResponse::class.java)
        }
    }

    // axon: fetch the account (username for the paired user; tokens are
    // stripped server-side for device tokens)
    suspend fun account(user: User): AccountResponse {
        val url = user.baseUrl.trimEnd('/') + "/v1/account"
        val customHeaders = repository.getCustomHeaders(user.baseUrl)
        val request = HttpUtil.requestBuilder(url, user, customHeaders).build()
        HttpUtil.defaultClient(context, user.baseUrl).newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected response ${response.code} when fetching account")
            }
            return gson.fromJson(response.body.string(), AccountResponse::class.java)
        }
    }

    // axon: read this device's agent-channel config (applied on sync)
    suspend fun deviceConfig(user: User, deviceId: String): DeviceConfig? {
        val url = user.baseUrl.trimEnd('/') + "/v1/device/" + deviceId + "/config"
        val customHeaders = repository.getCustomHeaders(user.baseUrl)
        val request = HttpUtil.requestBuilder(url, user, customHeaders).build()
        HttpUtil.defaultClient(context, user.baseUrl).newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                return null // device was unpaired server-side; stop syncing
            } else if (!response.isSuccessful) {
                throw IOException("Unexpected response ${response.code} when reading device config")
            }
            return gson.fromJson(response.body.string(), DeviceConfig::class.java)
        }
    }

    data class PairResponse(val device_id: String, val token: String, val base_url: String)

    data class AccountResponse(val username: String)

    data class DeviceConfig(val subscriptions: List<DeviceConfigSubscription>?, val manage: String?)

    data class DeviceConfigSubscription(
        val topic: String,
        val base_url: String?,
        val muted: Boolean?,
        val min_priority: Int?,
        val auto_delete_seconds: Long?,
        val insistent: Boolean?,
        val display_name: String?
    )

    class PairingInvalidException : Exception("pairing code invalid, expired, or already used")

    class UnauthorizedException(val user: User?) : Exception()
    class EntityTooLargeException : Exception()
    class ApiException(val error: String, val code: Int) : Exception(error)

    private data class ErrorResponse(
        val code: Int?,
        val http: Int?,
        val error: String?
    )

    companion object {
        private const val TAG = "NtfyApiService"

        // These constants have corresponding values in the server codebase!
        const val CONTROL_TOPIC = "~control"
        const val EVENT_MESSAGE = "message"
        const val EVENT_MESSAGE_DELETE = "message_delete"
        const val EVENT_MESSAGE_CLEAR = "message_clear"
        const val EVENT_KEEPALIVE = "keepalive"
        const val EVENT_POLL_REQUEST = "poll_request"

        // Valid values for the "since" parameter
        const val SINCE_ALL = "all"   // Retrieve all cached messages
        const val SINCE_NONE = "none" // Don't retrieve any old messages
    }
}
