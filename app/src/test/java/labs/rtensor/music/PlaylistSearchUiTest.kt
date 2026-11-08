package labs.rtensor.music

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import labs.rtensor.music.data.*
import labs.rtensor.music.ui.MusicApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Searching also finds playlists: a row of covers above the songs that opens like a playlist on Home. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaylistSearchUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val song = Track("aaaaaaaaaaa", "Afterimage", "Serein", "https://music.youtube.com/watch?v=aaaaaaaaaaa", durationMs = 200_000)
    private val playlist = FeedEntry("VLPL111111111111", "Tamil Hits", "Hum Radio · 48 songs", "", "playlist", "VLPL111111111111", "")

    private fun state(results: List<Track>, playlists: List<FeedEntry>) = MusicState(look = LookChoice.ROOM, query = "tamil", searched = true,
        results = results, playlistResults = playlists, reducedMotion = true)

    @Test fun aMatchingPlaylistShowsAboveTheSongsAndOpensAsAPlaylist() {
        var opened: FeedEntry? = null
        compose.setContent { MusicApp(state(listOf(song), listOf(playlist)), onBrowse = { opened = it }) }
        compose.onNodeWithText("Playlists").assertIsDisplayed()
        compose.onNodeWithText("Tamil Hits").assertIsDisplayed()
        compose.onNodeWithText("Hum Radio · 48 songs").assertIsDisplayed()
        compose.onNodeWithText("Afterimage").assertIsDisplayed()
        compose.onNodeWithText("2 results").assertIsDisplayed()
        compose.onNodeWithContentDescription("Playlist Tamil Hits, Hum Radio · 48 songs").performClick()
        compose.waitForIdle()
        assertEquals("VLPL111111111111", opened?.browseId)
        assertEquals("playlist", opened?.kind)
    }

    @Test fun searchesWithoutPlaylistsLookAsTheyDid() {
        compose.setContent { MusicApp(state(listOf(song), emptyList())) }
        compose.onNodeWithText("Afterimage").assertIsDisplayed()
        compose.onNodeWithText("Playlists").assertDoesNotExist()
        compose.onNodeWithText("1 result").assertIsDisplayed()
    }

    @Test fun aSearchThatOnlyFoundPlaylistsIsNotReportedAsEmpty() {
        compose.setContent { MusicApp(state(emptyList(), listOf(playlist))) }
        compose.onNodeWithText("Tamil Hits").assertIsDisplayed()
        compose.onNodeWithText("Nothing found this time").assertDoesNotExist()
    }

    @Test fun clearingTheSearchClearsItsPlaylists() {
        val cleared = state(listOf(song), listOf(playlist)).clearedSearch()
        assertTrue(cleared.playlistResults.isEmpty() && cleared.results.isEmpty() && cleared.query.isEmpty())
    }

    @Test fun backFromAPlaylistOpenedInSearchReturnsToTheResults() {
        var cleared = false
        compose.setContent { MusicApp(state(listOf(song), listOf(playlist)).copy(look = LookChoice.HUM), onBrowse = {}, onQuery = { if (it.isEmpty()) cleared = true }) }
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithContentDescription("Playlist Tamil Hits, Hum Radio · 48 songs").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Playlists").assertDoesNotExist()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Playlists").assertIsDisplayed()
        compose.onNodeWithText("Afterimage").assertIsDisplayed()
        assertFalse("going back must not clear the search", cleared)
    }
}
