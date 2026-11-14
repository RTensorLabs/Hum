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
        val names = titleCandidates(track.title, track.artist)
        val known = normalize("${track.artist} ${track.title}")
        var failure: java.io.IOException? = null
        fun fetch(path: String, query: (okhttp3.HttpUrl.Builder) -> Unit): Any? =
            try { get(path, query) } catch (e: java.io.IOException) { failure = e; null }
        fun search(query: (okhttp3.HttpUrl.Builder) -> Unit): List<LyricLine> {
            val results = fetch("search", query) as? JSONArray ?: return emptyList()
            val best = pick((0 until results.length()).map { results.getJSONObject(it) }, names, known, seconds)
            return parse(best?.optString("syncedLyrics").orEmpty())
        }
        // An exact match on title, artist and length first. Then searches: by title and artist as given, then by each
        // likely song name taken from the title with the artist it names. A YouTube upload titled
        // "ARJN x KDS - KALYANI [ Official Lyrical Video ]" by "MONEY VERSE RECORDS" is searched as "KALYANI ARJN".
        // Every search result must carry one of those song names and, without a close length, an artist the track names,
        // so a different song by the same artist is never shown. The length picks the right cut of the song.
        val exact = fetch("get") { url -> url.addQueryParameter("track_name", title).addQueryParameter("artist_name", track.artist)
            .apply { if (seconds != null) addQueryParameter("duration", seconds.toString()) } }
        var found = parse((exact as? JSONObject)?.optString("syncedLyrics").orEmpty())
        if (found.isEmpty()) found = search { url -> url.addQueryParameter("track_name", title).addQueryParameter("artist_name", track.artist) }
        val queries = names.flatMap { (name, artist) -> listOf("$name $artist".trim(), "$name ${leadArtist(track.artist)}".trim()) }.distinct().take(4)
        for (q in queries) { if (found.isNotEmpty()) break; found = search { url -> url.addQueryParameter("q", q) } }
        if (found.isEmpty()) found = names.firstOrNull()?.let { (name, _) -> search { url -> url.addQueryParameter("track_name", name) } }.orEmpty()
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
            .replace(Regex("""\s*[(\[]\s*(official|lyric|lyrics|lyrical|audio|video|music video|full|hd|4k|visuali[sz]er|remaster(ed)?)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
            .trim()

        /** The title without the film, language or version notes: "Monica (From "Coolie") (Tamil)" → "Monica". */
        internal fun coreTitle(title: String): String =
            title.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").substringBefore(" - ").trim().ifBlank { title }

        private val parts = Regex("""\s+[-–—]\s+|\s*[|｜•]\s*""")
        private val trailingNoise = Regex("""((^|\s+)(official|lyrical|lyrics?|music|video|audio|song|full|hd|4k|visuali[sz]er|edited))+$""", RegexOption.IGNORE_CASE)
        private val clearNoise = Regex("""\b(official|lyrical|lyrics?|video|audio|hd|4k|visuali[sz]er|edited)\b""", RegexOption.IGNORE_CASE)

        /** "Tum Hi Ho Full Video Song HD" → "Tum Hi Ho", while "Love Song" and "Pop Music" keep their names. */
        internal fun dropNoise(piece: String): String =
            trailingNoise.find(piece)?.takeIf { clearNoise.containsMatchIn(it.value) }?.let { piece.removeRange(it.range).trim() } ?: piece.trim()

        /**
         * The song names a title may hold, each with the artist the title names beside it, most likely first.
         * "ARJN x KDS - KALYANI [ Official Lyrical Video ]" → ("KALYANI", "ARJN"); "Monica (From "Coolie") (Tamil)" by
         * "Sublahshini & Vishnu Edavan" → ("Monica", "Sublahshini"). A part that reads as a list of artists ("A x B", "A, B")
         * or as the uploader's name comes last.
         */
        internal fun titleCandidates(title: String, artist: String): List<Pair<String, String>> {
            val pieces = cleanTitle(title).split(parts).map { piece ->
                piece.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").replace(Regex("""["“”]"""), "").let(::dropNoise)
            }.filter { it.isNotBlank() }
            if (pieces.isEmpty()) return emptyList()
            val uploader = normalize(artist)
            fun artistLike(piece: String): Boolean {
                val n = normalize(piece)
                return Regex("""\s(x|&)\s|,""", RegexOption.IGNORE_CASE).containsMatchIn(piece) || n.isNotEmpty() && uploader.isNotEmpty() && (n in uploader || uploader in n)
            }
            val ordered = pieces.withIndex().sortedBy { if (artistLike(it.value)) 1 else 0 }
            return ordered.take(3).map { (i, name) ->
                val beside = pieces.withIndex().firstOrNull { it.index != i && artistLike(it.value) }?.value ?: pieces.getOrNull(if (i == 0) 1 else 0)
                name to leadArtist(beside ?: artist)
            }.distinctBy { normalize(it.first) to normalize(it.second) }
        }

        /** Lowercase letters and digits only, words joined by single spaces. */
        internal fun normalize(text: String): String =
            text.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

        /**
         * The result to show, or null. It must be named like one of [names] ("KALYANI (Remix)" counts as "KALYANI").
         * A length within 4 s of the track wins; otherwise one within 10 s whose artist the track names; with no
         * known length, the first whose artist the track names.
         */
        internal fun pick(results: List<JSONObject>, names: List<Pair<String, String>>, known: String, seconds: Long?): JSONObject? {
            val wanted = names.map { normalize(it.first) }.filter { it.isNotEmpty() }
            fun named(o: JSONObject): Boolean {
                val n = normalize(coreTitle(o.optString("trackName")))
                return n.isNotEmpty() && wanted.any { it == n || (minOf(it.length, n.length) >= 4 && (it.startsWith(n) || n.startsWith(it))) }
            }
            fun byArtist(o: JSONObject): Boolean = o.optString("artistName").split(Regex("""\s*(,|&|;|\band\b|\bfeat\b\.?|\bft\b\.?|\bx\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE))
                .map { normalize(it) }.any { name ->
                    // The whole name, or a distinctive first name: "Anirudh" in a title names "Anirudh Ravichander".
                    listOf(name, name.substringBefore(' ').takeIf { ' ' in name && it.length >= 5 }).any { it != null && it.length >= 2 &&
                        Regex("""(^|\s)${Regex.escape(it)}(\s|$)""").containsMatchIn(known) } }
            val usable = results.filter { it.optString("syncedLyrics").isNotBlank() && named(it) }
            if (seconds == null) return usable.firstOrNull(::byArtist)
            fun gap(o: JSONObject) = kotlin.math.abs(o.optDouble("duration", 0.0) - seconds)
            return usable.filter { gap(it) <= 4 }.minByOrNull(::gap) ?: usable.filter { gap(it) <= 10 && byArtist(it) }.minByOrNull(::gap)
        }

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
