package app.trashai

import android.content.Context
import app.trashai.data.ItemRule
import app.trashai.data.KeywordHit
import app.trashai.data.WasteGuideDb
import app.trashai.data.itemById
import app.trashai.data.searchByKeywords
import app.trashai.gemini.GeminiClient
import app.trashai.vision.Detection
import app.trashai.vision.LabelBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

data class AppUiState(
    val sheetState: SheetState = SheetState.Idle,
    val lastDetections: List<Detection> = emptyList(),
)

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
    private val analysisLock = Mutex()

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
     * Detection callback. Updates overlay every frame, but ONLY changes the
     * sheet content when the sheet is Idle. Once the user has any sheet open
     * (clarify, item, ask, confirming, …) the live camera no longer overrides
     * what they're looking at.
     */
    fun onDetections(detections: List<Detection>) {
        _state.update { it.copy(lastDetections = detections) }
        if (detections.isEmpty()) return
        if (_state.value.sheetState !is SheetState.Idle) return
        // Auto-present only when there's exactly ONE candidate box.
        // Multiple boxes → wait for the user to tap the one they care about.
        if (detections.size == 1) {
            scope.launch { groundFromDetection(detections.first(), sourceLabel = "카메라 인식") }
        }
    }

    /**
     * User explicitly tapped a bounding box on the camera overlay.
     * Always wins over current sheet state (user-initiated selection).
     */
    fun pickDetection(detection: Detection) {
        scope.launch {
            _state.update { it.copy(sheetState = SheetState.Loading("선택한 영역 분석 중…")) }
            groundFromDetection(detection, sourceLabel = "선택한 영역", forced = true)
        }
    }

    suspend fun onCapture(jpegBytes: ByteArray) {
        if (!gemini.isConfigured) {
            _state.update { it.copy(sheetState = SheetState.Error("local.properties의 GEMINI_API_KEY를 입력해주세요.")) }
            return
        }
        _state.update { it.copy(sheetState = SheetState.Loading("정밀 분석 중…")) }
        val keywords = gemini.classifyTrashKeywords(jpegBytes)
        if (keywords.isEmpty()) {
            // Gemini didn't help — kick off the conversational fallback instead
            // of just showing Empty.
            startAskUser(reason = "사진으로도 식별이 어렵네요.")
            return
        }
        groundAndPresent(keywords, sourceLabel = "Gemini")
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

    /** Dismiss whatever's on the sheet and resume live detection. */
    fun dismissSheet() {
        _state.update { it.copy(sheetState = SheetState.Idle) }
    }

    // ---- Conversational flow --------------------------------------------------

    /** Open the AI conversation panel from any state. */
    fun startAskUser(reason: String? = null) {
        val prompt = reason?.let { "$it\n무엇인지 알려주실래요? (예: '뚜껑 달린 화장품 통', '깨진 거울')" }
            ?: "이 물건이 무엇인가요? 재질이나 용도를 알려주세요."
        _state.update {
            it.copy(sheetState = SheetState.AskUser(prompt = prompt, history = emptyList()))
        }
    }

    /**
     * User typed a free-form Korean description. We:
     *   1. Search DB by raw text (cheap, often enough)
     *   2. If nothing or very weak, ask Gemini to extract keywords and re-search
     *   3. If still nothing → keep the conversation going
     *   4. Otherwise → Confirming(top match)
     */
    fun submitUserText(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        val current = _state.value.sheetState as? SheetState.AskUser
        val newHistory = (current?.history.orEmpty()) + SheetState.Turn(SheetState.Speaker.User, text)

        scope.launch {
            _state.update { it.copy(sheetState = SheetState.Loading("DB에서 찾는 중…")) }
            val db = withContext(Dispatchers.IO) { WasteGuideDb.open(appContext) }

            // Pass 1: split user text into rough tokens and search.
            val initialNeedles = (listOf(text) + text.split(" ", "/", ",", " ").filter { it.length >= 2 })
                .distinct()
            var hits = withContext(Dispatchers.IO) { db.searchByKeywords(initialNeedles, limit = 6) }

            // Pass 2: ask Gemini to reword if we got no/weak hits.
            if (hits.isEmpty() && gemini.isConfigured) {
                val keywords = gemini.extractKeywordsFromText(text)
                if (keywords.isNotEmpty()) {
                    hits = withContext(Dispatchers.IO) { db.searchByKeywords(keywords, limit = 6) }
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
        // Show the alternates as a clarify list so the user can pick one
        // OR fall back to AskUser if there are no alternates.
        if (s.alternates.isNotEmpty()) {
            _state.update { it.copy(sheetState = SheetState.Clarify(s.alternates)) }
        } else {
            startAskUser(reason = "그렇군요, 다시 알려주실래요?")
        }
    }

    // ---- internals -----------------------------------------------------------

    private suspend fun groundFromDetection(
        detection: Detection,
        sourceLabel: String,
        forced: Boolean = false,
    ) {
        if (!analysisLock.tryLock()) return
        try {
            if (!forced && _state.value.sheetState !is SheetState.Idle) return
            val englishLabels = detection.labels.map { it.label }
            val keywords = englishLabels.flatMap { LabelBridge.toKoreanKeywords(it) }
            if (keywords.isEmpty()) {
                presentGenericClarify()
                return
            }
            groundAndPresent(keywords, sourceLabel = sourceLabel)
        } finally {
            analysisLock.unlock()
        }
    }

    private suspend fun groundAndPresent(keywords: List<String>, sourceLabel: String) {
        val db = withContext(Dispatchers.IO) { WasteGuideDb.open(appContext) }
        val hits = withContext(Dispatchers.IO) { db.searchByKeywords(keywords, limit = 6) }
        if (hits.isEmpty()) {
            // No match at all — hand off to conversational AI fallback
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
                // Auto-presented by camera — use Confirming so user can reject easily
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

    private suspend fun presentGenericClarify() {
        val db = withContext(Dispatchers.IO) { WasteGuideDb.open(appContext) }
        val hits = withContext(Dispatchers.IO) {
            db.searchByKeywords(
                listOf("플라스틱", "종이", "유리", "캔", "비닐", "음식물"),
                limit = 6,
            )
        }
        if (hits.isEmpty()) {
            startAskUser(reason = "카테고리도 못 찾았어요.")
        } else {
            _state.update { it.copy(sheetState = SheetState.Clarify(hits)) }
        }
    }
}
