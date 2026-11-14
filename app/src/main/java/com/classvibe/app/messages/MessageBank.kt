package com.classvibe.app.messages

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.classvibe.app.data.AppDatabase
import com.classvibe.app.data.MessageStyle
import kotlinx.coroutines.flow.first
import kotlin.random.Random

private val Context.historyStore by preferencesDataStore(name = "message_history")

/**
 * 600+ offline notification messages (assets/messages.txt) with:
 *  - category + style filtering (Classic mix, Savage, Motivational, Tenglish, Hinglish)
 *  - per-category no-repeat window (~75% of the category must be used before a repeat)
 *  - "never twice for the same exam" tracking
 *  - 👍 / 👎 ratings and the user's own custom messages
 */
object MessageBank {

    data class Line(val id: String, val category: String, val style: Char, val text: String)

    /** One weighted category candidate. */
    data class Choice(val category: String, val weight: Double = 1.0)

    @Volatile
    private var cache: List<Line>? = null

    fun lines(context: Context): List<Line> = cache ?: synchronized(this) {
        cache ?: context.assets.open("messages.txt").bufferedReader().useLines { seq ->
            seq.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { raw ->
                    val parts = raw.split('|', limit = 3)
                    if (parts.size < 3) null
                    else Line(
                        id = "b" + parts[2].hashCode().toString(16),
                        category = parts[0].trim(),
                        style = parts[1].trim().firstOrNull() ?: 'f',
                        text = parts[2].trim()
                    )
                }.toList()
        }.also { cache = it }
    }

    fun count(context: Context): Int = lines(context).size

    private val RECENT_PREFIX = "r_"
    private val EXAM_PREFIX = "e_"
    private val LOG = stringPreferencesKey("log")
    private val LIKED = stringSetPreferencesKey("liked")
    private val DISLIKED = stringSetPreferencesKey("disliked")
    private const val SEP = "\u001F"
    private const val REC = "\u001E"

    private fun allowedStyles(style: MessageStyle): List<Set<Char>> = when (style) {
        MessageStyle.MIX, MessageStyle.PLAIN -> listOf(setOf('f', 's', 'm'))
        MessageStyle.SAVAGE -> listOf(setOf('s'), setOf('f'))
        MessageStyle.MOTIVATIONAL -> listOf(setOf('m'), setOf('f'))
        MessageStyle.TENGLISH -> listOf(setOf('t'), setOf('f', 'm'))
        MessageStyle.HINGLISH -> listOf(setOf('h'), setOf('f', 'm'))
    }

    /**
     * Picks a message. [group] = CLASS / EXAM / WEEKEND / SUMMARY selects custom messages and the
     * Tenglish/Hinglish "<group>_ANY" pools. [scope] (e.g. "exam42") prevents repeats for that item.
     * Returns null when the style is PLAIN or nothing matches (callers then use plain text).
     */
    suspend fun pick(
        context: Context,
        choices: List<Choice>,
        style: MessageStyle,
        group: String,
        scope: String? = null
    ): Line? {
        if (style == MessageStyle.PLAIN) return null
        val all = lines(context)
        val prefs = context.historyStore.data.first()
        val disliked = prefs[DISLIKED] ?: emptySet()
        val liked = prefs[LIKED] ?: emptySet()

        // Tenglish / Hinglish: mostly from the "<group>_ANY" pool in that language.
        val langStyle = when (style) { MessageStyle.TENGLISH -> 't'; MessageStyle.HINGLISH -> 'h'; else -> null }
        val langPool = if (langStyle != null) all.filter { it.category == "${group}_ANY" && it.style == langStyle } else emptyList()

        val available = choices.filter { c -> all.any { it.category == c.category } }
        val chosenCategory: String? = if (available.isEmpty()) null else {
            val total = available.sumOf { it.weight }
            var r = Random.nextDouble() * total
            available.firstOrNull { c -> r -= c.weight; r <= 0 }?.category ?: available.last().category
        }

        var pool: List<Line> = when {
            langPool.isNotEmpty() && (chosenCategory == null || Random.nextDouble() < 0.7) -> langPool
            chosenCategory == null -> emptyList()
            else -> {
                val inCategory = all.filter { it.category == chosenCategory }
                allowedStyles(style).asSequence()
                    .map { styles -> inCategory.filter { it.style in styles } }
                    .firstOrNull { it.isNotEmpty() } ?: inCategory
            }
        }

        // User's own messages join the pool.
        val custom = AppDatabase.get(context).customMessageDao().getAll()
            .filter { it.category == group }
            .map { Line("c${it.id}", group, 'f', it.text) }
        if (custom.isNotEmpty() && (pool.isEmpty() || Random.nextDouble() < 0.35)) pool = pool + custom

        pool = pool.filter { it.id !in disliked }
        if (pool.isEmpty()) return null

        val categoryKey = stringPreferencesKey(RECENT_PREFIX + (chosenCategory ?: group))
        val recent = prefs[categoryKey].orEmpty().split(',').filter { it.isNotBlank() }
        val window = (pool.size * 0.75).toInt().coerceIn(1, 60)
        val usedForScope = scope?.let { prefs[stringSetPreferencesKey(EXAM_PREFIX + it)] } ?: emptySet()

        var candidates = pool.filter { it.id !in recent.take(window) && it.id !in usedForScope }
        if (candidates.isEmpty()) candidates = pool.filter { it.id != recent.firstOrNull() && it.id !in usedForScope }
        if (candidates.isEmpty()) candidates = pool
        // Liked messages count twice.
        val weighted = candidates + candidates.filter { it.id in liked }
        val pick = weighted.random()

        context.historyStore.edit { p ->
            p[categoryKey] = (listOf(pick.id) + recent.filter { it != pick.id }).take(60).joinToString(",")
            if (scope != null) {
                val key = stringSetPreferencesKey(EXAM_PREFIX + scope)
                p[key] = (p[key] ?: emptySet()) + pick.id
            }
        }
        return pick
    }

    /** Replaces {placeholders}; unknown/empty ones are removed cleanly. */
    fun render(text: String, vars: Map<String, String>): String {
        var out = text
        vars.forEach { (k, v) -> out = out.replace("{$k}", v) }
        return out.replace(Regex("""\{[a-z_]+\}"""), "")
            .replace(Regex("""\(\s*\)"""), "")
            .replace(Regex(""" {2,}"""), " ")
            .trim()
    }

    /** Stores what was shown so the user can rate it later in Settings. */
    suspend fun log(context: Context, id: String, shownText: String) {
        context.historyStore.edit { p ->
            val entries = p[LOG].orEmpty().split(REC).filter { it.isNotBlank() }
            val updated = (listOf(id + SEP + shownText) + entries.filterNot { it.startsWith(id + SEP) }).take(25)
            p[LOG] = updated.joinToString(REC)
        }
    }

    data class Logged(val id: String, val text: String, val rating: Int)

    suspend fun recent(context: Context): List<Logged> {
        val p = context.historyStore.data.first()
        val liked = p[LIKED] ?: emptySet()
        val disliked = p[DISLIKED] ?: emptySet()
        return p[LOG].orEmpty().split(REC).filter { it.isNotBlank() }.mapNotNull { e ->
            val parts = e.split(SEP, limit = 2)
            if (parts.size != 2) null
            else Logged(parts[0], parts[1], if (parts[0] in liked) 1 else if (parts[0] in disliked) -1 else 0)
        }
    }

    /** rating: 1 = 👍, -1 = 👎, 0 = clear. */
    suspend fun rate(context: Context, id: String, rating: Int) {
        context.historyStore.edit { p ->
            val liked = (p[LIKED] ?: emptySet()) - id
            val disliked = (p[DISLIKED] ?: emptySet()) - id
            p[LIKED] = if (rating == 1) liked + id else liked
            p[DISLIKED] = if (rating == -1) disliked + id else disliked
        }
    }
}
