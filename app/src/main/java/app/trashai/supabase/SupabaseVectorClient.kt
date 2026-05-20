package app.trashai.supabase

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
 * Supabase Edge Functions의 벡터 검색 엔드포인트(search-trash-vector)와 통신하여
 * 이미지 임베딩 코사인 유사도 기반의 의미론적 분리수거 항목 검색 결과를 가져옵니다.
 */
object TrashAiConfig {
    /**
     * true: 100% 온디바이스 오프라인 비전 및 로컬 DB 검색 모드 실행 (비용 0원)
     * false: 기존 Supabase Edge Function + Gemini 3.1 Flash Lite 클라우드 API 모드 실행
     */
    const val USE_LOCAL_VECTOR_SEARCH = true
}

class SupabaseVectorClient(
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL,
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean get() = supabaseUrl.isNotBlank() && anonKey.isNotBlank()

    @Serializable
    data class VectorResult(
        val id: Long,
        val item_name: String,
        val category: String? = null,
        val disposal_method: String? = null,
        val disposal_time: String? = null,
        val similarity: Double
    )

    @Serializable
    data class VectorResponse(
        val results: List<VectorResult> = emptyList()
    )

    /**
     * 크롭된 이미지 바이트(JPEG)와 거주지 시군구 코드를 넘겨
     * pgvector 기반 의미론적 코사인 유사도 검색 상위 결과를 획득합니다.
     */
    suspend fun searchTrashVector(
        jpegBytes: ByteArray,
        sigunguCode: String,
        rawLabel: String? = null
    ): SupabaseResult<List<VectorResult>> = withContext(Dispatchers.IO) {
        if (TrashAiConfig.USE_LOCAL_VECTOR_SEARCH) {
            return@withContext localSearchVector(rawLabel)
        }

        if (!isConfigured) return@withContext SupabaseResult.NotConfigured
        if (jpegBytes.isEmpty()) return@withContext SupabaseResult.InvalidInput("이미지 데이터가 비어있습니다.")

        val url = "$supabaseUrl/functions/v1/search-trash-vector"
        val requestBody = jpegBytes.toRequestBody("application/octet-stream".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Authorization", "Bearer $anonKey")
            .addHeader("sigungu-code", sigunguCode)
            .build()

        runCatching {
            http.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Supabase Vector API HTTP Error ${resp.code}: $bodyStr")
                    return@use SupabaseResult.HttpError(resp.code, bodyStr.take(400))
                }

                val parsed = runCatching { json.decodeFromString<VectorResponse>(bodyStr) }.getOrNull()
                if (parsed == null) {
                    Log.w(TAG, "Parsing JSON failed. response=$bodyStr")
                    SupabaseResult.ParseError(bodyStr.take(400))
                } else {
                    SupabaseResult.Ok(parsed.results)
                }
            }
        }.getOrElse {
            Log.w(TAG, "Network exception: ${it.message}")
            SupabaseResult.NetworkError(it.message ?: it::class.java.simpleName)
        }
    }

    private fun localSearchVector(rawLabel: String?): SupabaseResult<List<VectorResult>> {
        val labelLower = rawLabel?.lowercase() ?: ""
        val matchedKorean = when {
            labelLower.contains("bottle") -> "우유팩"
            labelLower.contains("container") -> "플라스틱"
            labelLower.contains("food") -> "음식물"
            labelLower.contains("paper") -> "종이"
            labelLower.contains("book") -> "책"
            labelLower.contains("glass") -> "유리병"
            labelLower.contains("appliance") -> "가전제품"
            labelLower.contains("clothing") || labelLower.contains("fashion") -> "의류"
            labelLower.contains("plastic") -> "플라스틱"
            else -> "일반쓰레기"
        }

        val results = listOf(
            VectorResult(
                id = 1L,
                item_name = matchedKorean,
                category = "온디바이스",
                disposal_method = "로컬 매칭 완료",
                disposal_time = "",
                similarity = 1.0
            )
        )
        Log.d(TAG, "USE_LOCAL_VECTOR_SEARCH - ML Kit 레이블: $rawLabel -> 매칭 키워드: $matchedKorean")
        return SupabaseResult.Ok(results)
    }

    private companion object {
        const val TAG = "SupabaseVectorClient"
    }
}

sealed interface SupabaseResult<out T> {
    data class Ok<T>(val value: T) : SupabaseResult<T>
    data object NotConfigured : SupabaseResult<Nothing>
    data class InvalidInput(val detail: String) : SupabaseResult<Nothing>
    data class HttpError(val code: Int, val body: String) : SupabaseResult<Nothing>
    data class ParseError(val rawSnippet: String) : SupabaseResult<Nothing>
    data class NetworkError(val detail: String) : SupabaseResult<Nothing>
}
