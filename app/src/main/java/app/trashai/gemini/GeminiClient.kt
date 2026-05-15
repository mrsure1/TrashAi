package app.trashai.gemini

import android.util.Base64
import android.util.Log
import app.trashai.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Wraps Gemini's generateContent endpoint with vision input and grounds the
 * answer to a small Korean keyword list our DB can search against.
 *
 * Returns [GeminiResult] so callers can surface real failure reasons (HTTP
 * status, parse error, empty model output, …) instead of silently falling back.
 */
class GeminiClient(
    private val apiKey: String = BuildConfig.GEMINI_API_KEY,
    private val model: String = "gemini-2.5-flash",
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean get() = apiKey.isNotBlank() && apiKey != "PASTE_YOUR_GEMINI_API_KEY_HERE"

    /** Quick liveness check — sends a 1-token text request to confirm the key works. */
    suspend fun ping(): GeminiResult<String> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext GeminiResult.NotConfigured
        val body = """{"contents":[{"parts":[{"text":"hi"}]}]}""".toRequestBody("application/json".toMediaType())
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        runCatching {
            http.newCall(Request.Builder().url(url).post(body).build()).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (resp.isSuccessful) GeminiResult.Ok("HTTP ${resp.code} (${txt.length} bytes)")
                else GeminiResult.HttpError(resp.code, txt.take(400))
            }
        }.getOrElse { GeminiResult.NetworkError(it.message ?: it::class.java.simpleName) }
    }

    /**
     * Extract Korean trash keywords from a free-text user description.
     */
    suspend fun extractKeywordsFromText(userText: String): GeminiResult<List<String>> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext GeminiResult.NotConfigured
        if (userText.isBlank()) return@withContext GeminiResult.Ok(emptyList())
        val safeText = userText.replace("\\", "\\\\").replace("\"", "\\\"")
        val instruction = "당신은 한국 분리수거 도우미다. 사용자가 묘사한 쓰레기를 한국 지자체 품목사전(wasteguide.or.kr)에서 찾을 수 있는 한국어 키워드 1~5개로 정제하라. 매우 일반적인 카테고리(플라스틱/종이/유리/캔/비닐/음식물)도 후순위로 포함하라. 오직 다음 JSON 형식으로만 답하라: {\\\"keywords\\\":[\\\"...\\\",\\\"...\\\"]}. 사용자 묘사: \\\"$safeText\\\""
        val body = """
            {
              "contents":[{"parts":[{"text":"$instruction"}]}],
              "generationConfig":{
                "temperature":0.1,
                "responseMimeType":"application/json"
              }
            }
        """.trimIndent().toRequestBody("application/json".toMediaType())
        callAndParseKeywords(body)
    }

    /**
     * Identify the trash item shown in [jpegBytes] and return Korean keyword
     * candidates suitable for app_search_keyword lookup.
     */
    suspend fun classifyTrashKeywords(jpegBytes: ByteArray): GeminiResult<List<String>> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext GeminiResult.NotConfigured
        if (jpegBytes.isEmpty()) return@withContext GeminiResult.InvalidInput("이미지가 비어있습니다")
        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        Log.i(TAG, "classifyTrashKeywords: bytes=${jpegBytes.size} b64=${b64.length}")
        val instruction = "당신은 한국 분리수거 도우미다. 사진의 핵심 쓰레기/재활용 품목 1개를 식별하고, 한국 지자체 품목사전(wasteguide.or.kr)에서 흔히 쓰일 한국어 키워드 후보를 1~5개 반환하라. 예: '투명페트병','페트병','종이팩','우유팩','건전지','종이상자'. 오직 다음 JSON 형식으로만 답하라: {\\\"keywords\\\":[\\\"...\\\",\\\"...\\\"]}. 확신 없으면 더 일반적인 상위 카테고리(플라스틱/종이/유리/캔/비닐/음식물)를 포함하라."
        val body = """
            {
              "contents":[{"parts":[
                {"text":"$instruction"},
                {"inlineData":{"mimeType":"image/jpeg","data":"$b64"}}
              ]}],
              "generationConfig":{
                "temperature":0.1,
                "responseMimeType":"application/json"
              }
            }
        """.trimIndent().toRequestBody("application/json".toMediaType())
        callAndParseKeywords(body)
    }

    private fun callAndParseKeywords(body: okhttp3.RequestBody): GeminiResult<List<String>> {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        return runCatching {
            http.newCall(Request.Builder().url(url).post(body).build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Gemini HTTP ${resp.code}: ${text.take(300)}")
                    return@use GeminiResult.HttpError(resp.code, text.take(400))
                }
                val keywords = parseKeywordsFromGeminiResponse(text)
                if (keywords == null) GeminiResult.ParseError(text.take(400))
                else GeminiResult.Ok(keywords)
            }
        }.getOrElse {
            Log.w(TAG, "Gemini network/exception: ${it.message}")
            GeminiResult.NetworkError(it.message ?: it::class.java.simpleName)
        }
    }

    /** @return null on parse failure, empty list if model returned nothing, or the keywords. */
    private fun parseKeywordsFromGeminiResponse(raw: String): List<String>? {
        val outer = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        val inner = runCatching {
            outer.asObject()["candidates"]?.asArray()
                ?.firstOrNull()?.asObject()
                ?.get("content")?.asObject()
                ?.get("parts")?.asArray()
                ?.firstOrNull()?.asObject()
                ?.get("text")?.asPrimitive()?.content
        }.getOrNull() ?: return null
        val parsed = runCatching { json.decodeFromString<KeywordResponse>(inner) }.getOrNull()
            ?: return null
        return parsed.keywords.filter { it.isNotBlank() }.take(8)
    }

    @Serializable
    private data class KeywordResponse(val keywords: List<String> = emptyList())

    private companion object { const val TAG = "GeminiClient" }
}

sealed interface GeminiResult<out T> {
    data class Ok<T>(val value: T) : GeminiResult<T>
    data object NotConfigured : GeminiResult<Nothing>
    data class InvalidInput(val detail: String) : GeminiResult<Nothing>
    data class HttpError(val code: Int, val body: String) : GeminiResult<Nothing>
    data class ParseError(val rawSnippet: String) : GeminiResult<Nothing>
    data class NetworkError(val detail: String) : GeminiResult<Nothing>
}

private fun kotlinx.serialization.json.JsonElement.asObject() =
    this as kotlinx.serialization.json.JsonObject

private fun kotlinx.serialization.json.JsonElement.asArray() =
    this as kotlinx.serialization.json.JsonArray

private fun kotlinx.serialization.json.JsonElement.asPrimitive() =
    this as kotlinx.serialization.json.JsonPrimitive
