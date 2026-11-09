package labs.rtensor.music.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** One lyric line and the moment it starts. */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * Time-synced lyrics from LRCLIB (lrclib.net), a free community library that needs no account.
 * Only the song's title, artist and length are sent. Songs it does not have simply come back empty
 * and the plain lyrics are shown instead.
 */
class SyncedLyricsSource(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build(),
    private val base: String = "https://lrclib.net/api",
) {
    /**
     * The song's synced lines, or empty when LRCLIB does not have it. Throws [java.io.IOException] when
     * the library could not be reached, so a failed lookup is retried instead of remembered as "none".
     */
    suspend fun find(track: Track): List<LyricLine> = withContext(Dispatchers.IO) {
        val title = cleanTitle(track.title)
        if (title.isBlank()) return@withContext emptyList()
        val seconds = (track.durationMs / 1000).takeIf { it > 0 }
        var failure: java.io.IOException? = null
        fun fetch(path: String, query: (okhttp3.HttpUrl.Builder) -> Unit): Any? =
            try { get(path, query) } catch (e: java.io.IOException) { failure = e; null }
        fun closest(results: JSONArray?): List<LyricLine> {
            val best = (0 until (results?.length() ?: 0)).map { results!!.getJSONObject(it) }
                .filter { it.optString("syncedLyrics").isNotBlank() && (seconds == null || kotlin.math.abs(it.optDouble("duration", 0.0) - seconds) <= 4) }
                .minByOrNull { if (seconds == null) 0.0 else kotlin.math.abs(it.optDouble("duration", 0.0) - seconds) }
            return parse(best?.optString("syncedLyrics").orEmpty())
        }
        // An exact match on title, artist and length first, then the closest search result, then a looser
        // search on the core title ("Monica" from "Monica (From "Coolie") (Tamil)") and the lead artist,
        // where the length picks the right version (the Tamil and Telugu cuts differ by half a minute).
        val exact = fetch("get") { url -> url.addQueryParameter("track_name", title).addQueryParameter("artist_name", track.artist)
            .apply { if (seconds != null) addQueryParameter("duration", seconds.toString()) } }
        val found = parse((exact as? JSONObject)?.optString("syncedLyrics").orEmpty())
            .ifEmpty { closest(fetch("search") { url -> url.addQueryParameter("track_name", title).addQueryParameter("artist_name", track.artist) } as? JSONArray) }
            .ifEmpty {
                if (seconds == null) return@ifEmpty emptyList()
                closest(fetch("search") { url -> url.addQueryParameter("q", "${coreTitle(title)} ${leadArtist(track.artist)}".trim()) } as? JSONArray)
            }
        if (found.isEmpty()) failure?.let { throw it }
        found
    }

    /** The response, or null when the library answered without it (not found). Throws when the library could not be reached. */
    private fun get(path: String, query: (okhttp3.HttpUrl.Builder) -> Unit): Any? {
        val url = "$base/$path".toHttpUrl().newBuilder().also(query).build()
        val request = Request.Builder().url(url).header("User-Agent", "Hum (https://github.com/RTensorLabs/Hum)").build()
        return client.newCall(request).execute().use { response ->
            if (response.code >= 500 || response.code == 429) throw java.io.IOException("LRCLIB answered ${response.code}")
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty().trim()
            try { if (body.startsWith("[")) JSONArray(body) else if (body.startsWith("{")) JSONObject(body) else null }
            catch (_: org.json.JSONException) { null }
        }
    }

    companion object {
        /** "Song (Official Video) [4K]" matches far more often as "Song". */
        internal fun cleanTitle(title: String): String = title
            .replace(Regex("""\s*[(\[](official|lyric|lyrics|audio|video|music video|hd|4k|visuali[sz]er|remaster(ed)?)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
            .trim()

        /** The title without the film, language or version notes: "Monica (From "Coolie") (Tamil)" → "Monica". */
        internal fun coreTitle(title: String): String =
            title.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").substringBefore(" - ").trim().ifBlank { title }

        /** The first artist of "A & B", "A, B" or "A feat. B". */
        internal fun leadArtist(artist: String): String =
            artist.split(Regex("""\s*(,|&|;|\band\b|\bfeat\b\.?|\bft\b\.?|\bx\b)\s*""", RegexOption.IGNORE_CASE)).first().trim()

        /** `[01:23.45] words` (several stamps allowed per line) → lines in time order; blank lines and tags dropped. */
        internal fun parse(lrc: String): List<LyricLine> {
            val stamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
            val lines = mutableListOf<LyricLine>()
            lrc.lineSequence().forEach { raw ->
                val stamps = stamp.findAll(raw).toList()
                if (stamps.isEmpty()) return@forEach
                val text = raw.substring(stamps.last().range.last + 1).trim()
                if (text.isEmpty()) return@forEach
                stamps.forEach { m ->
                    val fraction = m.groupValues[3]
                    val ms = when (fraction.length) { 0 -> 0L; 1 -> fraction.toLong() * 100; 2 -> fraction.toLong() * 10; else -> fraction.take(3).toLong() }
                    lines += LyricLine(m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1_000 + ms, text)
                }
            }
            return lines.sortedBy { it.timeMs }
        }

        /** Index of the line playing at [positionMs]: the last one that has started, or -1 before the first. */
        fun activeIndex(lines: List<LyricLine>, positionMs: Long): Int {
            var low = 0; var high = lines.lastIndex; var found = -1
            while (low <= high) { val mid = (low + high) ushr 1; if (lines[mid].timeMs <= positionMs) { found = mid; low = mid + 1 } else high = mid - 1 }
            return found
        }
    }
}
