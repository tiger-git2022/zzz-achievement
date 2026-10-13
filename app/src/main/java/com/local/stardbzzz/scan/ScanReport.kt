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
    /** stardb can list one achievement under several ids; records with the same name, category and version are one. */
    val version: String = "",
) {
    val linkKey: Triple<String, String, String> get() = Triple(name, category, version)
}

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
    /** The game's own 完成数 counter. */
    val gameCompletedCount: Int?,
    /** Achievements this scan saw as completed in the game; equals [gameCompletedCount] when nothing was missed. */
    val gameCompletedSeen: Int,
    /** Locally completed achievements (records listing the same achievement count once). */
    val localCompletedCount: Int,
    /** Locally completed records, which can exceed [localCompletedCount] when stardb lists one achievement twice. */
    val localCompletedRecords: Int,
    val observedCount: Int,
    val matchedCount: Int,
    val unmatched: List<ObservedRow>,
    /** Completed locally but not seen by this scan: missed by the scan, misread, or not in the game's list. */
    val localCompletedNotSeen: List<ScanCandidate>,
    val diffs: List<ScanDiff>,
) {
    /** The local completed count if every diff were applied. */
    val localCompletedAfterSync: Int
        get() = localCompletedCount + diffs.count { it.markCompleted } - diffs.count { !it.markCompleted }
}

/**
 * Fuzzy-matches OCR'd achievement names to local achievements.
 * OCR mostly gets names right but drops or swaps the odd character and mangles 「」『』 brackets,
 * so names are compared after stripping punctuation, with an edit-distance ratio.
 */
class AchievementMatcher(private val candidates: List<ScanCandidate>) {
    private val prepared = candidates.map { candidate ->
        val names = nameVariants(candidate.name).map(::normalizeText).distinct()
        PreparedCandidate(
            candidate = candidate,
            names = names,
            stems = names.map(::nameStem).distinct(),
            description = normalizeText(candidate.description).take(DescriptionPrefix),
            numbers = numbersIn(candidate.description),
            category = normalizeText(candidate.category),
        )
    }
    private val categories = prepared.map { it.category }.filter { it.isNotEmpty() }.distinct()

    fun match(name: String, description: String, categoryHint: String?): ScanCandidate? {
        val stem = nameStem(normalizeText(name))
        if (stem.isEmpty()) return null
        val observed = ObservedText(stem, normalizeText(description).take(DescriptionPrefix), numbersIn(description))

        val category = categoryHint?.let(::normalizeText)?.let { hint ->
            categories.maxByOrNull { similarity(it, hint) }?.takeIf { similarity(it, hint) >= CategoryThreshold }
        }
        val inCategory = category?.let { wanted -> prepared.filter { it.category == wanted } }.orEmpty()
        return bestOf(inCategory, observed) ?: bestOf(prepared, observed)
    }

    private fun bestOf(pool: List<PreparedCandidate>, observed: ObservedText): ScanCandidate? {
        if (pool.isEmpty()) return null
        // Names are compared without their tier mark (Ⅰ/Ⅱ/Ⅲ), which OCR reads as 1, =, |, 三 or !.
        val scored = pool.map { candidate -> candidate to candidate.stems.maxOf { similarity(it, observed.stem) } }
        val bestScore = scored.maxOf { it.second }
        if (bestScore < NameThreshold) return null
        val tied = scored.filter { it.second >= bestScore - TieMargin }.map { it.first }

        // Records with the same full name and description are one achievement listed twice; either one works.
        val groups = tied.groupBy { it.names to it.description }.values.toList()
        if (groups.size == 1) return groups.single().first().candidate

        // Tiers share a name and differ only in their description's numbers (10次 / 50次 / 200次).
        if (observed.numbers.isNotEmpty()) {
            groups.filter { it.first().numbers == observed.numbers }.singleOrNull()?.let { return it.first().candidate }
        }

        // Otherwise tell them apart by description; if the read can't, leave the row unmatched rather than
        // guess and sync the wrong achievement.
        val byDescription = groups
            .map { it.first() to similarity(it.first().description, observed.description) }
            .sortedByDescending { it.second }
        val (best, bestDescriptionScore) = byDescription[0]
        if (observed.description.isEmpty() || bestDescriptionScore - byDescription[1].second < DescriptionMargin) return null
        return best.candidate
    }

    private data class ObservedText(val stem: String, val description: String, val numbers: List<String>)

    private data class PreparedCandidate(
        val candidate: ScanCandidate,
        val names: List<String>,
        val stems: List<String>,
        val description: String,
        val numbers: List<String>,
        val category: String,
    )

    companion object {
        private const val NameThreshold = 0.75
        private const val CategoryThreshold = 0.6
        private const val TieMargin = 0.02
        private const val DescriptionMargin = 0.15
        private const val DescriptionPrefix = 40
        private val GenderVariantRegex = Regex("""\{M#([^{}]*)\}\{F#([^{}]*)\}""", RegexOption.IGNORE_CASE)

        /**
         * Characters ML Kit reads in place of the game's simplified Chinese: traditional or variant forms and
         * look-alikes seen on a real phone (採索 for 探索, 斷金製石 for 断金裂石, カ for 力). Both sides of every
         * comparison go through this, so mapping a real character too only matters if two achievements
         * differed by exactly that character.
         */
        private val OcrConfusables = mapOf(
            '採' to '探',
            '來' to '来',
            '樹' to '树',
            '製' to '裂',
            '購' to '购',
            '濱' to '滨',
            '慣' to '愤',
            'カ' to '力',
        )

        /**
         * NFKC folds full-width forms; known OCR look-alikes are mapped to what the game shows; everything except
         * letters and digits (brackets, spaces, punctuation) is dropped.
         */
        fun normalizeText(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFKC)
                .lowercase()
                .map { OcrConfusables[it] ?: it }
                .filter { it.isLetterOrDigit() }
                .joinToString("")

        /**
         * A normalized name without its trailing tier mark. Ⅰ/Ⅱ/Ⅲ normalize to i/ii/iii, and OCR reads them as
         * 1, l, 一, 三 (or =, |, ! which normalization already drops), so none of these can be trusted to pick
         * the tier; the description's numbers do that.
         */
        fun nameStem(normalizedName: String): String {
            val stem = normalizedName.trimEnd { it in TierMarks }
            return if (stem.length >= 2) stem else normalizedName
        }

        private const val TierMarks = "1ilvx一二三"
        private val NumberRegex = Regex("""\d+""")

        /** The numbers in a text, in order, without leading zeros. */
        fun numbersIn(text: String): List<String> =
            NumberRegex.findAll(Normalizer.normalize(text, Normalizer.Form.NFKC))
                .map { it.value.trimStart('0').ifEmpty { "0" } }
                .toList()

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

        fun levenshtein(a: String, b: String): Int {
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
        val unmatched = mutableListOf<ObservedRow>()
        val statusById = linkedMapOf<String, Pair<ScanCandidate, ObservedRow>>()
        observed.forEach { row ->
            val candidate = matcher.match(row.name, row.description, row.category)
            if (candidate == null) {
                // The same row read on another page has the same name and a similar description; a different
                // achievement with the same name has a different description and counts on its own.
                if (unmatched.none { sameUnmatchedRow(it, row) }) unmatched += row
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

        val seenLinks = statusById.values.map { it.first.linkKey }.toSet()
        val completedSeen = statusById.values
            .filter { it.second.status is RowStatus.Completed }
            .map { it.first.linkKey }
            .distinct().size + unmatched.count { it.status is RowStatus.Completed }
        val notSeen = candidates
            .filter { it.isCompleted && it.linkKey !in seenLinks }
            .distinctBy { it.linkKey }
            .sortedWith(compareBy({ it.category }, { it.name }))

        return ScanReport(
            gameCompletedCount = gameCompletedCount,
            gameCompletedSeen = completedSeen,
            localCompletedCount = completedAchievements(candidates),
            localCompletedRecords = candidates.count { it.isCompleted },
            observedCount = statusById.size + unmatched.size,
            matchedCount = statusById.size,
            unmatched = unmatched.toList(),
            localCompletedNotSeen = notSeen,
            diffs = diffs.sortedByDescending { it.markCompleted },
        )
    }

    private fun sameUnmatchedRow(a: ObservedRow, b: ObservedRow): Boolean {
        if (a.tab != b.tab || AchievementMatcher.normalizeText(a.category) != AchievementMatcher.normalizeText(b.category)) return false
        if (AchievementMatcher.normalizeText(a.name) != AchievementMatcher.normalizeText(b.name)) return false
        if (AchievementMatcher.numbersIn(a.description) != AchievementMatcher.numbersIn(b.description)) return false
        val descriptionA = AchievementMatcher.normalizeText(a.description)
        val descriptionB = AchievementMatcher.normalizeText(b.description)
        return descriptionA.isEmpty() || descriptionB.isEmpty() ||
            AchievementMatcher.similarity(descriptionA, descriptionB) >= SameDescription
    }

    /** OCR of one description varies by a character or two between frames; different achievements differ more. */
    private const val SameDescription = 0.7

    /** Completed achievements, counting records that list the same achievement once. */
    fun completedAchievements(candidates: List<ScanCandidate>): Int =
        candidates.filter { it.isCompleted }.map { it.linkKey }.distinct().size

    private fun RowStatus.label(): String = when (this) {
        is RowStatus.Completed -> date?.let { "已完成（$it）" } ?: "已完成"
        is RowStatus.InProgress -> "进行中 $current/$target"
    }
}
