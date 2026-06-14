package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.PermanentInstallationID
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompleteRequest
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompleteResponse
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.settings.SweepSettingsParser
import dev.sweep.assistant.utils.CompressionUtils
import dev.sweep.assistant.utils.encodeString
import dev.sweep.assistant.utils.getCurrentSweepPluginVersion
import dev.sweep.assistant.utils.getDebugInfo
import dev.sweep.assistant.utils.defaultJson
import dev.sweep.assistant.utils.raiseForStatus
import dev.sweep.assistant.utils.streamJson
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.future.await
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

@Serializable
internal data class OpenAIChatMessage(
    val role: String,
    val content: String,
)

@Serializable
internal data class OpenAIChatCompletionRequest(
    val model: String,
    val messages: List<OpenAIChatMessage>,
    // Newer OpenAI models (gpt-5 / o-series) reject `max_tokens` and any non-default `temperature`.
    // We therefore send `max_completion_tokens` and omit `temperature` entirely (server default).
    @SerialName("max_completion_tokens")
    val maxCompletionTokens: Int = 256,
    val stream: Boolean = false,
)

@Serializable
internal data class OpenAIChatCompletionResponse(
    val choices: List<OpenAIChoice> = emptyList(),
)

@Serializable
internal data class OpenAIChoice(
    val message: OpenAIChatResponseMessage? = null,
    val text: String? = null,
)

@Serializable
internal data class OpenAIChatResponseMessage(
    val content: String? = null,
)

/**
 * Service that periodically resolves the IP address of autocomplete.sweep.dev
 * to keep DNS cache warm while using HTTPS with the domain name directly.
 */
@Service(Service.Level.PROJECT)
class AutocompleteIpResolverService(
    private val project: Project,
) : Disposable {
    companion object {
        private val logger = Logger.getInstance(AutocompleteIpResolverService::class.java)

        fun getInstance(project: Project): AutocompleteIpResolverService = project.getService(AutocompleteIpResolverService::class.java)

        private const val HOSTNAME = "autocomplete.sweep.dev"
        private const val RESOLUTION_INTERVAL_MS = 15_000L
        private const val HEALTH_CHECK_INTERVAL_MS = 25_000L // Just under 30 seconds
        private const val READ_TIMEOUT_MS = 10_000L
        private const val USER_ACTIVITY_TIMEOUT_MS = 15 * 60 * 1000L // 15 minutes
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lastLatencyMs = AtomicLong(-1L) // -1 indicates no measurement yet
    private val lastUserActionTimestamp = AtomicLong(System.currentTimeMillis()) // Initialize with current time
    private var resolutionJob: Job? = null
    private var healthCheckJob: Job? = null

    /**
     * Checks if the user is pointed to the cloud version of the plugin.
     * Returns true if either:
     * 1. The user is on the cloud environment (plugin version), OR
     * 2. Their backend URL is pointed to https://backend.app.sweep.dev
     */
    private fun isPointedToCloud(): Boolean =
        SweepSettingsParser.isCloudEnvironment() ||
            SweepSettings.getInstance().baseUrl == "https://backend.app.sweep.dev"

    // HTTP client with connection pooling and keep-alive
    private val httpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build()

    /**
     * Gets the shared HttpClient instance for connection pooling.
     * This allows other services to use the same connection pool.
     */
    fun getSharedHttpClient(): HttpClient = httpClient

    /**
     * Executes a next edit autocomplete request.
     * This centralizes the entire HTTP request flow in the DNS resolver service.
     */
    @RequiresBackgroundThread
    suspend fun fetchNextEditAutocomplete(request: NextEditAutocompleteRequest): NextEditAutocompleteResponse? {
        return try {
            val isLocalMode = SweepConfig.getInstance(project).isAutocompleteLocalMode()

            when {
                !isLocalMode && SweepSettings.getInstance().hasCustomAutocompleteProvider ->
                    fetchCustomNextEditAutocomplete(request)

                !isLocalMode -> {
                    // No fallback to Sweep's hosted autocomplete: cloud autocomplete requires a
                    // user-configured OpenAI-compatible endpoint (enable local mode otherwise).
                    logger.debug("No autocomplete backend configured (enable local mode or set a custom endpoint)")
                    null
                }

                else -> {
                    LocalAutocompleteServerManager.getInstance().ensureServerRunning()
                    val postData = encodeString(request, NextEditAutocompleteRequest.serializer())
                val postDataBytes = postData.toByteArray(Charsets.UTF_8)

                // Try to compress the request data
                val (finalData, useCompression) =
                    if (CompressionUtils.isBrotliAvailable()) {
                        val compressedData = CompressionUtils.compress(postDataBytes, CompressionUtils.CompressionType.BROTLI)
                        if (compressedData.size < postDataBytes.size) {
                            val compressionRatio = CompressionUtils.calculateCompressionRatio(postDataBytes.size, compressedData.size)
                            logger.info(
                                "Request compressed: ${postDataBytes.size} -> ${compressedData.size} bytes (${String.format(
                                    "%.1f",
                                    compressionRatio,
                                )}% reduction)",
                            )
                            Pair(compressedData, true)
                        } else {
                            logger.info("Compression not beneficial, sending uncompressed")
                            Pair(postDataBytes, false)
                        }
                    } else {
                        logger.info("Brotli not available, sending uncompressed")
                        Pair(postDataBytes, false)
                    }

                val authorization =
                    if (SweepSettings.getInstance().githubToken.isBlank()) {
                        "Bearer device_id_${PermanentInstallationID.get()}"
                    } else {
                        "Bearer ${SweepSettings.getInstance().githubToken}"
                    }

                val httpRequestBuilder =
                    HttpRequest
                        .newBuilder()
                        .uri(URI.create("${getBaseUrl()}/backend/next_edit_autocomplete"))
                        .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                        .header("Content-Type", "application/json")
                        .header("Authorization", authorization)
                        .header("X-Plugin-Version", getCurrentSweepPluginVersion() ?: "unknown")
                        .header("X-IDE-Name", ApplicationInfo.getInstance().fullApplicationName)
                        .header("X-IDE-Version", ApplicationInfo.getInstance().fullVersion)
                        .header("X-Debug-Info", getDebugInfo())

                if (useCompression) {
                    httpRequestBuilder.header("Content-Encoding", CompressionUtils.CompressionType.BROTLI.encoding)
                }

                val httpRequest = httpRequestBuilder.POST(HttpRequest.BodyPublishers.ofByteArray(finalData)).build()

                val response =
                    httpClient
                        .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                        .await()
                        .raiseForStatus()

                var result: NextEditAutocompleteResponse? = null
                val isLocalMode = SweepConfig.getInstance(project).isAutocompleteLocalMode()

                if (isLocalMode) {
                    // For local mode, read line-by-line to handle server crashes mid-stream gracefully
                    try {
                        response.body().bufferedReader().use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val l = line ?: continue
                                if (l.isBlank()) continue
                                try {
                                    val jsonElement = defaultJson.parseToJsonElement(l)
                                    if (jsonElement is JsonObject && jsonElement.containsKey("status")) {
                                        val status = jsonElement["status"]?.jsonPrimitive?.contentOrNull
                                        if (status == "error") {
                                            val errorMsg = jsonElement["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                                            logger.warn("Local autocomplete server error: $errorMsg")
                                            continue
                                        }
                                    }
                                    result = defaultJson.decodeFromString(NextEditAutocompleteResponse.serializer(), l)
                                } catch (e: Exception) {
                                    logger.warn("Error parsing local server response: ${e.message}")
                                }
                            }
                        }
                    } catch (e: java.io.IOException) {
                        // Server closed the stream (crash, broken pipe, etc.)
                        // Process whatever we got before the closure
                        logger.info("Local server stream closed: ${e.message}")
                    }

                    if (result != null) {
                        LocalAutocompleteServerManager.getInstance().reportSuccess()
                    } else {
                        LocalAutocompleteServerManager.getInstance().reportFailure()
                    }
                } else {
                    response.streamJson<NextEditAutocompleteResponse>().collect {
                        result = it
                    }
                }

                    result
                }
            }
        } catch (e: Exception) {
            logger.warn("Error fetching next edit autocomplete: ${e.message}")
            if (SweepConfig.getInstance(project).isAutocompleteLocalMode()) {
                LocalAutocompleteServerManager.getInstance().reportFailure()
            }
            throw e
        }
    }

    private suspend fun fetchCustomNextEditAutocomplete(request: NextEditAutocompleteRequest): NextEditAutocompleteResponse? =
        withContext(Dispatchers.IO) {
            val settings = SweepSettings.getInstance()
            val model = settings.customAutocompleteModel
            if (model.isBlank()) {
                logger.warn("Custom autocomplete model is blank")
                return@withContext null
            }

            val prompt = buildCustomAutocompletePrompt(request)
            val requestBody =
                encodeString(
                    OpenAIChatCompletionRequest(
                        model = model,
                        messages =
                            listOf(
                                OpenAIChatMessage(
                                    role = "system",
                                    content =
                                        "You are a code autocomplete engine. Return only a JSON object with a completion field.",
                                ),
                                OpenAIChatMessage(role = "user", content = prompt),
                            ),
                    ),
                    OpenAIChatCompletionRequest.serializer(),
                )

            val httpRequest =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create(getCustomAutocompleteEndpoint(settings.customAutocompleteUrl)))
                    .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer ${settings.customAutocompleteApiKey}")
                    .header("X-Plugin-Version", getCurrentSweepPluginVersion() ?: "unknown")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()

            val response =
                httpClient
                    .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
                    .await()
                    .raiseForStatus()

            val body = response.body()
            val chatResponse = defaultJson.decodeFromString<OpenAIChatCompletionResponse>(body)
            val content =
                chatResponse.choices.firstNotNullOfOrNull { choice ->
                    choice.message?.content ?: choice.text
                } ?: return@withContext null

            parseCustomAutocompleteResponse(request, content)
        }

    private fun getCustomAutocompleteEndpoint(configuredUrl: String): String {
        val url = configuredUrl.trim().trimEnd('/')
        return when {
            url.endsWith("/chat/completions") -> url
            url.endsWith("/v1") -> "$url/chat/completions"
            else -> "$url/v1/chat/completions"
        }
    }

    private fun buildCustomAutocompletePrompt(request: NextEditAutocompleteRequest): String {
        val cursor = request.cursor_position.coerceIn(0, request.file_contents.length)
        val prefix = request.file_contents.substring(0, cursor).takeLast(12_000)
        val suffix = request.file_contents.substring(cursor).take(4_000)
        val recentChanges = request.recent_changes_high_res.ifBlank { request.recent_changes }.takeLast(4_000)
        val retrievalContext =
            (request.file_chunks + request.retrieval_chunks)
                .take(8)
                .joinToString("\n\n") { chunk ->
                    "File: ${chunk.file_path}:${chunk.start_line}-${chunk.end_line}\n${chunk.content.take(2_000)}"
                }

        return """
            Complete the code at the cursor.
            Return exactly one JSON object:
            {"completion":"text to insert at the cursor","confidence":0.0}

            Requirements:
            - The completion must be only the inserted text, not the whole file.
            - Do not include markdown fences or explanations.
            - Prefer a short, high-confidence completion.
            - Use empty string if no useful completion is available.

            Current file: ${request.file_path}
            Cursor offset: $cursor

            Text before cursor:
            $prefix

            Text after cursor:
            $suffix

            Recent user changes:
            $recentChanges

            Related context:
            $retrievalContext
        """.trimIndent()
    }

    private fun parseCustomAutocompleteResponse(
        request: NextEditAutocompleteRequest,
        content: String,
    ): NextEditAutocompleteResponse? {
        val cleaned = stripMarkdownFence(content)
        val parsedObject =
            runCatching {
                defaultJson.parseToJsonElement(cleaned.trim()) as? JsonObject
            }.getOrNull()

        val completion =
            parsedObject
                ?.get("completion")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: cleaned.trim()

        if (completion.isEmpty()) return null

        val startIndex =
            parsedObject
                ?.get("start_index")
                ?.jsonPrimitive
                ?.intOrNull
                ?: request.cursor_position
        val endIndex =
            parsedObject
                ?.get("end_index")
                ?.jsonPrimitive
                ?.intOrNull
                ?: request.cursor_position
        val confidence =
            parsedObject
                ?.get("confidence")
                ?.jsonPrimitive
                ?.floatOrNull
                ?: 0.7f
        val autocompleteId =
            parsedObject
                ?.get("autocomplete_id")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: "custom-${System.currentTimeMillis()}"

        return NextEditAutocompleteResponse(
            start_index = startIndex,
            end_index = endIndex,
            completion = completion,
            confidence = confidence,
            autocomplete_id = autocompleteId,
            completions =
                listOf(
                    dev.sweep.assistant.autocomplete.edit.NextEditAutocompletion(
                        start_index = startIndex,
                        end_index = endIndex,
                        completion = completion,
                        confidence = confidence,
                        autocomplete_id = autocompleteId,
                    ),
                ),
        )
    }

    private fun stripMarkdownFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return text

        val lines = trimmed.lines().toMutableList()
        if (lines.isNotEmpty() && lines.first().trim().startsWith("```")) {
            lines.removeAt(0)
        }
        if (lines.isNotEmpty() && lines.last().trim().startsWith("```")) {
            lines.removeAt(lines.lastIndex)
        }
        return lines.joinToString("\n")
    }

    init {
        // No background DNS resolution or health-checks against Sweep's hosted service:
        // autocomplete is served only by the local server or a user-configured endpoint.
    }

    /**
     * Base URL for the NextEdit-protocol backend. Only the local autocomplete server speaks this
     * protocol now (the custom OpenAI-compatible endpoint is handled separately), so this always
     * points at the local server.
     */
    fun getBaseUrl(): String = LocalAutocompleteServerManager.getInstance().getServerUrl()

    /**
     * Gets the last measured latency in milliseconds.
     * Returns -1 if no measurement has been taken yet.
     */
    fun getLastLatencyMs(): Long = lastLatencyMs.get()

    /**
     * Updates the timestamp of the last user action.
     * Call this whenever the user performs any action (typing, clicking, etc.).
     */
    fun updateLastUserActionTimestamp() {
        lastUserActionTimestamp.set(System.currentTimeMillis())
    }

    /**
     * Checks if there was user activity within the last 10 minutes.
     */
    private fun hasRecentUserActivity(): Boolean {
        val currentTime = System.currentTimeMillis()
        val lastActivity = lastUserActionTimestamp.get()
        return (currentTime - lastActivity) <= USER_ACTIVITY_TIMEOUT_MS
    }

    private fun startPeriodicResolution() {
        resolutionJob =
            scope.launch {
                // Initial resolution
                resolveIpAddress()

                // Periodic resolution every 15 seconds, but only if user was active in last 10 minutes
                while (isActive) {
                    delay(RESOLUTION_INTERVAL_MS)
                    if (hasRecentUserActivity()) {
                        resolveIpAddress()
                    }
                }
            }
    }

    private fun startPeriodicHealthCheck() {
        healthCheckJob =
            scope.launch {
                // Initial health check
                performHealthCheck()

                // Periodic health check every 25 seconds, but only if user was active in last 10 minutes
                while (isActive) {
                    delay(HEALTH_CHECK_INTERVAL_MS)
                    if (hasRecentUserActivity()) {
                        performHealthCheck()
                    }
                }
            }
    }

    private suspend fun resolveIpAddress() {
        if (SweepConfig.getInstance(project).isAutocompleteLocalMode()) return
        if (!isPointedToCloud()) return
        try {
            withContext(Dispatchers.IO) {
                // Just resolve the hostname to keep DNS cache warm
                // We don't use the IP addresses, just let the OS cache them
                InetAddress.getAllByName(HOSTNAME)
            }
        } catch (e: Exception) {
            logger.warn("Failed to resolve $HOSTNAME: ${e.message}")
        }
    }

    private suspend fun performHealthCheck() {
        if (SweepConfig.getInstance(project).isAutocompleteLocalMode()) return
        if (!isPointedToCloud()) return
        try {
            withContext(Dispatchers.IO) {
                val baseUrl = getBaseUrl()
                val startTime = System.currentTimeMillis()

                val request =
                    HttpRequest
                        .newBuilder()
                        .uri(URI.create(baseUrl))
                        .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                        .GET()
                        .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
                val endTime = System.currentTimeMillis()
                val latency = endTime - startTime

                if (response.statusCode() in 200..299) {
                    lastLatencyMs.set(latency)
//                    println("AutocompleteIpResolverService: Health check to $baseUrl successful, latency: ${latency}ms")
                } else {
                    logger.warn("Health check to $baseUrl failed with response code: ${response.statusCode()}")
                }
            }
        } catch (e: Exception) {
            logger.warn("Health check failed: ${e.message}")
            // Keep the last latency value on failure
        }
    }

    override fun dispose() {
        resolutionJob?.cancel()
        healthCheckJob?.cancel()
        scope.cancel()
    }
}
