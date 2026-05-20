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
    const val USE_LOCAL_VECTOR_SEARCH = false
}

import app.trashai.gemini.GeminiClient
import app.trashai.gemini.GeminiResult

class SupabaseVectorClient(
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL,
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val gemini = GeminiClient()

    val isConfigured: Boolean get() = supabaseUrl.isNotBlank() && anonKey.isNotBlank() && gemini.isConfigured

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
     * 클라이언트 내부에서 직접 Gemini API를 호출하여 이미지 속 사물을 고정밀 판독하고
     * 한글 단어 후보군을 그대로 반환합니다.
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

        when (val geminiRes = gemini.classifyTrashKeywords(jpegBytes)) {
            is GeminiResult.Ok -> {
                val keywords = geminiRes.value
                if (keywords.isEmpty()) {
                    SupabaseResult.Ok(emptyList())
                } else {
                    // 가장 첫 번째 판독 단어가 실시간 화면의 바운딩 박스 라벨이 됩니다.
                    val results = keywords.mapIndexed { idx, item ->
                        VectorResult(
                            id = idx.toLong() + 1,
                            item_name = item,
                            category = "재활용",
                            disposal_method = "",
                            disposal_time = "",
                            similarity = if (idx == 0) 1.0 else 0.8
                        )
                    }
                    SupabaseResult.Ok(results)
                }
            }
            is GeminiResult.HttpError -> SupabaseResult.HttpError(geminiRes.code, geminiRes.body)
            is GeminiResult.NetworkError -> SupabaseResult.NetworkError(geminiRes.detail)
            is GeminiResult.ParseError -> SupabaseResult.ParseError(geminiRes.rawSnippet)
            is GeminiResult.InvalidInput -> SupabaseResult.InvalidInput(geminiRes.detail)
            is GeminiResult.NotConfigured -> SupabaseResult.NotConfigured
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
            
            // ML Kit 5대 카테고리 매핑
            labelLower.contains("home goods") || labelLower.contains("home") -> "플라스틱"
            labelLower.contains("plants") || labelLower.contains("plant") -> "나무"
            
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
        Log.d(TAG, "USE_LOCAL_VECTOR_SEARCH - ML Kit 레이블: '$rawLabel' -> 매칭 키워드: '$matchedKorean'")
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
