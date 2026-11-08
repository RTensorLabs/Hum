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

/** Recent searches, kept like YouTube's: newest first, tap to search again, remove one or clear all. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchHistoryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun aSearchGoesToTheTopAndTheSameWordsReplaceTheOldOne() {
        val history = SearchHistory.record(SearchHistory.record(SearchHistory.record(emptyList(), "arijit"), "tamil hits"), "  Arijit ")
        assertEquals(listOf("Arijit", "tamil hits"), history)
    }

    @Test fun linksBlanksAndOverlongListsAreNotKept() {
        assertEquals(emptyList<String>(), SearchHistory.record(emptyList(), "https://music.youtube.com/watch?v=x"))
        assertEquals(emptyList<String>(), SearchHistory.record(emptyList(), "   "))
        val many = (1..60).fold(emptyList<String>()) { h, i -> SearchHistory.record(h, "song $i") }
        assertEquals(SearchHistory.LIMIT, many.size)
        assertEquals("song 60", many.first())
    }

    @Test fun anEmptyBoxShowsTheLatestAndTypingNarrowsThem() {
        val history = listOf("tamil hits", "arijit singh", "tamil melody")
        assertEquals(history, SearchHistory.shown(history, ""))
        assertEquals(listOf("tamil hits", "tamil melody"), SearchHistory.shown(history, "TAM"))
        assertEquals(listOf("tamil melody"), SearchHistory.shown(history, "tamil m"))
        assertEquals(emptyList<String>(), SearchHistory.shown(history, "tamil hits"))
        assertEquals(SearchHistory.SHOWN, SearchHistory.shown((1..40).map { "s$it" }, "").size)
    }

    @Test fun removeIgnoresCase() = assertEquals(listOf("b"), SearchHistory.remove(listOf("A", "b"), "a"))

    private fun open(state: MusicState, onSearch: (String) -> Unit = {}, onRemove: (String) -> Unit = {}, onClear: () -> Unit = {}) {
        compose.setContent { MusicApp(state, onSuggestion = onSearch, onRemoveSearch = onRemove, onClearSearches = onClear) }
        compose.onNodeWithContentDescription("Search").performClick()
        compose.waitForIdle()
    }
    private val base = MusicState(look = LookChoice.HUM, reducedMotion = true, searchHistory = listOf("tamil hits", "arijit singh"))

    @Test fun recentSearchesShowInAnEmptySearchAndRunWhenTapped() {
        var ran: String? = null
        open(base, onSearch = { ran = it })
        compose.onNodeWithText("Recent searches").assertIsDisplayed()
        compose.onNodeWithText("arijit singh").assertIsDisplayed()
        compose.onNodeWithContentDescription("Recent search tamil hits").performClick()
        assertEquals("tamil hits", ran)
    }

    @Test fun aRecentSearchCanBeRemovedAndAllCanBeCleared() {
        var removed: String? = null
        var cleared = false
        open(base, onRemove = { removed = it }, onClear = { cleared = true })
        compose.onNodeWithContentDescription("Remove arijit singh from recent searches").performClick()
        assertEquals("arijit singh", removed)
        compose.onNodeWithText("Clear all").performClick()
        assertTrue(cleared)
    }

    @Test fun withListeningHistoryOffNothingIsShown() {
        open(base.copy(trackHistory = false))
        compose.onNodeWithText("Recent searches").assertDoesNotExist()
        compose.onNodeWithText("tamil hits").assertDoesNotExist()
    }

    @Test fun noSearchesNoHeading() {
        open(base.copy(searchHistory = emptyList()))
        compose.onNodeWithText("Recent searches").assertDoesNotExist()
    }
}
