package com.example.assist

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Arrays
import java.util.concurrent.TimeUnit

/**
 * GeminiByokClient:
 * Direct user-initiated REST client to Google Gemini API (v1beta) using user's own BYOK key.
 * - Strict TLS 1.3 / 1.2 ConnectionSpecs, zero HTTP disk cache, zero logging of headers/keys.
 * - Optional Orbot/Tor SOCKS5 (127.0.0.1:9050) or custom SOCKS5 transport.
 * - Key is passed exclusively via `x-goog-api-key` header and wiped immediately.
 */
object GeminiByokClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private const val ANONYMOUS_USER_AGENT = "Zver/1.0"

    private val directClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(50, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .cache(null)
            .build()
    }

    @Volatile
    private var cachedProxyKey: String = ""

    @Volatile
    private var cachedProxyClient: OkHttpClient? = null

    fun resolveEffectiveProxy(config: ByokManager.ProxyTransportConfig): Proxy? {
        return when (config.mode) {
            ByokManager.TransportMode.DIRECT_TLS -> null
            ByokManager.TransportMode.TOR_ORBOT_SOCKS5,
            ByokManager.TransportMode.CUSTOM_SOCKS5 -> {
                val host = config.host.trim().ifEmpty { ByokManager.DEFAULT_TOR_HOST }
                val port = config.port.coerceIn(1, 65535)
                Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host, port))
            }
        }
    }

    internal fun buildClientForTransport(config: ByokManager.ProxyTransportConfig): OkHttpClient {
        if (config.mode == ByokManager.TransportMode.DIRECT_TLS) return directClient
        val cacheId = "${config.mode.name}:${config.host}:${config.port}"
        val existing = cachedProxyClient
        if (existing != null && cachedProxyKey == cacheId) return existing
        val proxy = resolveEffectiveProxy(config)
        val built = OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS))
            .proxy(proxy)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(80, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .cache(null)
            .build()
        cachedProxyKey = cacheId
        cachedProxyClient = built
        return built
    }

    data class GenerationResponse(
        val text: String,
        val modelId: String,
        val promptTokens: Int,
        val outputTokens: Int,
        val totalTokens: Int,
        val latencyMs: Long
    )

    suspend fun fetchAvailableModels(
        apiKeyChars: CharArray,
        transportConfig: ByokManager.ProxyTransportConfig = ByokManager.ProxyTransportConfig()
    ): Result<List<ByokManager.DiscoveredModel>> = withContext(Dispatchers.IO) {
        val activeClient = buildClientForTransport(transportConfig)
        val keyCopy = apiKeyChars.clone()
        val keyString = String(keyCopy).trim()
        try {
            if (keyString.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("API-ключ пустой"))
            }

            val allModels = mutableListOf<ByokManager.DiscoveredModel>()
            val seenTokens = mutableSetOf<String>()
            var pageToken: String? = null
            var pages = 0

            do {
                val pageStart = System.currentTimeMillis()
                val url = buildString {
                    append("$BASE_URL/models?pageSize=100")
                    if (!pageToken.isNullOrBlank()) {
                        append("&pageToken=${java.net.URLEncoder.encode(pageToken, "UTF-8")}")
                    }
                }
                val req = Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", ANONYMOUS_USER_AGENT)
                    .addHeader("x-goog-api-key", keyString)
                    .build()

                activeClient.newCall(req).execute().use { resp ->
                    val latency = System.currentTimeMillis() - pageStart
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val errMsg = ByokManager.sanitizeForLog(extractApiError(bodyStr, resp.code), keyString)
                        ByokManager.recordHttpLog(
                            method = "GET",
                            endpoint = "/v1beta/models?page=${pages + 1}",
                            httpCode = resp.code,
                            latencyMs = latency,
                            detail = errMsg,
                            secretKey = keyString
                        )
                        return@withContext Result.failure(IllegalStateException(errMsg))
                    }
                    val root = JSONObject(bodyStr)
                    val modelsArr = root.optJSONArray("models") ?: JSONArray()
                    var pageAdded = 0
                    for (i in 0 until modelsArr.length()) {
                        val obj = modelsArr.getJSONObject(i)
                        val methodsArr = obj.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                        var supportsGenerate = false
                        for (j in 0 until methodsArr.length()) {
                            if (methodsArr.optString(j) == "generateContent") {
                                supportsGenerate = true
                                break
                            }
                        }
                        if (!supportsGenerate) continue

                        val id = obj.optString("name").removePrefix("models/").trim()
                        if (id.isEmpty()) continue
                        if (id.contains("embedding", ignoreCase = true) || id.contains("aqa", ignoreCase = true)) {
                            continue
                        }

                        allModels.add(
                            ByokManager.DiscoveredModel(
                                id = id,
                                displayName = obj.optString("displayName", id).ifBlank { id },
                                description = obj.optString("description", ""),
                                inputTokenLimit = obj.optInt("inputTokenLimit", 0),
                                outputTokenLimit = obj.optInt("outputTokenLimit", 0)
                            )
                        )
                        pageAdded++
                    }
                    val nextToken = root.optString("nextPageToken", "").ifBlank { null }
                    ByokManager.recordHttpLog(
                        method = "GET",
                        endpoint = "/v1beta/models?page=${pages + 1}",
                        httpCode = resp.code,
                        latencyMs = latency,
                        detail = "raw=${modelsArr.length()}, generateContent=$pageAdded, nextPage=${nextToken != null}",
                        secretKey = keyString
                    )
                    pageToken = if (nextToken != null && seenTokens.add(nextToken)) nextToken else null
                }
                pages++
            } while (pageToken != null && pages < 15)

            val sorted = sortDiscoveredModels(allModels.distinctBy { it.id })
            Result.success(sorted)
        } catch (e: Exception) {
            val mapped = ByokManager.sanitizeForLog(mapNetworkError(e), keyString)
            ByokManager.recordHttpLog(
                method = "GET",
                endpoint = "/v1beta/models",
                httpCode = -1,
                latencyMs = 0L,
                detail = mapped,
                secretKey = keyString
            )
            Result.failure(IllegalStateException(mapped))
        } finally {
            Arrays.fill(keyCopy, '0')
        }
    }

    internal fun sortDiscoveredModels(models: List<ByokManager.DiscoveredModel>): List<ByokManager.DiscoveredModel> {
        return models.sortedWith(
            compareByDescending<ByokManager.DiscoveredModel> { modelPriorityScore(it.id) }
                .thenBy { it.id }
        )
    }

    private fun modelPriorityScore(id: String): Int {
        val low = id.lowercase()
        var score = 0
        if (low.contains("hydrogen")) score += 500
        if (low.contains("gemini-4") || low.contains("4.0") || low.contains("4-")) score += 450
        if (low.contains("gemini-3") || low.contains("3.")) score += 400
        if (low.contains("2.5")) score += 320
        if (low.contains("2.0")) score += 260
        if (low.contains("flash")) score += 40
        if (low.contains("pro")) score += 30
        if (low.contains("lite")) score += 10
        if (low.contains("exp") || low.contains("preview")) score += 15
        if (low.contains("gemma")) score -= 100
        if (low.contains("1.0") || low.contains("1.5")) score -= 40
        return score
    }

    suspend fun generateContent(
        apiKeyChars: CharArray,
        modelId: String,
        systemPrompt: String,
        userPrompt: String,
        imageBytes: ByteArray? = null,
        imageMimeType: String = "image/webp",
        extraImages: List<Pair<ByteArray, String>> = emptyList(),
        transportConfig: ByokManager.ProxyTransportConfig = ByokManager.ProxyTransportConfig()
    ): Result<GenerationResponse> = withContext(Dispatchers.IO) {
        val activeClient = buildClientForTransport(transportConfig)
        val keyCopy = apiKeyChars.clone()
        val keyString = String(keyCopy).trim()
        val startMs = System.currentTimeMillis()
        val cleanModel = modelId.removePrefix("models/").trim()
        if (cleanModel.isEmpty()) {
            Arrays.fill(keyCopy, '0')
            return@withContext Result.failure(IllegalArgumentException("Модель не выбрана"))
        }
        try {
            if (keyString.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("API-ключ пустой"))
            }

            val parts = JSONArray()
            if (imageBytes != null && imageBytes.isNotEmpty()) {
                val b64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                parts.put(
                    JSONObject().apply {
                        put(
                            "inline_data",
                            JSONObject().apply {
                                put("mime_type", imageMimeType)
                                put("data", b64)
                            }
                        )
                    }
                )
            }
            for ((bytes, mime) in extraImages) {
                if (bytes.isNotEmpty()) {
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    parts.put(
                        JSONObject().apply {
                            put(
                                "inline_data",
                                JSONObject().apply {
                                    put("mime_type", mime)
                                    put("data", b64)
                                }
                            )
                        }
                    )
                }
            }
            parts.put(JSONObject().apply { put("text", userPrompt) })

            val payload = JSONObject().apply {
                if (systemPrompt.isNotBlank()) {
                    put(
                        "system_instruction",
                        JSONObject().apply {
                            put("parts", JSONArray().put(JSONObject().apply { put("text", systemPrompt) }))
                        }
                    )
                }
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("parts", parts)
                        }
                    )
                )
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("temperature", 0.35)
                        put("maxOutputTokens", 1200)
                    }
                )
            }

            val req = Request.Builder()
                .url("$BASE_URL/models/$cleanModel:generateContent")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", ANONYMOUS_USER_AGENT)
                .addHeader("x-goog-api-key", keyString)
                .build()

            activeClient.newCall(req).execute().use { resp ->
                val latency = System.currentTimeMillis() - startMs
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errMsg = ByokManager.sanitizeForLog(extractApiError(bodyStr, resp.code), keyString)
                    ByokManager.recordHttpLog(
                        method = "POST",
                        endpoint = "/v1beta/models/$cleanModel:generateContent",
                        httpCode = resp.code,
                        latencyMs = latency,
                        detail = errMsg,
                        secretKey = keyString
                    )
                    ByokManager.recordStats(cleanModel, 0, 0, 0, latency, success = false)
                    return@withContext Result.failure(IllegalStateException(errMsg))
                }

                val root = JSONObject(bodyStr)
                val candidates = root.optJSONArray("candidates")
                val sb = StringBuilder()
                if (candidates != null && candidates.length() > 0) {
                    val c0 = candidates.getJSONObject(0)
                    val contentParts = c0.optJSONObject("content")?.optJSONArray("parts")
                    if (contentParts != null) {
                        for (i in 0 until contentParts.length()) {
                            val t = contentParts.getJSONObject(i).optString("text", "")
                            if (t.isNotBlank()) {
                                if (sb.isNotEmpty()) sb.append("\n")
                                sb.append(t.trim())
                            }
                        }
                    }
                    if (sb.isEmpty()) {
                        val finishReason = c0.optString("finishReason", "")
                        if (finishReason.isNotBlank()) {
                            sb.append("Модель остановила вывод ($finishReason).")
                        }
                    }
                }

                val usage = root.optJSONObject("usageMetadata")
                val promptTok = usage?.optInt("promptTokenCount", 0) ?: 0
                val outTok = usage?.optInt("candidatesTokenCount", 0) ?: 0
                val totalTok = usage?.optInt("totalTokenCount", promptTok + outTok) ?: (promptTok + outTok)

                val finalText = sb.toString().ifBlank { "Пустой ответ от модели ($cleanModel)." }
                ByokManager.recordHttpLog(
                    method = "POST",
                    endpoint = "/v1beta/models/$cleanModel:generateContent",
                    httpCode = resp.code,
                    latencyMs = latency,
                    detail = "tokens=$totalTok (in=$promptTok, out=$outTok), img=${imageBytes != null}",
                    secretKey = keyString
                )
                ByokManager.recordStats(cleanModel, promptTok, outTok, totalTok, latency, success = true)

                Result.success(
                    GenerationResponse(
                        text = finalText,
                        modelId = cleanModel,
                        promptTokens = promptTok,
                        outputTokens = outTok,
                        totalTokens = totalTok,
                        latencyMs = latency
                    )
                )
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startMs
            val mapped = ByokManager.sanitizeForLog(mapNetworkError(e), keyString)
            ByokManager.recordHttpLog(
                method = "POST",
                endpoint = "/v1beta/models/$cleanModel:generateContent",
                httpCode = -1,
                latencyMs = latency,
                detail = mapped,
                secretKey = keyString
            )
            ByokManager.recordStats(cleanModel, 0, 0, 0, latency, success = false)
            Result.failure(IllegalStateException(mapped))
        } finally {
            Arrays.fill(keyCopy, '0')
        }
    }

    private fun extractApiError(body: String, httpCode: Int): String {
        return try {
            val msg = JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
            when (httpCode) {
                400 -> "Ошибка запроса (400): $msg"
                401, 403 -> "Ключ не принят или нет прав ($httpCode): $msg"
                404 -> "Модель не найдена (404): $msg"
                429 -> "Лимит запросов исчерпан (429): $msg"
                else -> if (msg.isNotBlank()) "HTTP $httpCode: $msg" else "HTTP ошибка $httpCode"
            }
        } catch (_: Exception) {
            "HTTP ошибка $httpCode"
        }
    }

    fun isNetworkOnline(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    fun isQuotaOrNetworkFailure(message: String?): Boolean {
        val m = message.orEmpty().lowercase()
        return m.contains("429") ||
            m.contains("лимит") ||
            m.contains("quota") ||
            m.contains("нет сети") ||
            m.contains("dns") ||
            m.contains("таймаут") ||
            m.contains("timeout") ||
            m.contains("unable to resolve") ||
            m.contains("failed to connect") ||
            m.contains("socket")
    }

    private fun mapNetworkError(e: Exception): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("Permission denied", ignoreCase = true) ->
                "Нет разрешения INTERNET в манифесте или блокировка фаерволом"
            msg.contains("Unable to resolve host", ignoreCase = true) ->
                "Нет сети или DNS не отвечает (проверь VPN / NextDNS / интернет)"
            msg.contains("timeout", ignoreCase = true) ->
                "Таймаут ответа от Gemini API"
            else -> msg.ifBlank { e.javaClass.simpleName }
        }
    }
}
