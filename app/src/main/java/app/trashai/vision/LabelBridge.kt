package app.trashai.vision

/**
 * Maps ML Kit ImageLabeling English labels to:
 *   - displayKo: short Korean string shown on the box overlay
 *   - keywords: Korean keyword candidates fed to app_search_keyword (DB lookup)
 *
 * Curated by hand for common recyclable / household trash classes.
 * Display string is intentionally short (1~2 단어) for the box label.
 */
object LabelBridge {

    data class Bridge(val displayKo: String, val keywords: List<String>)

    private val map: Map<String, Bridge> = buildMap {
        // ---- Drink containers / bottles ----
        put("bottle", Bridge("페트병", listOf("페트병", "플라스틱병", "플라스틱")))
        put("plastic bottle", Bridge("페트병", listOf("페트병", "플라스틱병", "플라스틱")))
        put("water bottle", Bridge("생수병", listOf("페트병", "생수병")))
        put("soda bottle", Bridge("음료병", listOf("페트병", "음료수병")))
        put("wine bottle", Bridge("유리병", listOf("유리병", "와인병")))
        put("beer bottle", Bridge("유리병", listOf("유리병", "맥주병")))
        put("glass bottle", Bridge("유리병", listOf("유리병")))

        // ---- Cups / drinkware ----
        put("cup", Bridge("컵", listOf("컵", "종이컵")))
        put("paper cup", Bridge("종이컵", listOf("종이컵")))
        put("plastic cup", Bridge("플라스틱컵", listOf("플라스틱컵", "플라스틱")))
        put("mug", Bridge("머그", listOf("머그", "도자기")))
        put("drinkware", Bridge("컵", listOf("컵")))
        put("tableware", Bridge("식기", listOf("식기")))
        put("wine glass", Bridge("유리잔", listOf("유리", "유리잔")))

        // ---- Cans / metal ----
        put("can", Bridge("캔", listOf("캔", "알루미늄캔", "철캔")))
        put("tin can", Bridge("철캔", listOf("철캔", "캔")))
        put("aluminum can", Bridge("알루미늄캔", listOf("알루미늄캔", "캔")))
        put("metal", Bridge("고철", listOf("고철", "캔")))

        // ---- Cartons / paper / cardboard ----
        put("carton", Bridge("종이팩", listOf("종이팩", "우유팩")))
        put("milk carton", Bridge("우유팩", listOf("우유팩", "종이팩")))
        put("juice carton", Bridge("종이팩", listOf("종이팩")))
        put("box", Bridge("종이상자", listOf("종이상자", "박스")))
        put("cardboard", Bridge("골판지", listOf("종이상자", "골판지", "박스")))
        put("packaging and labeling", Bridge("포장재", listOf("종이상자", "포장")))
        put("paper", Bridge("종이", listOf("종이")))
        put("paper product", Bridge("종이", listOf("종이")))
        put("newspaper", Bridge("신문지", listOf("신문지", "종이")))
        put("magazine", Bridge("잡지", listOf("잡지", "종이")))
        put("book", Bridge("책", listOf("책", "종이")))
        put("publication", Bridge("종이", listOf("종이")))
        put("envelope", Bridge("종이", listOf("종이")))
        put("document", Bridge("종이", listOf("종이")))
        put("receipt", Bridge("종이", listOf("종이")))

        // ---- Glass / mirrors ----
        put("glass", Bridge("유리", listOf("유리", "유리병")))
        put("glassware", Bridge("유리", listOf("유리")))
        put("mirror", Bridge("거울", listOf("거울", "유리")))
        put("window", Bridge("유리창", listOf("유리")))

        // ---- Plastics / vinyl bags / tupperware ----
        put("plastic", Bridge("플라스틱", listOf("플라스틱")))
        put("plastic bag", Bridge("비닐", listOf("비닐", "비닐봉지")))
        put("bag", Bridge("비닐", listOf("비닐", "비닐봉지")))
        put("plastic wrap", Bridge("비닐", listOf("비닐")))
        put("container", Bridge("플라스틱용기", listOf("플라스틱 용기", "플라스틱")))
        put("tupperware", Bridge("플라스틱용기", listOf("플라스틱 용기", "플라스틱")))
        put("jar", Bridge("유리병", listOf("유리병", "병")))
        put("toy", Bridge("장난감", listOf("플라스틱", "장난감")))

        // ---- Food / bio ----
        put("food", Bridge("음식물", listOf("음식물")))
        put("fruit", Bridge("과일", listOf("음식물", "과일")))
        put("vegetable", Bridge("채소", listOf("음식물", "채소")))
        put("banana", Bridge("음식물", listOf("음식물")))
        put("apple", Bridge("음식물", listOf("음식물")))
        put("orange", Bridge("음식물", listOf("음식물")))
        put("egg", Bridge("음식물", listOf("음식물")))
        put("bread", Bridge("음식물", listOf("음식물")))
        put("dish", Bridge("음식물", listOf("음식물")))
        put("snack", Bridge("음식물", listOf("음식물")))
        put("seafood", Bridge("음식물", listOf("음식물")))

        // ---- Electronics / household / hazardous ----
        put("electronic device", Bridge("폐가전", listOf("폐가전", "소형가전")))
        put("electronics", Bridge("폐가전", listOf("폐가전")))
        put("mobile phone", Bridge("휴대폰", listOf("휴대폰", "폐가전")))
        put("smartphone", Bridge("휴대폰", listOf("휴대폰", "폐가전")))
        put("phone", Bridge("휴대폰", listOf("휴대폰", "폐가전")))
        put("laptop", Bridge("노트북", listOf("폐가전")))
        put("computer", Bridge("컴퓨터", listOf("폐가전")))
        put("television", Bridge("TV", listOf("폐가전", "대형폐기물")))
        put("monitor", Bridge("모니터", listOf("폐가전")))
        put("remote control", Bridge("리모컨", listOf("리모컨", "건전지")))
        put("battery", Bridge("건전지", listOf("건전지", "배터리")))
        put("light bulb", Bridge("전구", listOf("형광등", "전구")))
        put("lighting", Bridge("조명", listOf("형광등", "전구")))
        put("lamp", Bridge("조명", listOf("형광등", "전구")))
        put("fluorescent lamp", Bridge("형광등", listOf("형광등")))
        put("headphones", Bridge("이어폰", listOf("폐가전")))

        // ---- Clothing / shoes / fabric ----
        put("clothing", Bridge("의류", listOf("의류", "헌옷")))
        put("shirt", Bridge("의류", listOf("의류", "헌옷")))
        put("dress", Bridge("의류", listOf("의류", "헌옷")))
        put("jeans", Bridge("의류", listOf("의류", "헌옷")))
        put("trousers", Bridge("의류", listOf("의류", "헌옷")))
        put("jacket", Bridge("의류", listOf("의류", "헌옷")))
        put("shoe", Bridge("신발", listOf("신발", "헌옷")))
        put("footwear", Bridge("신발", listOf("신발", "헌옷")))
        put("hat", Bridge("모자", listOf("의류", "모자")))
        put("textile", Bridge("섬유", listOf("의류", "섬유")))

        // ---- Furniture / large items ----
        put("furniture", Bridge("가구", listOf("대형폐기물", "가구")))
        put("chair", Bridge("의자", listOf("대형폐기물")))
        put("table", Bridge("탁자", listOf("대형폐기물")))
        put("bed", Bridge("침대", listOf("대형폐기물")))
        put("couch", Bridge("소파", listOf("대형폐기물")))
        put("sofa", Bridge("소파", listOf("대형폐기물")))
        put("mattress", Bridge("매트리스", listOf("대형폐기물")))

        // ---- Misc ----
        put("umbrella", Bridge("우산", listOf("우산")))
        put("pen", Bridge("펜", listOf("플라스틱")))
        put("pencil", Bridge("연필", listOf("종이")))
        put("toothbrush", Bridge("칫솔", listOf("플라스틱")))
        put("razor", Bridge("면도기", listOf("플라스틱")))
        put("toiletries", Bridge("욕실용품", listOf("플라스틱")))
        put("cosmetics", Bridge("화장품", listOf("플라스틱", "유리병")))
        put("packaging", Bridge("포장재", listOf("종이상자", "비닐", "플라스틱")))

        // ---- Plants / organic (often not trash but ML Kit may flag) ----
        put("plant", Bridge("식물", listOf("음식물")))
        put("flower", Bridge("꽃", listOf("음식물")))
        put("tree", Bridge("식물", listOf("음식물")))
        put("leaf", Bridge("식물", listOf("음식물")))
        put("grass", Bridge("식물", listOf("음식물")))

        // ---------------------------------------------------------------
        // TACO 60 categories — exact strings as they appear in taco_labels.txt
        // (case-insensitive lookup, also stored as lowercase keys here).
        // ---------------------------------------------------------------
        put("aluminium foil", Bridge("알루미늄호일", listOf("고철", "알루미늄")))
        put("aluminium blister pack", Bridge("알약포장", listOf("비닐")))
        put("carded blister pack", Bridge("알약포장", listOf("비닐")))
        put("other plastic bottle", Bridge("플라스틱병", listOf("페트병", "플라스틱병")))
        put("clear plastic bottle", Bridge("투명페트병", listOf("투명페트병", "페트병")))
        put("plastic bottle cap", Bridge("병뚜껑", listOf("페트병", "병뚜껑", "플라스틱")))
        put("metal bottle cap", Bridge("금속뚜껑", listOf("캔", "병뚜껑")))
        put("broken glass", Bridge("깨진유리", listOf("유리", "위험물")))
        put("food can", Bridge("식품캔", listOf("캔", "철캔")))
        put("aerosol", Bridge("스프레이캔", listOf("캔", "위험물")))
        put("drink can", Bridge("음료캔", listOf("캔", "알루미늄캔")))
        put("toilet tube", Bridge("휴지심", listOf("종이")))
        put("other carton", Bridge("종이팩", listOf("종이팩", "우유팩")))
        put("egg carton", Bridge("계란판", listOf("종이팩", "종이")))
        put("drink carton", Bridge("종이팩", listOf("종이팩", "우유팩")))
        put("corrugated carton", Bridge("종이상자", listOf("종이상자", "골판지", "박스")))
        put("meal carton", Bridge("종이팩", listOf("종이팩")))
        put("pizza box", Bridge("피자박스", listOf("종이상자", "박스")))
        put("disposable plastic cup", Bridge("플라스틱컵", listOf("플라스틱컵", "플라스틱")))
        put("foam cup", Bridge("스티로폼컵", listOf("스티로폼", "플라스틱컵")))
        put("glass cup", Bridge("유리잔", listOf("유리")))
        put("other plastic cup", Bridge("플라스틱컵", listOf("플라스틱컵", "플라스틱")))
        put("food waste", Bridge("음식물", listOf("음식물")))
        put("plastic lid", Bridge("플라스틱뚜껑", listOf("플라스틱", "뚜껑")))
        put("metal lid", Bridge("금속뚜껑", listOf("캔", "뚜껑")))
        put("other plastic", Bridge("플라스틱", listOf("플라스틱")))
        put("tissues", Bridge("휴지", listOf("일반쓰레기")))
        put("wrapping paper", Bridge("포장지", listOf("종이")))
        put("normal paper", Bridge("종이", listOf("종이")))
        put("plastified paper bag", Bridge("코팅종이봉투", listOf("일반쓰레기", "종이")))
        put("plastic film", Bridge("비닐필름", listOf("비닐")))
        put("six pack rings", Bridge("비닐", listOf("비닐")))
        put("garbage bag", Bridge("종량제봉투", listOf("종량제봉투", "비닐")))
        put("other plastic wrapper", Bridge("비닐포장", listOf("비닐")))
        put("single-use carrier bag", Bridge("비닐봉지", listOf("비닐", "비닐봉지")))
        put("polypropylene bag", Bridge("비닐봉지", listOf("비닐", "비닐봉지")))
        put("crisp packet", Bridge("과자봉지", listOf("비닐")))
        put("spread tub", Bridge("플라스틱통", listOf("플라스틱 용기", "플라스틱")))
        put("disposable food container", Bridge("일회용용기", listOf("플라스틱 용기", "플라스틱")))
        put("foam food container", Bridge("스티로폼용기", listOf("스티로폼")))
        put("other plastic container", Bridge("플라스틱용기", listOf("플라스틱 용기", "플라스틱")))
        put("plastic glooves", Bridge("비닐장갑", listOf("비닐")))
        put("plastic utensils", Bridge("플라스틱식기", listOf("플라스틱")))
        put("pop tab", Bridge("캔따개", listOf("캔")))
        put("rope & strings", Bridge("끈", listOf("일반쓰레기")))
        put("scrap metal", Bridge("고철", listOf("고철")))
        put("squeezable tube", Bridge("튜브", listOf("플라스틱")))
        put("plastic straw", Bridge("플라스틱빨대", listOf("플라스틱")))
        put("paper straw", Bridge("종이빨대", listOf("종이")))
        put("styrofoam piece", Bridge("스티로폼", listOf("스티로폼")))
        put("unlabeled litter", Bridge("미분류", listOf("일반쓰레기")))
        put("cigarette", Bridge("담배꽁초", listOf("일반쓰레기")))
    }

    /** Korean display label shown on the bounding box. Falls back heuristically. */
    fun displayKo(englishLabel: String): String {
        val key = englishLabel.lowercase().trim()
        map[key]?.let { return it.displayKo }
        for (word in key.split(" ", "-", "_")) {
            map[word]?.let { return it.displayKo }
        }
        // Heuristic fallback: substring contains
        for ((k, v) in map) {
            if (key.contains(k)) return v.displayKo
        }
        return "기타"
    }

    /** Keywords for DB search. Empty if no mapping (caller should fall back). */
    fun toKoreanKeywords(englishLabel: String): List<String> {
        val key = englishLabel.lowercase().trim()
        map[key]?.let { return it.keywords }
        for (word in key.split(" ", "-", "_")) {
            map[word]?.let { return it.keywords }
        }
        for ((k, v) in map) {
            if (key.contains(k)) return v.keywords
        }
        return emptyList()
    }
}
