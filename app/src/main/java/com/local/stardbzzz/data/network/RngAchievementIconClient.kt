package com.local.stardbzzz.data.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

data class RngAchievementData(
    val iconTiers: Map<String, Int> = emptyMap(),
    val descriptions: Map<String, String> = emptyMap(),
)

class RngAchievementIconClient(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("rng_moe_cache_v2", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    @Volatile
    private var cachedData: RngAchievementData? = null
    @Volatile
    private var cachedTrackerFingerprint: String? = null
    @Volatile
    private var cachedDeploymentFingerprint: String? = null

    suspend fun fetchAchievementData(expectedDescriptions: Map<String, String>): RngAchievementData = withContext(Dispatchers.IO) {
        val expectedIds = expectedDescriptions.keys
        val trackerFingerprint = expectedIds.sorted().joinToString(",").hashCode().toString()
        val deploymentFingerprint = fetchText(DeploymentVersionUrl)?.trim()?.takeIf { it.isNotBlank() }
        cachedData
            ?.takeIf {
                cachedTrackerFingerprint == trackerFingerprint &&
                    (deploymentFingerprint == null || cachedDeploymentFingerprint == deploymentFingerprint) &&
                    hasEnoughMatches(it, expectedIds) && hasExpectedLanguage(it, expectedDescriptions)
            }
            ?.let { return@withContext it }
        val knownUrl = preferences.getString(KnownMetadataKey, null)
        val knownFingerprint = preferences.getString(KnownTrackerFingerprintKey, null)
        val knownDeploymentFingerprint = preferences.getString(KnownDeploymentFingerprintKey, null)
        if (
            !knownUrl.isNullOrBlank() &&
            knownFingerprint == trackerFingerprint &&
            (deploymentFingerprint == null || knownDeploymentFingerprint == deploymentFingerprint)
        ) {
            val known = fetchText(knownUrl)?.let(::parseAchievementData) ?: RngAchievementData()
            if (hasEnoughMatches(known, expectedIds) && hasExpectedLanguage(known, expectedDescriptions)) {
                cachedData = known
                cachedTrackerFingerprint = trackerFingerprint
                cachedDeploymentFingerprint = deploymentFingerprint ?: knownDeploymentFingerprint
                return@withContext known
            }
        }
        val discovered = runCatching {
            val page = fetchText(BaseUrl).orEmpty()
            var parsed = parseAchievementData(page)
            val pending = ArrayDeque(parseChunkUrls(page, BaseUrl))
            val visited = mutableSetOf<String>()
            var metadataUrl: String? = null
            var bestMatchCount = countMatches(parsed, expectedIds)
            var bestDescriptionMatchCount = countDescriptionMatches(parsed, expectedDescriptions)
            while (pending.isNotEmpty() && visited.size < MaxChunks) {
                val url = pending.removeFirst()
                if (!visited.add(url)) continue
                val source = fetchText(url) ?: continue
                val sourceData = parseAchievementData(source)
                val matchCount = countMatches(sourceData, expectedIds)
                val descriptionMatchCount = countDescriptionMatches(sourceData, expectedDescriptions)
                if (matchCount > bestMatchCount ||
                    (matchCount == bestMatchCount && descriptionMatchCount > bestDescriptionMatchCount)
                ) {
                    parsed = sourceData
                    metadataUrl = url
                    bestMatchCount = matchCount
                    bestDescriptionMatchCount = descriptionMatchCount
                }
                if (hasEnoughMatches(sourceData, expectedIds) && hasExpectedLanguage(sourceData, expectedDescriptions)) {
                    parsed = sourceData
                    metadataUrl = url
                    break
                }
                parseChunkUrls(source, url).forEach { child ->
                    if (child !in visited) pending.addLast(child)
                }
            }

            parsed to metadataUrl
        }.getOrDefault(RngAchievementData() to null)
        val loaded = discovered.first
        discovered.second?.takeIf {
            hasEnoughMatches(loaded, expectedIds) && hasExpectedLanguage(loaded, expectedDescriptions)
        }?.let { url ->
            preferences.edit()
                .putString(KnownMetadataKey, url)
                .putString(KnownTrackerFingerprintKey, trackerFingerprint)
                .putString(KnownDeploymentFingerprintKey, deploymentFingerprint)
                .apply()
        }
        if (loaded.iconTiers.isNotEmpty()) {
            cachedData = loaded
            cachedTrackerFingerprint = trackerFingerprint
            cachedDeploymentFingerprint = deploymentFingerprint
        }
        loaded
    }

    private fun fetchText(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .header("User-Agent", BrowserUserAgent)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        }
    }

    private fun parseChunkUrls(page: String, baseUrl: String): List<String> {
        val urls = linkedSetOf<String>()
        val attributeRegex = Regex("""(?:src|href)=["']([^"']+\.js(?:\?[^"']*)?)["']""")
        val referenceRegex = Regex("""["']([^"']+\.js(?:\?[^"']*)?)["']""")
        val chunkRegex = Regex("""/(?:_out|_app)/immutable/[^"'\s]+\.js(?:\?[^"'\s]*)?""")
        (attributeRegex.findAll(page).map { it.groupValues[1] } + referenceRegex.findAll(page).map { it.groupValues[1] } + chunkRegex.findAll(page).map { it.value })
            .mapNotNull { url -> runCatching { URI(baseUrl).resolve(url).toString() }.getOrNull() }
            .filter { url -> "/immutable/" in url }
            .forEach(urls::add)
        return urls.toList()
    }

    private fun parseAchievementData(source: String): RngAchievementData {
        val tiers = mutableMapOf<String, Int>()
        val descriptions = mutableMapOf<String, String>()
        val objectRegex = Regex("""\{(?:[^{}]|\{[^{}]*\})*\}""")
        val idRegex = Regex("""["']?\bid\b["']?\s*:\s*["']?(\d+(?:e\d+)?)["']?""", RegexOption.IGNORE_CASE)
        val iconRegex = Regex("""["']?\bicon\b["']?\s*:\s*["']?([123])["']?""", RegexOption.IGNORE_CASE)
        val rarityRegex = Regex("""["']?\brarity\b["']?\s*:\s*["']?([123])["']?""", RegexOption.IGNORE_CASE)
        val descriptionRegex = Regex("""\bdesc\s*:\s*"((?:\\.|[^"\\])*)""", RegexOption.IGNORE_CASE)
        objectRegex.findAll(source).forEach { match ->
            val block = match.value
            val id = idRegex.find(block)?.groupValues?.getOrNull(1)?.normalizeJsNumberId() ?: return@forEach
            val icon = (iconRegex.find(block) ?: rarityRegex.find(block))
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@forEach
            tiers[id] = icon
            descriptionRegex.find(block)?.groupValues?.getOrNull(1)?.decodeJsString()?.let { descriptions[id] = it }
        }
        return RngAchievementData(iconTiers = tiers, descriptions = descriptions)
    }

    private fun String.decodeJsString(): String? =
        runCatching { JSONObject("""{"value":"$this"}""").getString("value") }.getOrNull()

    private fun hasEnoughMatches(data: RngAchievementData, expectedIds: Set<String>): Boolean {
        if (expectedIds.isEmpty()) return false
        val required = if (expectedIds.size <= MaximumAllowedMissing) {
            expectedIds.size
        } else {
            expectedIds.size - MaximumAllowedMissing
        }
        return countMatches(data, expectedIds) >= required
    }

    private fun countMatches(data: RngAchievementData, expectedIds: Set<String>): Int =
        expectedIds.count { it in data.iconTiers }

    private fun hasExpectedLanguage(
        data: RngAchievementData,
        expectedDescriptions: Map<String, String>,
    ): Boolean = countDescriptionMatches(data, expectedDescriptions) >= MinimumMatchingDescriptions

    private fun countDescriptionMatches(
        data: RngAchievementData,
        expectedDescriptions: Map<String, String>,
    ): Int = expectedDescriptions.count { (id, description) ->
        description.isNotBlank() && data.descriptions[id] == description
    }

    private fun String.normalizeJsNumberId(): String? {
        val value = lowercase()
        if (!value.contains("e")) return value
        val parts = value.split("e", limit = 2)
        val base = parts.getOrNull(0)?.takeIf { it.all(Char::isDigit) } ?: return null
        val exponent = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return base + "0".repeat(exponent)
    }

    private companion object {
        const val BaseUrl = "https://zzz.rng.moe/"
        const val MaxChunks = 300
        const val MaximumAllowedMissing = 10
        const val KnownMetadataKey = "known_metadata_url"
        const val KnownTrackerFingerprintKey = "known_tracker_fingerprint"
        const val KnownDeploymentFingerprintKey = "known_deployment_fingerprint"
        const val DeploymentVersionUrl = "https://zzz.rng.moe/deployment.log"
        const val MinimumMatchingDescriptions = 50
        const val BrowserUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    }
}
