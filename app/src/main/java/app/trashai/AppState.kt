package app.trashai

import android.content.Context
import app.trashai.data.CommonGuide
import app.trashai.data.ItemRule
import app.trashai.data.KeywordHit
import app.trashai.data.WasteGuideDb
import app.trashai.data.commonGuideById
import app.trashai.data.itemById
import app.trashai.data.ordinanceByRegion
import app.trashai.data.searchByKeywords
import app.trashai.gemini.GeminiClient
import app.trashai.gemini.GeminiResult
import app.trashai.supabase.SupabaseVectorClient
import app.trashai.supabase.SupabaseResult
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
    val regionLabel: String = "위치 확인 중...",
    val regionLoading: Boolean = false,
    /** JPEG of the most recent capture (cropped or full). Shown in the top half
     *  whenever a result sheet is open, replacing the live preview. */
    val lastCapturedJpeg: ByteArray? = null,
    val regionOrdinance: app.trashai.data.RegionOrdinance? = null,
)

sealed interface SheetState {
    data object Idle : SheetState
    data class Loading(val message: String) : SheetState
    data class Item(
        val rule: ItemRule, 
        val alternates: List<KeywordHit>,
        val commonGuide: CommonGuide? = null
    ) : SheetState
    data class Clarify(val candidates: List<KeywordHit>) : SheetState
    data class Empty(val detail: String) : SheetState
    data class Error(val message: String) : SheetState

    /** Conversational fallback — AI asks user to describe the item. */
    data class AskUser(
        val prompt: String,
        val history: List<Turn> = emptyList(),
    ) : SheetState

    data class Confirming(
        val rule: ItemRule,
        val alternates: List<KeywordHit>,
        val sourceLabel: String,
        val commonGuide: CommonGuide? = null,
    ) : SheetState

    /** Legal info & App info sheet. */
    data class Info(val initialTab: String = "개인정보 처리방침") : SheetState

    data class Turn(val from: Speaker, val text: String)
    enum class Speaker { Ai, User }
}

class AppState(private val appContext: Context) {

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gemini = GeminiClient()
    private val supabaseVector = SupabaseVectorClient()

    private val ambiguityGap = 0.15f

    fun preloadDb() {
        scope.launch {
            runCatching { 
                val db = WasteGuideDb.open(appContext)
                val ord = db.ordinanceByRegion("경기도", "고양시 일산동구")
                _state.update { it.copy(regionOrdinance = ord) }
            }.onFailure { e ->
                _state.update { it.copy(sheetState = SheetState.Error(e.message ?: "DB open failed")) }
            }
        }
    }

    /**
     * Called when the user has selected a region (or full frame) and confirmed
     * they want it analyzed. We send the JPEG to Gemini, then ground the result
     * against the local DB.
     */
    suspend fun onCapture(jpegBytes: ByteArray, rawLabel: String? = null) {
        // 이미 유효한 캐시 라벨이나 분류명이 전달된 경우, 즉시 로컬 DB 그라운딩을 수행하여 비용 및 대기 시간 단축
        if (!rawLabel.isNullOrBlank() && rawLabel != "미확인" && rawLabel != "확인 불가" && rawLabel != "분석 중...") {
            _state.update {
                it.copy(
                    sheetState = SheetState.Loading("로컬 DB 가이드 매칭 중…"),
                    lastCapturedJpeg = jpegBytes,
                )
            }
            groundAndPresent(listOf(rawLabel), sourceLabel = "Cached Match")
            return
        }

        if (!supabaseVector.isConfigured) {
            _state.update { it.copy(sheetState = SheetState.Error("local.properties의 SUPABASE_URL 및 SUPABASE_ANON_KEY를 입력해주세요. (Clean+Rebuild 필요)")) }
            return
        }
        _state.update {
            it.copy(
                sheetState = SheetState.Loading("AI가 벡터 분석 중… (이미지 ${jpegBytes.size / 1024}KB)"),
                lastCapturedJpeg = jpegBytes,
            )
        }
        
        val sigunguCode = _state.value.regionOrdinance?.regionId ?: "1100000000"
        
        when (val r = supabaseVector.searchTrashVector(jpegBytes, sigunguCode, rawLabel)) {
            is SupabaseResult.Ok -> {
                if (r.value.isEmpty()) {
                    startAskUser(reason = "벡터 검색 매칭 결과가 없습니다.")
                } else {
                    // 상위 유사도 매칭된 아이템명을 키워드로 획득하여 로컬 DB Grounding 수행
                    val keywords = r.value.map { it.item_name }
                    groundAndPresent(keywords, sourceLabel = "Vector Search")
                }
            }
            is SupabaseResult.HttpError -> _state.update {
                it.copy(sheetState = SheetState.Error("Supabase HTTP ${r.code}\n${r.body}"))
            }
            is SupabaseResult.ParseError -> _state.update {
                it.copy(sheetState = SheetState.Error("Supabase 응답 파싱 실패:\n${r.rawSnippet}"))
            }
            is SupabaseResult.NetworkError -> _state.update {
                it.copy(sheetState = SheetState.Error("네트워크 오류: ${r.detail}"))
            }
            is SupabaseResult.InvalidInput -> _state.update {
                it.copy(sheetState = SheetState.Error("입력 오류: ${r.detail}"))
            }
            SupabaseResult.NotConfigured -> _state.update {
                it.copy(sheetState = SheetState.Error("Supabase 접속 정보가 설정되지 않았습니다."))
            }
        }
    }

    fun pickItem(itemId: String) {
        scope.launch {
            val db = withContext(Dispatchers.IO) { WasteGuideDb.open(appContext) }
            val rule = withContext(Dispatchers.IO) { db.itemById(itemId) }
            if (rule == null) {
                _state.update { it.copy(sheetState = SheetState.Empty("선택한 품목을 찾지 못했습니다.")) }
            } else {
                val guide = if (isEcycleItem(rule)) withContext(Dispatchers.IO) { db.commonGuideById("ecycle") } else null
                _state.update { it.copy(sheetState = SheetState.Item(rule, alternates = emptyList(), commonGuide = guide)) }
            }
        }
    }

    fun dismissSheet() {
        _state.update { it.copy(sheetState = SheetState.Idle, lastCapturedJpeg = null) }
    }

    fun showInfo(tab: String = "개인정보 처리방침") {
        _state.update { it.copy(sheetState = SheetState.Info(tab)) }
    }

    /** Tap location pill → fetch GPS + reverse-geocode, update header label. */
    fun fetchRegionFromGps() {
        scope.launch {
            _state.update { it.copy(regionLoading = true) }
            val r = LocationHelper.fetchCurrentRegion(appContext)
            when (r) {
                is LocationHelper.Result.Ok -> {
                    val ord = withContext(Dispatchers.IO) {
                        runCatching {
                            val db = WasteGuideDb.open(appContext)
                            db.ordinanceByRegion(r.sido, r.locality + " " + r.subLocality)
                        }.getOrNull()
                    }
                    _state.update { it.copy(regionLabel = r.display, regionLoading = false, regionOrdinance = ord) }
                }
                LocationHelper.Result.PermissionDenied -> _state.update { s -> s.copy(
                    regionLoading = false,
                    sheetState = SheetState.Error("위치 권한이 거부되었습니다. 설정에서 허용해주세요."),
                )}
                LocationHelper.Result.NoLocation -> _state.update { s -> s.copy(
                    regionLoading = false,
                    sheetState = SheetState.Error("현재 위치를 가져오지 못했습니다. GPS가 켜져 있는지 확인하세요."),
                )}
                is LocationHelper.Result.Error -> _state.update { s -> s.copy(
                    regionLoading = false,
                    sheetState = SheetState.Error("위치 오류: ${r.message}"),
                )}
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
            val guide = if (isEcycleItem(rule)) withContext(Dispatchers.IO) { db.commonGuideById("ecycle") } else null
            _state.update {
                it.copy(
                    sheetState = SheetState.Confirming(
                        rule = rule,
                        alternates = hits.drop(1),
                        sourceLabel = "사용자 묘사 매칭",
                        commonGuide = guide,
                    )
                )
            }
        }
    }

    fun confirmYes() {
        val s = _state.value.sheetState as? SheetState.Confirming ?: return
        _state.update { it.copy(sheetState = SheetState.Item(s.rule, s.alternates, s.commonGuide)) }
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
                val guide = if (isEcycleItem(rule)) withContext(Dispatchers.IO) { db.commonGuideById("ecycle") } else null
                _state.update {
                    it.copy(
                        sheetState = SheetState.Confirming(
                            rule = rule,
                            alternates = hits.drop(1),
                            sourceLabel = sourceLabel,
                            commonGuide = guide,
                        )
                    )
                }
            }
        } else {
            _state.update { it.copy(sheetState = SheetState.Clarify(hits)) }
        }
    }

    private fun isEcycleItem(rule: ItemRule): Boolean {
        val cat = rule.primaryCategory ?: ""
        val name = rule.itemName
        return cat.contains("가전") || name.contains("TV") || name.contains("냉장고") || 
               name.contains("세탁기") || name.contains("에어컨") || name.contains("전자레인지") || 
               name.contains("청소기") || name.contains("컴퓨터") || name.contains("모니터") || 
               name.contains("노트북") || name.contains("선풍기") || name.contains("가습기") || 
               name.contains("헤어드라이어") || name.contains("러닝머신")
    }
}
