package dev.sweep.assistant.utils

import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.http.HttpResponse

val defaultJson =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

private fun getMatchingBracket(char: Char): Char? =
    when (char) {
        '[' -> ']'
        '{' -> '}'
        '(' -> ')'
        else -> null
    }

/**
 * Scans a streaming buffer for complete top-level JSON values and returns them along with the
 * index up to which the buffer was consumed. Used to decode the JSON stream returned by the
 * autocomplete provider.
 */
fun getJSONPrefix(buffer: String): Pair<List<JsonElement>, Int> {
    if (buffer.startsWith("null")) {
        // for heartbeat messages
        return Pair(emptyList(), "null".length)
    }

    val stack = mutableListOf<Char>()
    var currentIndex = 0
    val results = mutableListOf<JsonElement>()
    var inString = false
    var escapeNext = false

    for (i in buffer.indices) {
        val char = buffer[i]

        if (escapeNext) {
            escapeNext = false
            continue
        }

        if (char == '\\') {
            escapeNext = true
            continue
        }

        if (char == '"') {
            inString = !inString
        }

        if (!inString) {
            if (char == '[' || char == '{' || char == '(') {
                stack.add(char)
            } else if (stack.lastOrNull()?.let { getMatchingBracket(it) } == char) {
                stack.removeAt(stack.lastIndex)
                if (stack.isEmpty()) {
                    try {
                        val jsonElement = Json.parseToJsonElement(buffer.substring(currentIndex, i + 1))
                        results.add(jsonElement)
                        currentIndex = i + 1
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
        }
    }

    return Pair(results, currentIndex)
}

fun <T> encodeString(
    request: T,
    serializer: SerializationStrategy<T>,
) = defaultJson.encodeToString(
    serializer,
    request,
)

inline fun <reified T> HttpURLConnection.sendRequest(
    request: T,
    serializer: SerializationStrategy<T>,
) = apply {
    val postData = encodeString(request, serializer)

    outputStream.use { os ->
        os.write(postData.toByteArray())
        os.flush()
    }
}

inline fun <reified T> HttpURLConnection.streamJson() =
    flow {
        var currentText = ""

        try {
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val buffer = CharArray(1024)
                var bytesRead: Int
                while (reader.read(buffer).also { bytesRead = it } != -1) {
                    currentText += String(buffer, 0, bytesRead)
                    val (jsonElements, currentIndex) = getJSONPrefix(currentText)
                    currentText = currentText.drop(currentIndex)

                    for (jsonElement in jsonElements) {
                        try {
                            val output = defaultJson.decodeFromString<T>(jsonElement.toString())
                            emit(output)
                        } catch (e: Exception) {
                            println("Error decoding JSON ${e.message}")
                            continue
                        }
                    }
                }
            }
        } catch (e: java.io.IOException) {
            // Handle stream closure gracefully - this can happen when:
            // 1. Server closes the connection (RST_STREAM)
            // 2. Network timeout occurs
            // 3. Request is cancelled
            // If we've already emitted some data, this is not necessarily an error
            if (e.message?.contains("closed") == true || e.message?.contains("RST_STREAM") == true) {
                // Stream was closed, but we may have already received valid data
                // Just exit gracefully
            } else {
                // Re-throw other IOExceptions
                throw e
            }
        }
    }

inline fun <reified T> HttpResponse<InputStream>.streamJson() =
    flow {
        var currentText = ""

        try {
            BufferedReader(InputStreamReader(body())).use { reader ->
                val buffer = CharArray(1024)
                var bytesRead: Int
                while (reader.read(buffer).also { bytesRead = it } != -1) {
                    currentText += String(buffer, 0, bytesRead)
                    val (jsonElements, currentIndex) = getJSONPrefix(currentText)
                    currentText = currentText.drop(currentIndex)

                    for (jsonElement in jsonElements) {
                        try {
                            val output = defaultJson.decodeFromString<T>(jsonElement.toString())
                            emit(output)
                        } catch (e: Exception) {
                            println("Error decoding JSON ${e.message}")
                            continue
                        }
                    }
                }
            }
        } catch (e: java.io.IOException) {
            // Handle stream closure gracefully - this can happen when:
            // 1. Server closes the connection (RST_STREAM)
            // 2. Network timeout occurs
            // 3. Request is cancelled
            // If we've already emitted some data, this is not necessarily an error
            if (e.message?.contains("closed") == true || e.message?.contains("RST_STREAM") == true) {
                // Stream was closed, but we may have already received valid data
                // Just exit gracefully
            } else {
                // Re-throw other IOExceptions
                throw e
            }
        }
    }

fun <T> HttpResponse<T>.raiseForStatus(): HttpResponse<T> {
    if (statusCode() !in 200..399) {
        throw java.io.IOException("HTTP ${statusCode()}")
    }
    return this
}
