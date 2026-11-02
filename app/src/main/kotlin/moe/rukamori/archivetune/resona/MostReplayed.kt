/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * "Jump to the drop" (Resona): YouTube's "Most replayed" heatmap of a video
 * (web `next` response, macroMarkersListEntity / heatMarkers) and the most
 * replayed point after the intro, minus 3 s. Same rules as Resona for iPad.
 */
data class Heatmap(val values: List<Double>, val durationSeconds: Double) {
    val dropTimeSeconds: Double?
        get() {
            if (values.size < 10 || durationSeconds <= 0) return null
            val from = (values.size * 0.06).toInt()
            val to = (values.size * 0.97).toInt()
            if (to <= from) return null
            var peak = from
            for (i in from until to) if (values[i] > values[peak]) peak = i
            val segment = durationSeconds / values.size
            return maxOf(0.0, peak * segment - 3)
        }
}

object MostReplayed {
    private const val Endpoint = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false"
    private const val ClientVersion = "2.20261002.01.00"
    private val cache = ConcurrentHashMap<String, Heatmap>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    suspend fun heatmap(videoId: String): Heatmap? {
        cache[videoId]?.let { return it }
        if (videoId in missing) return null
        val result = withContext(Dispatchers.IO) { runCatching { fetch(videoId) }.getOrNull() }
        if (result != null) cache[videoId] = result else missing += videoId
        return result
    }

    private fun fetch(videoId: String): Heatmap? {
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject().put("clientName", "WEB").put("clientVersion", ClientVersion).put("hl", "en").put("gl", "US")))
            .put("videoId", videoId)
        val connection = (URL(Endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            if (connection.responseCode != 200) return null
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return parse(JSONObject(text))
        } finally {
            connection.disconnect()
        }
    }

    /** Current format (macroMarkersListEntity) first, then the older heatMarkers. */
    fun parse(json: JSONObject): Heatmap? {
        (find(json, "macroMarkersListEntity") as? JSONObject)?.let { entity ->
            val list = entity.optJSONObject("markersList") ?: return@let
            if (list.optString("markerType") != "MARKER_TYPE_HEATMAP") return@let
            val markers = list.optJSONArray("markers") ?: return@let
            if (markers.length() == 0) return@let
            val values = (0 until markers.length()).map { markers.getJSONObject(it).optString("intensityScoreNormalized").toDoubleOrNull() ?: markers.getJSONObject(it).optDouble("intensityScoreNormalized", 0.0) }
            val last = markers.getJSONObject(markers.length() - 1)
            val end = ((last.optString("startMillis").toDoubleOrNull() ?: 0.0) + (last.optString("durationMillis").toDoubleOrNull() ?: 0.0)) / 1000
            return if (end > 0) Heatmap(values, end) else null
        }
        (find(json, "heatMarkers") as? JSONArray)?.let { array ->
            val renderers = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optJSONObject("heatMarkerRenderer") }
            if (renderers.isEmpty()) return null
            val values = renderers.map { it.optDouble("heatMarkerIntensityScoreNormalized", 0.0) }
            val last = renderers.last()
            val end = (last.optDouble("timeRangeStartMillis", 0.0) + last.optDouble("markerDurationMillis", 0.0)) / 1000
            return if (end > 0) Heatmap(values, end) else null
        }
        return null
    }

    private fun find(node: Any?, key: String): Any? {
        when (node) {
            is JSONObject -> {
                if (node.has(key)) return node.get(key)
                for (name in node.keys()) find(node.opt(name), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) find(node.opt(i), key)?.let { return it }
        }
        return null
    }
}
