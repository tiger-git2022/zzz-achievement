package com.local.stardbzzz.data.update

/**
 * Release versions: "vX.Y.Z" (e.g. "v2.1.0"). Older releases were "v1.1.<CI run>" ("v1.1.66"), which read the same
 * way; an old-style "-t<N>" suffix compares after the numbers. 2.1.0 > 1.1.66, so the new releases come after all
 * old ones. See .github/workflows/release.yml.
 */
internal object AppVersion {
    private val Pattern = Regex("""^v?(\d+(?:\.\d+)*)(?:-t(\d+))?$""")

    /** The version's numbers, the "-t" number last (0 without one); null when [version] isn't a release version. */
    fun parse(version: String): List<Int>? {
        val match = Pattern.matchEntire(version.trim()) ?: return null
        val numbers = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
        val build = match.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: return null
        return numbers + build
    }

    /** Whether release [latest] is newer than the installed [current] version; unreadable [current] is older. */
    fun isNewer(latest: String, current: String): Boolean {
        val new = parse(latest) ?: return false
        val old = parse(current) ?: return true
        val newNumbers = new.dropLast(1)
        val oldNumbers = old.dropLast(1)
        for (i in 0 until maxOf(newNumbers.size, oldNumbers.size)) {
            val a = newNumbers.getOrElse(i) { 0 }
            val b = oldNumbers.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return new.last() > old.last()
    }
}
