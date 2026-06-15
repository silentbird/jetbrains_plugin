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
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompletion
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
import java.util.UUID
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

// Raw text-completion (/v1/completions) types, used for self-hosted NextEdit models
// (e.g. sweep-next-edit-v2-7B served by vLLM/sglang).
@Serializable
internal data class OpenAICompletionRequest(
    val model: String,
    val prompt: String,
    @SerialName("max_tokens")
    val maxTokens: Int = 1024,
    val temperature: Double = 0.0,
    val stop: List<String> = emptyList(),
    val stream: Boolean = false,
)

@Serializable
internal data class OpenAICompletionResponse(
    val choices: List<OpenAICompletionChoice> = emptyList(),
)

@Serializable
internal data class OpenAICompletionChoice(
    val text: String? = null,
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

        // Stop tokens for the NextEdit model (sweep-next-edit-v2-7B).
        private val STOP_TOKENS = listOf("<|file_sep|>", "<|endoftext|>")
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

            val built = buildNextEditPrompt(request)
            val requestBody =
                encodeString(
                    OpenAICompletionRequest(
                        model = model,
                        prompt = built.prompt,
                        maxTokens = 1024,
                        temperature = 0.0,
                        stop = STOP_TOKENS,
                    ),
                    OpenAICompletionRequest.serializer(),
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
            if (response.statusCode() !in 200..299) {
                logger.warn("Custom autocomplete provider returned HTTP ${response.statusCode()}: ${response.body()}")
                return@withContext null
            }

            val completionResponse = defaultJson.decodeFromString<OpenAICompletionResponse>(response.body())
            val completion = completionResponse.choices.firstNotNullOfOrNull { it.text } ?: return@withContext null

            buildNextEditResponse(request, built, completion)
        }

    private fun getCustomAutocompleteEndpoint(configuredUrl: String): String {
        val url = configuredUrl.trim().trimEnd('/')
        return when {
            url.endsWith("/completions") && !url.endsWith("/chat/completions") -> url
            url.endsWith("/v1") -> "$url/completions"
            else -> "$url/v1/completions"
        }
    }

    private data class BuiltNextEditPrompt(
        val prompt: String,
        val codeBlock: String,
        val blockStartIndex: Int,
        val prefill: String,
    )

    /**
     * Builds the NextEdit model prompt. Ported from sweepai/sweep-next-edit-v2-7B `inference.py`
     * (`build_prompt` + `compute_prefill`): a `<|file_sep|>`-delimited prompt with surrounding file
     * context, retrieval chunks, recent changes, the cursor code block (with a `<|cursor|>` marker),
     * and a prefill that seeds the `updated/` section.
     */
    private fun buildNextEditPrompt(request: NextEditAutocompleteRequest): BuiltNextEditPrompt {
        val fileContents = request.file_contents
        val cursorPosition = request.cursor_position.coerceIn(0, fileContents.length)
        val lines = splitKeepEnds(fileContents)

        // Find the line containing the cursor
        var pos = 0
        var cursorLine = if (lines.isEmpty()) 0 else lines.size - 1
        for (i in lines.indices) {
            if (pos + lines[i].length > cursorPosition) {
                cursorLine = i
                break
            }
            pos += lines[i].length
        }

        val numLinesBefore = 10
        val numLinesAfter = 10
        val blockStart = maxOf(0, cursorLine - numLinesBefore)
        val blockEnd = minOf(lines.size, cursorLine + numLinesAfter + 1)
        val codeBlock = lines.subList(blockStart, blockEnd).joinToString("")
        val blockStartIndex = lines.subList(0, blockStart).sumOf { it.length }
        val relativeCursor = (cursorPosition - blockStartIndex).coerceIn(0, codeBlock.length)

        val codeBlockWithCursor =
            codeBlock.substring(0, relativeCursor) + "<|cursor|>" + codeBlock.substring(relativeCursor)
        val prevSection = codeBlock
        val prefill = computePrefill(codeBlock, relativeCursor, request.changes_above_cursor)

        val contextStart = maxOf(0, cursorLine - 150)
        val contextEnd = minOf(lines.size, cursorLine + 150)
        val initialFile = lines.subList(contextStart, contextEnd).joinToString("")

        val retrievalResults =
            request.retrieval_chunks.joinToString("") { "\n<|file_sep|>${it.file_path}\n${it.content}\n" }

        val startLine = blockStart + 1
        val endLine = blockEnd
        val filePath = request.file_path

        // TODO(autocomplete #1): request.recent_changes is in the plugin's "File: path\n<diff>"
        // format, not the model's original:/updated: DIFF_FORMAT. See AUTOCOMPLETE_TODO.md.
        var formatted =
            "<|file_sep|>$filePath\n" +
                "$initialFile$retrievalResults\n" +
                "${request.recent_changes}\n" +
                "<|file_sep|>original/$filePath:$startLine:$endLine\n" +
                "$prevSection\n" +
                "<|file_sep|>current/$filePath:$startLine:$endLine\n" +
                "$codeBlockWithCursor\n" +
                "<|file_sep|>updated/$filePath:$startLine:$endLine\n" +
                prefill

        if (request.file_chunks.isNotEmpty()) {
            formatted =
                request.file_chunks.joinToString("") { "<|file_sep|>${it.file_path}\n${it.content}\n" } + formatted
        }

        return BuiltNextEditPrompt(formatted, codeBlock, blockStartIndex, prefill)
    }

    /**
     * Ported from `inference.py` `compute_prefill`. Seeds the `updated/` section so the model only
     * generates from the edit point onward.
     */
    private fun computePrefill(
        codeBlock: String,
        relativeCursor: Int,
        changesAboveCursor: Boolean,
    ): String =
        if (changesAboveCursor) {
            // Insertion mode: prefill only the first line + trailing blank lines.
            val prefilledLines = splitKeepEnds(codeBlock.substring(0, relativeCursor))
            var beforeSplit = prefilledLines.take(1).joinToString("")
            val afterSplit = prefilledLines.drop(1).joinToString("")
            for (ch in afterSplit) {
                if (ch == '\n') beforeSplit += "\n" else break
            }
            beforeSplit
        } else {
            // Default mode: prefill everything up to the cursor's line.
            val prefixBeforeCursor = codeBlock.substring(0, relativeCursor)
            if (!prefixBeforeCursor.contains('\n')) {
                ""
            } else {
                codeBlock.substring(0, prefixBeforeCursor.lastIndexOf('\n') + 1)
            }
        }

    /** Splits text into lines keeping the line terminators (like Python's splitlines(keepends=True)). */
    private fun splitKeepEnds(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (ch == '\n') {
                result.add(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        return result
    }

    /**
     * Turns the model's predicted `updated/` code block into a [NextEditAutocompleteResponse] that
     * replaces the original cursor code block. Indices are returned as code-point (Python-style)
     * offsets because the caller applies `convertPythonToKotlinIndex` (adjustIndices) before use.
     */
    private fun buildNextEditResponse(
        request: NextEditAutocompleteRequest,
        built: BuiltNextEditPrompt,
        rawCompletion: String,
    ): NextEditAutocompleteResponse? {
        // Strip any stop tokens the server didn't already remove.
        var completion = rawCompletion
        for (stop in STOP_TOKENS) {
            val idx = completion.indexOf(stop)
            if (idx >= 0) completion = completion.substring(0, idx)
        }

        val updatedBlock = built.prefill + completion
        if (updatedBlock == built.codeBlock || updatedBlock.trim() == built.codeBlock.trim()) {
            return null // no-op prediction
        }

        // TODO(autocomplete #2): port inference.py is_pure_insertion_above_cursor to reject
        // low-value "insert above cursor only" predictions. See AUTOCOMPLETE_TODO.md.

        val fileContents = request.file_contents
        val codeBlock = built.codeBlock

        // Reduce the whole-block replacement to the minimal changed span by trimming common leading
        // and trailing *whole lines*. This keeps the region line-aligned and moves start_index next
        // to the actual change (so the renderer treats it as a near-cursor inline edit, not a distant
        // "jump"). Char-level trimming would split mid-line and make a clean line insertion look like
        // a replacement, which forces the popup instead of inline ghost text.
        val oldLines = splitKeepEnds(codeBlock)
        val newLines = splitKeepEnds(updatedBlock)
        var lead = 0
        while (lead < minOf(oldLines.size, newLines.size) && oldLines[lead] == newLines[lead]) lead++
        var trail = 0
        while (trail < minOf(oldLines.size, newLines.size) - lead &&
            oldLines[oldLines.size - 1 - trail] == newLines[newLines.size - 1 - trail]
        ) {
            trail++
        }
        val prefixLen = oldLines.take(lead).sumOf { it.length }
        val suffixLen = oldLines.takeLast(trail).sumOf { it.length }

        val startKotlin = (built.blockStartIndex + prefixLen).coerceIn(0, fileContents.length)
        val endKotlin = (built.blockStartIndex + codeBlock.length - suffixLen).coerceIn(startKotlin, fileContents.length)
        val replacement =
            updatedBlock.substring(
                prefixLen.coerceAtMost(updatedBlock.length),
                (updatedBlock.length - suffixLen).coerceIn(prefixLen.coerceAtMost(updatedBlock.length), updatedBlock.length),
            )

        // Express offsets as code-point counts so adjustIndices() restores the correct UTF-16 offsets.
        val startCp = fileContents.codePointCount(0, startKotlin)
        val endCp = fileContents.codePointCount(0, endKotlin)
        // TODO(autocomplete #3): /v1/completions returns no confidence; hardcoded. See AUTOCOMPLETE_TODO.md.
        val confidence = 1.0f
        val autocompleteId = UUID.randomUUID().toString()

        return NextEditAutocompleteResponse(
            start_index = startCp,
            end_index = endCp,
            completion = replacement,
            confidence = confidence,
            autocomplete_id = autocompleteId,
            completions =
                listOf(
                    NextEditAutocompletion(
                        start_index = startCp,
                        end_index = endCp,
                        completion = replacement,
                        confidence = confidence,
                        autocomplete_id = autocompleteId,
                    ),
                ),
        )
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
