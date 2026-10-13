package com.local.stardbzzz.scan

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/** The fields of a local achievement the scan compares against. */
data class ScanCandidate(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val isCompleted: Boolean,
)

/** One achievement row as seen in the game, with the sidebar category it was listed under. */
data class ObservedRow(
    val tab: String,
    val category: String,
    val name: String,
    val description: String,
    val status: RowStatus,
)

data class ScanDiff(
    val id: String,
    val name: String,
    val category: String,
    /** true: completed in game but not locally; false: completed locally but in progress in game. */
    val markCompleted: Boolean,
    val gameStatus: String,
)

data class ScanReport(
    val gameCompletedCount: Int?,
    val localCompletedCount: Int,
    val observedCount: Int,
    val matchedCount: Int,
    val unmatched: List<ObservedRow>,
    val diffs: List<ScanDiff>,
)

/**
 * Fuzzy-matches OCR'd achievement names to local achievements.
 * OCR mostly gets names right but drops or swaps the odd character and mangles 「」『』 brackets,
 * so names are compared after stripping punctuation, with an edit-distance ratio.
 */
class AchievementMatcher(private val candidates: List<ScanCandidate>) {
    private val prepared = candidates.map { candidate ->
        PreparedCandidate(
            candidate = candidate,
            names = nameVariants(candidate.name).map(::normalizeText).distinct(),
            description = normalizeText(candidate.description).take(DescriptionPrefix),
            category = normalizeText(candidate.category),
        )
    }
    private val categories = prepared.map { it.category }.filter { it.isNotEmpty() }.distinct()

    fun match(name: String, description: String, categoryHint: String?): ScanCandidate? {
        val normalizedName = normalizeText(name)
        if (normalizedName.isEmpty()) return null
        val normalizedDescription = normalizeText(description).take(DescriptionPrefix)

        val category = categoryHint?.let(::normalizeText)?.let { hint ->
            categories.maxByOrNull { similarity(it, hint) }?.takeIf { similarity(it, hint) >= CategoryThreshold }
        }
        val inCategory = category?.let { wanted -> prepared.filter { it.category == wanted } }.orEmpty()
        return bestOf(inCategory, normalizedName, normalizedDescription)
            ?: bestOf(prepared, normalizedName, normalizedDescription)
    }

    private fun bestOf(pool: List<PreparedCandidate>, name: String, description: String): ScanCandidate? {
        if (pool.isEmpty()) return null
        val scored = pool.map { candidate -> candidate to candidate.names.maxOf { similarity(it, name) } }
        val bestScore = scored.maxOf { it.second }
        if (bestScore < NameThreshold) return null
        val tied = scored.filter { it.second >= bestScore - TieMargin }.map { it.first }
        if (tied.size == 1) return tied.single().candidate

        // Same or near-same names are told apart by description; if the read can't tell them apart,
        // leave the row unmatched rather than guess and sync the wrong achievement.
        val byDescription = tied
            .map { it to similarity(it.description, description) }
            .sortedByDescending { it.second }
        val (best, bestDescriptionScore) = byDescription.first()
        // Records with the same description are the same achievement listed twice; either one works.
        val rival = byDescription.firstOrNull { it.first.description != best.description } ?: return best.candidate
        if (description.isEmpty() || bestDescriptionScore - rival.second < DescriptionMargin) return null
        return best.candidate
    }

    private data class PreparedCandidate(
        val candidate: ScanCandidate,
        val names: List<String>,
        val description: String,
        val category: String,
    )

    companion object {
        private const val NameThreshold = 0.75
        private const val CategoryThreshold = 0.6
        private const val TieMargin = 0.02
        private const val DescriptionMargin = 0.15
        private const val DescriptionPrefix = 40
        private val GenderVariantRegex = Regex("""\{M#([^{}]*)\}\{F#([^{}]*)\}""", RegexOption.IGNORE_CASE)

        /** NFKC folds full-width forms; everything except letters and digits (brackets, spaces, punctuation) is dropped. */
        fun normalizeText(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFKC)
                .lowercase()
                .filter { it.isLetterOrDigit() }

        /** stardb keeps both protagonist variants ({M#..}{F#..}); the game shows one of them. */
        fun nameVariants(name: String): List<String> {
            if (!GenderVariantRegex.containsMatchIn(name)) return listOf(name)
            return listOf(
                name.replace(GenderVariantRegex) { it.groupValues[1] },
                name.replace(GenderVariantRegex) { it.groupValues[2] },
            )
        }

        fun similarity(a: String, b: String): Double {
            if (a == b) return 1.0
            val longest = max(a.length, b.length)
            if (longest == 0) return 1.0
            return 1.0 - levenshtein(a, b).toDouble() / longest
        }

        private fun levenshtein(a: String, b: String): Int {
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                current[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost)
                }
                val swap = previous
                previous = current
                current = swap
            }
            return previous[b.length]
        }
    }
}

object ScanReportBuilder {
    fun build(
        observed: List<ObservedRow>,
        candidates: List<ScanCandidate>,
        gameCompletedCount: Int?,
    ): ScanReport {
        val matcher = AchievementMatcher(candidates)
        // The same row is usually captured on two consecutive pages. Match every reading first and merge by
        // the matched achievement, so same-name achievements told apart by description stay separate.
        val unmatched = linkedMapOf<Triple<String, String, String>, ObservedRow>()
        val statusById = linkedMapOf<String, Pair<ScanCandidate, ObservedRow>>()
        observed.forEach { row ->
            val candidate = matcher.match(row.name, row.description, row.category)
            if (candidate == null) {
                val key = Triple(row.tab, AchievementMatcher.normalizeText(row.category), AchievementMatcher.normalizeText(row.name))
                unmatched.putIfAbsent(key, row)
                return@forEach
            }
            val existing = statusById[candidate.id]
            if (existing == null || (existing.second.status !is RowStatus.Completed && row.status is RowStatus.Completed)) {
                statusById[candidate.id] = candidate to row
            }
        }

        val diffs = statusById.values.mapNotNull { (candidate, row) ->
            val completedInGame = row.status is RowStatus.Completed
            if (completedInGame == candidate.isCompleted) return@mapNotNull null
            ScanDiff(
                id = candidate.id,
                name = candidate.name,
                category = candidate.category,
                markCompleted = completedInGame,
                gameStatus = row.status.label(),
            )
        }

        return ScanReport(
            gameCompletedCount = gameCompletedCount,
            localCompletedCount = candidates.count { it.isCompleted },
            observedCount = statusById.size + unmatched.size,
            matchedCount = statusById.size,
            unmatched = unmatched.values.toList(),
            diffs = diffs.sortedByDescending { it.markCompleted },
        )
    }

    private fun RowStatus.label(): String = when (this) {
        is RowStatus.Completed -> date?.let { "已完成（$it）" } ?: "已完成"
        is RowStatus.InProgress -> "进行中 $current/$target"
    }
}
