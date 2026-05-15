package app.trashai.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

object WasteGuideDb {
    private const val ASSET_NAME = "wasteguide.sqlite3"
    private const val DB_FILENAME = "wasteguide.sqlite3"

    @Volatile private var db: SQLiteDatabase? = null

    fun open(context: Context): SQLiteDatabase {
        db?.let { return it }
        synchronized(this) {
            db?.let { return it }
            val file = File(context.filesDir, DB_FILENAME)
            if (!file.exists()) {
                context.assets.open(ASSET_NAME).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val opened = SQLiteDatabase.openDatabase(
                file.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            db = opened
            return opened
        }
    }
}

data class ItemRule(
    val itemId: String,
    val itemName: String,
    val primaryCategory: String?,
    val dischargeMethod: String?,
    val featureText: String?,
    val cautionText: String?,
    val appSummary: String?,
    val sourceName: String,
    val sourceUrl: String,
)

data class KeywordHit(
    val itemId: String,
    val itemName: String,
    val matchedKeyword: String,
    val weight: Int,
)

private const val SELECT_RULE = """
    SELECT item_id, item_name, primary_category, discharge_method,
           feature_text, caution_text, app_summary, source_name, source_url
    FROM app_item_rule
"""

private fun android.database.Cursor.toItemRule() = ItemRule(
    itemId = getString(0),
    itemName = getString(1),
    primaryCategory = getString(2),
    dischargeMethod = getString(3),
    featureText = getString(4),
    cautionText = getString(5),
    appSummary = getString(6),
    sourceName = getString(7),
    sourceUrl = getString(8),
)

fun SQLiteDatabase.firstItemRule(): ItemRule? =
    rawQuery("$SELECT_RULE ORDER BY item_name LIMIT 1", null).use { c ->
        if (c.moveToFirst()) c.toItemRule() else null
    }

fun SQLiteDatabase.itemById(itemId: String): ItemRule? =
    rawQuery("$SELECT_RULE WHERE item_id = ? LIMIT 1", arrayOf(itemId)).use { c ->
        if (c.moveToFirst()) c.toItemRule() else null
    }

/**
 * Search app_search_keyword by exact or LIKE match (Korean strings expected).
 * Returns top hits ordered by weight desc, then keyword length asc (more specific first).
 */
fun SQLiteDatabase.searchByKeywords(needles: List<String>, limit: Int = 8): List<KeywordHit> {
    if (needles.isEmpty()) return emptyList()
    val results = mutableListOf<KeywordHit>()
    val seen = HashSet<String>()
    for (n in needles.distinct()) {
        if (n.isBlank()) continue
        val pattern = "%$n%"
        rawQuery(
            """
            SELECT k.target_id, r.item_name, k.keyword, k.weight
            FROM app_search_keyword k
            JOIN app_item_rule r ON r.item_id = k.target_id
            WHERE k.target_type = 'item' AND (k.keyword = ? OR k.keyword LIKE ?)
            ORDER BY (k.keyword = ?) DESC, k.weight DESC, length(k.keyword) ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(n, pattern, n, limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                if (seen.add(id)) {
                    results.add(
                        KeywordHit(
                            itemId = id,
                            itemName = c.getString(1),
                            matchedKeyword = c.getString(2),
                            weight = c.getInt(3),
                        )
                    )
                }
            }
        }
    }
    return results.take(limit)
}
