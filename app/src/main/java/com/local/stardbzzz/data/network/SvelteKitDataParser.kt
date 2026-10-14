package com.local.stardbzzz.data.network

import org.json.JSONArray
import org.json.JSONObject

fun parseAchievementTrackerData(raw: String): List<AchievementDto> {
    if (raw.trimStart().startsWith("<")) {
        return parseAchievementTrackerHtml(raw)
    }

    val json = raw
    val root = JSONObject(json)
    val nodes = root.getJSONArray("nodes")

    for (index in 0 until nodes.length()) {
        val node = nodes.optJSONObject(index) ?: continue
        if (node.optString("type") != "data") continue

        val data = node.optJSONArray("data") ?: continue
        val resolver = SvelteKitDataResolver(data)
        val rootData = resolver.resolveAt(0) as? Map<*, *> ?: continue
        val load = rootData["load"] as? Map<*, *> ?: continue
        return load.toAchievements()
    }

    return emptyList()
}

private fun parseAchievementTrackerHtml(html: String): List<AchievementDto> {
    val loadStart = html.indexOf("load:{achievement_count:")
    if (loadStart == -1) return emptyList()

    val objectStart = html.indexOf('{', loadStart)
    if (objectStart == -1) return emptyList()

    val objectEnd = findBalancedObjectEnd(html, objectStart)
    if (objectEnd == -1) return emptyList()

    val loadObject = html.substring(objectStart, objectEnd + 1).quoteJavaScriptObjectKeys()
    return JSONObject(loadObject).toAchievements()
}

private fun findBalancedObjectEnd(text: String, start: Int): Int {
    var depth = 0
    var inString = false
    var escaped = false

    for (index in start until text.length) {
        val char = text[index]
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '"') {
                inString = false
            }
            continue
        }

        when (char) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return index
            }
        }
    }

    return -1
}

private fun String.quoteJavaScriptObjectKeys(): String =
    replace(Regex("([,{])([A-Za-z_][A-Za-z0-9_]*):")) { match ->
        "${match.groupValues[1]}\"${match.groupValues[2]}\":"
    }.replace(Regex("([:\\[,])-?\\.(\\d+)")) { match ->
        val sign = if (match.value.contains("-.")) "-" else ""
        "${match.groupValues[1]}${sign}0.${match.groupValues[2]}"
    }

private class SvelteKitDataResolver(private val data: JSONArray) {
    private val cache = mutableMapOf<Int, Any?>()

    fun resolveAt(index: Int): Any? {
        if (index < 0 || index >= data.length()) return null
        if (cache.containsKey(index)) return cache[index]
        val resolved = resolveValue(data.get(index))
        cache[index] = resolved
        return resolved
    }

    private fun resolveValue(value: Any?): Any? {
        return when (value) {
            JSONObject.NULL, null -> null
            is JSONObject -> value.toMap()
            is JSONArray -> value.toList()
            else -> value
        }
    }

    private fun JSONObject.toMap(): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        keys().forEach { key ->
            result[key] = resolveReferenceOrValue(get(key))
        }
        return result
    }

    private fun JSONArray.toList(): List<Any?> {
        return List(length()) { index -> resolveReferenceOrValue(get(index)) }
    }

    private fun resolveReferenceOrValue(value: Any?): Any? {
        val reference = (value as? Number)
            ?.toInt()
            ?.takeIf { value.toDouble() == it.toDouble() && it >= 0 && it < data.length() }
        return if (reference != null) {
            resolveAt(reference)
        } else {
            resolveValue(value)
        }
    }
}

private fun Map<*, *>.toAchievements(): List<AchievementDto> {
    val series = this["series"] as? List<*> ?: return emptyList()
    var order = 0
    return series.flatMap { seriesItem ->
        val seriesMap = seriesItem as? Map<*, *> ?: return@flatMap emptyList()
        val primaryCategory = seriesMap.displayName()
        val groups = seriesMap["achievement_groups"] as? List<*> ?: emptyList<Any?>()
        groups.flatMap { groupItem ->
            val group = groupItem as? Map<*, *> ?: return@flatMap emptyList()
            val completeId = group["complete"]?.toString()
            val groupName = group.displayName()
            val achievements = group["achievements"] as? List<*> ?: emptyList<Any?>()
            achievements.mapNotNull { achievementItem ->
                val achievement = achievementItem as? Map<*, *> ?: return@mapNotNull null
                if (achievement["impossible"] == true) return@mapNotNull null
                val id = achievement["id"]?.toString() ?: return@mapNotNull null
                val viaGroup = completeId != null && achievements.size > 1 && !sameId(completeId, id)
                AchievementDto(
                    id = id,
                    name = achievement["name"]?.toString().orEmpty(),
                    description = achievement["description"]?.toString().orEmpty(),
                    primaryCategory = primaryCategory,
                    category = achievement["series_name"]?.toString().orEmpty().ifBlank { groupName },
                    reward = achievement["currency"]?.toString().orEmpty(),
                    version = achievement["version"]?.toString().orEmpty(),
                    displayOrder = order++,
                    // A group lists alternative achievements (story branches); stardb records the one completed.
                    completed = completeId != null && sameId(completeId, id),
                    completedViaGroup = viaGroup,
                )
            }
        }
    }.distinctBy { it.id }
}

private fun JSONObject.toAchievements(): List<AchievementDto> {
    val series = optJSONArray("series") ?: return emptyList()
    var order = 0
    return (0 until series.length()).flatMap { seriesIndex ->
        val seriesItem = series.optJSONObject(seriesIndex) ?: return@flatMap emptyList()
        val primaryCategory = seriesItem.displayName()
        val groups = seriesItem.optJSONArray("achievement_groups") ?: return@flatMap emptyList()
        (0 until groups.length()).flatMap { groupIndex ->
            val group = groups.optJSONObject(groupIndex) ?: return@flatMap emptyList()
            val completeId = group.opt("complete")?.takeUnless { it == JSONObject.NULL }?.toString()
            val groupName = group.displayName()
            val achievements = group.optJSONArray("achievements") ?: return@flatMap emptyList()
            (0 until achievements.length()).mapNotNull { achievementIndex ->
                val achievement = achievements.optJSONObject(achievementIndex) ?: return@mapNotNull null
                if (achievement.optBoolean("impossible", false)) return@mapNotNull null
                val id = achievement.opt("id")?.toString() ?: return@mapNotNull null
                val viaGroup = completeId != null && achievements.length() > 1 && !sameId(completeId, id)
                AchievementDto(
                    id = id,
                    name = achievement.optString("name"),
                    description = achievement.optString("description"),
                    primaryCategory = primaryCategory,
                    category = achievement.optString("series_name").ifBlank { groupName },
                    reward = achievement.opt("currency")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty(),
                    version = achievement.optString("version"),
                    displayOrder = order++,
                    // A group lists alternative achievements (story branches); stardb records the one completed.
                    completed = completeId != null && sameId(completeId, id),
                    completedViaGroup = viaGroup,
                )
            }
        }
    }.distinctBy { it.id }
}

/** Ids can come back as 4040123 or 4040123.0 depending on how the number was decoded. */
private fun sameId(a: String, b: String): Boolean =
    a == b || (a.toDoubleOrNull() != null && a.toDoubleOrNull() == b.toDoubleOrNull())

private fun Map<*, *>.displayName(): String =
    listOf("name", "title", "label", "series_name")
        .firstNotNullOfOrNull { key -> this[key]?.toString()?.takeIf { it.isNotBlank() } }
        .orEmpty()

private fun JSONObject.displayName(): String =
    listOf("name", "title", "label", "series_name")
        .firstNotNullOfOrNull { key -> optString(key).takeIf { it.isNotBlank() } }
        .orEmpty()
