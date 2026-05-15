package app.trashai

import android.content.Context
import app.trashai.data.ItemRule
import app.trashai.data.KeywordHit
import app.trashai.data.WasteGuideDb
import app.trashai.data.itemById
import app.trashai.data.searchByKeywords
import app.trashai.gemini.GeminiClient
import app.trashai.gemini.GeminiResult
import app.trashai.location.LocationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppUiState(
    val sheetState: SheetState = SheetState.Idle,
    /** Region label shown in the top pill. Defaults to manual seed; updated by GPS. */
    val regionLabel: String = "고양시 일산동구",
    val regionLoading: Boolean = false,
    /** JPEG of the most recent capture (cropped or full). Shown in the top half
     *  whenever a result sheet is open, replacing the live preview. */
    val lastCapturedJpeg: ByteArray? = null,
) {
    // Default equals/hashCode would compare ByteArray by reference; that's fine
    // for our usage (we only test sheetState/regionLabel-driven recomposition).
}

sealed interface SheetState {
    data object Idle : SheetState
    data class Loading(val message: String) : SheetState
    data class Item(val rule: ItemRule, val alternates: List<KeywordHit>) : SheetState
    data class Clarify(val candidates: List<KeywordHit>) : SheetState
    data class Empty(val detail: String) : SheetState
    data class Error(val message: String) : SheetState

    /** Conversational fallback — AI asks user to describe the item. */
    data class AskUser(
        val prompt: String,
        val history: List<Turn> = emptyList(),
    ) : SheetState

    /** "Is this the right item?" confirmation step. */
    data class Confirming(
        val rule: ItemRule,
        val alternates: List<KeywordHit>,
        val sourceLabel: String,
    ) : SheetState

    data class Turn(val from: Speaker, val text: String)
    enum class Speaker { Ai, User }
}

class AppState(private val appContext: Context) {

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gemini = GeminiClient()

    private val ambiguityGap = 0.15f

    fun preloadDb() {
        scope.launch {
            runCatching { WasteGuideDb.open(appContext) }
                .onFailure { e ->
                    _state.update { it.copy(sheetState = SheetState.Error(e.message ?: "DB open failed")) }
                }
        }
    }

    /**
     * Called when the user has selected a region (or full frame) and confirmed
     * they want it analyzed. We send the JPEG to Gemini, then ground the result
     * against the local DB.
     */
    suspend fun onCapture(jpegBytes: ByteArray) {
        if (!gemini.isConfigured) {
            _state.update { it.copy(sheetState = SheetState.Error("local.properties의 GEMINI_API_KEY를 입력해주세요. (Clean+Rebuild 필요)")) }
            return
        }
        _state.update {
            it.copy(
                sheetState = SheetState.Loading("AI가 분석 중… (이미지 ${jpegBytes.size / 1024}KB)"),
                lastCapturedJpeg = jpegBytes,
            )
        }
        when (val r = gemini.classifyTrashKeywords(jpegBytes)) {
            is GeminiResult.Ok -> {
                if (r.value.isEmpty()) startAskUser(reason = "AI가 빈 응답을 반환했어요.")
                else groundAndPresent(r.value, sourceLabel = "Gemini")
            }
            is GeminiResult.HttpError -> _state.update {
                it.copy(sheetState = SheetState.Error("Gemini HTTP ${r.code}\n${r.body}"))
            }
            is GeminiResult.ParseError -> _state.update {
                it.copy(sheetState = SheetState.Error("Gemini 응답 파싱 실패:\n${r.rawSnippet}"))
            }
            is GeminiResult.NetworkError -> _state.update {
                it.copy(sheetState = SheetState.Error("네트워크 오류: ${r.detail}"))
            }
            is GeminiResult.InvalidInput -> _state.update {
                it.copy(sheetState = SheetState.Error("입력 오류: ${r.detail}"))
            }
            GeminiResult.NotConfigured -> _state.update {
                it.copy(sheetState = SheetState.Error("API 키가 설정되지 않았습니다."))
            }
        }
    }

    fun pickItem(itemId: String) {
        scope.launch {
            val rule = withContext(Dispatchers.IO) {
                WasteGuideDb.open(appContext).itemById(itemId)
            }
            if (rule == null) {
                _state.update { it.copy(sheetState = SheetState.Empty("선택한 품목을 찾지 못했습니다.")) }
            } else {
                _state.update { it.copy(sheetState = SheetState.Item(rule, alternates = emptyList())) }
            }
        }
    }

    fun dismissSheet() {
        _state.update { it.copy(sheetState = SheetState.Idle, lastCapturedJpeg = null) }
    }

    /** Tap location pill → fetch GPS + reverse-geocode, update header label. */
    fun fetchRegionFromGps() {
        scope.launch {
            _state.update { it.copy(regionLoading = true) }
            val r = LocationHelper.fetchCurrentRegion(appContext)
            _state.update { s ->
                when (r) {
                    is LocationHelper.Result.Ok -> s.copy(regionLabel = r.display, regionLoading = false)
                    LocationHelper.Result.PermissionDenied -> s.copy(
                        regionLoading = false,
                        sheetState = SheetState.Error("위치 권한이 거부되었습니다. 설정에서 허용해주세요."),
                    )
                    LocationHelper.Result.NoLocation -> s.copy(
                        regionLoading = false,
                        sheetState = SheetState.Error("현재 위치를 가져오지 못했습니다. GPS가 켜져 있는지 확인하세요."),
                    )
                    is LocationHelper.Result.Error -> s.copy(
                        regionLoading = false,
                        sheetState = SheetState.Error("위치 오류: ${r.message}"),
                    )
                }
            }
        }
    }

    /** Tap "API 테스트" — sends a tiny request to verify the key & model are reachable. */
    fun testApiKey() {
        scope.launch {
            _state.update { it.copy(sheetState = SheetState.Loading("API 키 테스트 중…")) }
            val r = gemini.ping()
            _state.update {
                it.copy(
                    sheetState = when (r) {
                        is GeminiResult.Ok -> SheetState.Error("✅ 연결 OK\n${r.value}")
                        is GeminiResult.HttpError -> SheetState.Error("❌ HTTP ${r.code}\n${r.body}")
                        is GeminiResult.NetworkError -> SheetState.Error("❌ 네트워크: ${r.detail}")
                        GeminiResult.NotConfigured -> SheetState.Error("❌ API 키 미설정")
                        else -> SheetState.Error("❌ ${r::class.java.simpleName}")
                    }
                )
            }
        }
    }

    // ---- Conversational flow --------------------------------------------------

    fun startAskUser(reason: String? = null) {
        val prompt = reason?.let { "$it\n무엇인지 알려주실래요? (예: '뚜껑 달린 화장품 통', '깨진 거울')" }
            ?: "이 물건이 무엇인가요? 재질이나 용도를 알려주세요."
        _state.update {
            it.copy(sheetState = SheetState.AskUser(prompt = prompt, history = emptyList()))
        }
    }

    fun submitUserText(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        val current = _state.value.sheetState as? SheetState.AskUser
        val newHistory = (current?.history.orEmpty()) + SheetState.Turn(SheetState.Speaker.User, text)

        scope.launch {
            _state.update { it.copy(sheetState = SheetState.Loading("DB에서 찾는 중…")) }
            val db = withContext(Dispatchers.IO) { WasteGuideDb.open(appContext) }

            val initialNeedles = (listOf(text) + text.split(" ", "/", ",", " ").filter { it.length >= 2 })
                .distinct()
            var hits = withContext(Dispatchers.IO) { db.searchByKeywords(initialNeedles, limit = 6) }

            if (hits.isEmpty() && gemini.isConfigured) {
                when (val r = gemini.extractKeywordsFromText(text)) {
                    is GeminiResult.Ok -> {
                        if (r.value.isNotEmpty()) {
                            hits = withContext(Dispatchers.IO) { db.searchByKeywords(r.value, limit = 6) }
                        }
                    }
                    else -> { /* fall through to no-hits handling below */ }
                }
            }

            if (hits.isEmpty()) {
                _state.update {
                    it.copy(
                        sheetState = SheetState.AskUser(
                            prompt = "DB에서 비슷한 항목을 못 찾았어요. 더 자세히 알려주실래요? (재질, 크기, 어디에 쓰는지 등)",
                            history = newHistory + SheetState.Turn(
                                SheetState.Speaker.Ai,
                                "잘 모르겠어요. 좀 더 알려주세요."
                            )
                        )
                    )
                }
                return@launch
            }

            val top = hits.first()
            val rule = withContext(Dispatchers.IO) { db.itemById(top.itemId) }
            if (rule == null) {
                _state.update { it.copy(sheetState = SheetState.Empty("DB에서 ${top.itemName}을 찾지 못했습니다.")) }
                return@launch
            }
            _state.update {
                it.copy(
                    sheetState = SheetState.Confirming(
                        rule = rule,
                        alternates = hits.drop(1),
                        sourceLabel = "사용자 묘사 매칭",
                    )
                )
            }
        }
    }

    fun confirmYes() {
        val s = _state.value.sheetState as? SheetState.Confirming ?: return
        _state.update { it.copy(sheetState = SheetState.Item(s.rule, s.alternates)) }
    }

    fun confirmNo() {
        val s = _state.value.sheetState as? SheetState.Confirming ?: return
        if (s.alternates.isNotEmpty()) {
            _state.update { it.copy(sheetState = SheetState.Clarify(s.alternates)) }
        } else {
            startAskUser(reason = "그렇군요, 다시 알려주실래요?")
        }
    }

    // ---- internals -----------------------------------------------------------

    private suspend fun groundAndPresent(keywords: List<String>, sourceLabel: String) {
        val db = withContext(Dispatchers.IO) { WasteGuideDb.open(appContext) }
        val hits = withContext(Dispatchers.IO) { db.searchByKeywords(keywords, limit = 6) }
        if (hits.isEmpty()) {
            startAskUser(reason = "DB에 정확히 일치하는 항목이 없어요.")
            return
        }
        val top = hits.first()
        val second = hits.getOrNull(1)
        val confident = second == null || (top.weight - second.weight) > (top.weight * ambiguityGap)
        if (confident) {
            val rule = withContext(Dispatchers.IO) { db.itemById(top.itemId) }
            if (rule == null) {
                _state.update { it.copy(sheetState = SheetState.Empty("DB에서 ${top.itemName}을 찾지 못했습니다.")) }
            } else {
                _state.update {
                    it.copy(
                        sheetState = SheetState.Confirming(
                            rule = rule,
                            alternates = hits.drop(1),
                            sourceLabel = sourceLabel,
                        )
                    )
                }
            }
        } else {
            _state.update { it.copy(sheetState = SheetState.Clarify(hits)) }
        }
    }
}
