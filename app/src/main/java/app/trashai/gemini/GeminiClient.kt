package app.trashai.gemini

import android.util.Base64
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
 * Minimal REST client for Gemini's generateContent endpoint with vision input.
 * No SDK — keeps APK small and dep surface tiny.
 *
 * Returns a structured JSON list of candidate Korean keywords for the trash item
 * shown in the image. The mobile client THEN looks those keywords up in the
 * local DB; we never render Gemini's free text directly (architecture §4.2).
 */
class GeminiClient(
    private val apiKey: String = BuildConfig.GEMINI_API_KEY,
    private val model: String = "gemini-1.5-flash-latest",
) {
    private val http = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean get() = apiKey.isNotBlank() && apiKey != "PASTE_YOUR_GEMINI_API_KEY_HERE"

    /**
     * Returns a list of Korean keyword strings (e.g. "페트병", "투명페트병") best
     * matching the photographed item. Empty on any error / not configured.
     */
    /**
     * Same JSON contract as image flow but driven by the user's free-form
     * Korean description. Used by the conversational fallback when the camera
     * can't identify the object.
     */
    suspend fun extractKeywordsFromText(userText: String): List<String> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext emptyList()
        if (userText.isBlank()) return@withContext emptyList()
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
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val req = Request.Builder().url(url).post(body).build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                parseKeywordsFromGeminiResponse(resp.body?.string().orEmpty())
            }
        }.getOrElse { emptyList() }
    }

    suspend fun classifyTrashKeywords(jpegBytes: ByteArray): List<String> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext emptyList()
        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val body = """
            {
              "contents":[{"parts":[
                {"text": $promptJsonString },
                {"inlineData":{"mimeType":"image/jpeg","data":"$b64"}}
              ]}],
              "generationConfig":{
                "temperature":0.1,
                "responseMimeType":"application/json"
              }
            }
        """.trimIndent().toRequestBody("application/json".toMediaType())

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val req = Request.Builder().url(url).post(body).build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val text = resp.body?.string().orEmpty()
                parseKeywordsFromGeminiResponse(text)
            }
        }.getOrElse { emptyList() }
    }

    private fun parseKeywordsFromGeminiResponse(raw: String): List<String> {
        val outer = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        val inner = runCatching {
            outer.asObject()["candidates"]?.asArray()
                ?.firstOrNull()?.asObject()
                ?.get("content")?.asObject()
                ?.get("parts")?.asArray()
                ?.firstOrNull()?.asObject()
                ?.get("text")?.asPrimitive()?.content
        }.getOrNull() ?: return emptyList()
        // model is asked for {"keywords":["..."]}
        val parsed = runCatching { json.decodeFromString<KeywordResponse>(inner) }.getOrNull()
        return parsed?.keywords?.filter { it.isNotBlank() }?.take(8).orEmpty()
    }

    @Serializable
    private data class KeywordResponse(val keywords: List<String> = emptyList())

    private companion object {
        // Embedded as a JSON string literal (escaped quotes baked in).
        const val promptJsonString = "\"" +
            "당신은 한국 분리수거 도우미다. 사진의 핵심 쓰레기/재활용 품목 1개를 식별하고, " +
            "한국 지자체 품목사전(wasteguide.or.kr)에서 흔히 쓰일 한국어 키워드 후보를 1~5개 반환하라. " +
            "예: '투명페트병','페트병','종이팩','우유팩','건전지','종이상자'. " +
            "오직 다음 JSON 형식으로만 답하라: {\\\"keywords\\\":[\\\"...\\\",\\\"...\\\"]}. " +
            "확신 없으면 더 일반적인 상위 카테고리(플라스틱/종이/유리/캔/비닐/음식물)를 포함하라." +
            "\""
    }
}

// --- tiny JsonElement helpers (kotlinx.serialization.json built-ins exist as
//     `jsonObject`/`jsonArray`/`jsonPrimitive`/`contentOrNull` extensions, but
//     keeping these named locally for readability inside the parser above).
private fun kotlinx.serialization.json.JsonElement.asObject() =
    this as kotlinx.serialization.json.JsonObject

private fun kotlinx.serialization.json.JsonElement.asArray() =
    this as kotlinx.serialization.json.JsonArray

private fun kotlinx.serialization.json.JsonElement.asPrimitive() =
    this as kotlinx.serialization.json.JsonPrimitive
