package labs.rtensor.music

import labs.rtensor.music.data.SyncedLyricsSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncedLyricsMatchTest {
    private fun result(track: String, artist: String, seconds: Double, synced: Boolean = true) = JSONObject()
        .put("trackName", track).put("artistName", artist).put("duration", seconds).put("syncedLyrics", if (synced) "[00:01.00] line" else "")

    @Test fun `video titles give the song name and the artist beside it`() {
        assertEquals("KALYANI" to "ARJN",
            SyncedLyricsSource.titleCandidates("ARJN x KDS x FIFTY4 x RONN - KALYANI [ Official Lyrical Video ]", "MONEY VERSE RECORDS").first())
        assertEquals("KALYANI" to "ARJN",
            SyncedLyricsSource.titleCandidates("KALYANI - ARJN x KDS x FIFTY4 x RONN | What We Cookin?", "What We Cookin?").first())
        assertEquals("KALYANI" to "ARJN",
            SyncedLyricsSource.titleCandidates("KALYANI (with Shreya Ghoshal) OFFICIAL MUSIC VIDEO | ARJN | KDS | FIFTY4 | RONN |", "Universal Music India").first())
        assertEquals("Kalyani" to "Arjn",
            SyncedLyricsSource.titleCandidates("Kalyani (Lyrics Song) - Arjn x Kds x Fifty4 x Ronn", "LostWaves").first())
        assertEquals("Hello" to "Adele", SyncedLyricsSource.titleCandidates("Adele - Hello (Official Music Video)", "AdeleVEVO").first())
    }

    @Test fun `only clear video words are dropped from a name`() {
        assertEquals("Tum Hi Ho", SyncedLyricsSource.dropNoise("Tum Hi Ho Full Video Song HD"))
        assertEquals("", SyncedLyricsSource.dropNoise("Video Song"))
        assertEquals("Love Song", SyncedLyricsSource.dropNoise("Love Song"))
        assertEquals("Pop Music", SyncedLyricsSource.dropNoise("Pop Music"))
        assertEquals("Arabic Kuthu" to "Beast", SyncedLyricsSource.titleCandidates("Arabic Kuthu - Video Song | Beast | Anirudh", "Sun TV").first())
    }

    @Test fun `song titles keep their name and artist`() {
        assertEquals(listOf("Monica" to "Sublahshini"),
            SyncedLyricsSource.titleCandidates("Monica (From \"Coolie\") (Tamil)", "Sublahshini & Vishnu Edavan"))
        assertEquals(listOf("KALYANI" to "ARJN"), SyncedLyricsSource.titleCandidates("KALYANI (Remix)", "ARJN, KDS, FIFTY4 & Shreya Ghoshal"))
    }

    @Test fun `the closest length of the same song wins`() {
        val names = listOf("KALYANI" to "ARJN")
        val known = SyncedLyricsSource.normalize("MONEY VERSE RECORDS ARJN x KDS - KALYANI")
        val picked = SyncedLyricsSource.pick(listOf(result("KALYANI", "ARJN, KDS, FIFTY4, RONN", 105.0), result("KALYANI (Remix)", "ARJN", 269.0),
            result("KALYANI", "ARJN, KDS, FIFTY4, RONN", 235.0)), names, known, 237)
        assertEquals(235.0, picked!!.getDouble("duration"), 0.0)
    }

    @Test fun `another song or a far length is never picked`() {
        val names = listOf("KALYANI" to "ARJN")
        val known = SyncedLyricsSource.normalize("MONEY VERSE RECORDS ARJN x KDS - KALYANI")
        assertNull(SyncedLyricsSource.pick(listOf(result("NERA", "ARJN, KDS, RONN", 237.0)), names, known, 237))
        assertNull(SyncedLyricsSource.pick(listOf(result("KALYANI", "ARJN", 260.0)), names, known, 237))
        assertNull(SyncedLyricsSource.pick(listOf(result("KALYANI", "ARJN", 237.0, synced = false)), names, known, 237))
        // Within 10 s only when the artist is one the track names.
        assertEquals(245.0, SyncedLyricsSource.pick(listOf(result("KALYANI", "ARJN", 245.0)), names, known, 237)!!.getDouble("duration"), 0.0)
        assertNull(SyncedLyricsSource.pick(listOf(result("Kalyani", "Tharindu Damsara", 245.0)), names, known, 237))
    }

    @Test fun `without a length the artist decides`() {
        val names = listOf("Kalyani" to "ARJN")
        val known = SyncedLyricsSource.normalize("ARJN Kalyani")
        val picked = SyncedLyricsSource.pick(listOf(result("Kalyani", "Tharindu Damsara", 206.0), result("KALYANI", "ARJN, KDS", 235.0)), names, known, null)
        assertEquals("ARJN, KDS", picked!!.getString("artistName"))
    }
}
