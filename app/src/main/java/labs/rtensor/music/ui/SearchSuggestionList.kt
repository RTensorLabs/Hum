package labs.rtensor.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import labs.rtensor.music.MusicState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import labs.rtensor.music.data.SearchHistory
import labs.rtensor.music.data.Track

@Composable
internal fun SearchSuggestionList(state: MusicState, onSelect: (String) -> Unit, onPlay: (Track) -> Unit,
    onRemoveSearch: (String) -> Unit = {}, onClearSearches: () -> Unit = {}) {
    // Recent searches, like YouTube: the latest ones in an empty box, the matching ones while typing.
    val recent = if (state.trackHistory) SearchHistory.shown(state.searchHistory, state.query) else emptyList()
    val provider = state.suggestions.filterNot { s -> s.track == null && recent.any { it.equals(s.text, ignoreCase = true) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
        if (recent.isNotEmpty() && state.query.isBlank()) item(key = "recent-heading") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent searches", Modifier.weight(1f).semantics { heading() }, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                TextButton(onClick = onClearSearches) { Text("Clear all", fontSize = 13.sp) }
            }
        }
        itemsIndexed(recent, key = { _, text -> "recent:$text" }) { _, text ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onSelect(text) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp).semantics { contentDescription = "Recent search $text" },
                    contentPadding = PaddingValues(vertical = 12.dp)) {
                    Icon(Glyph.History, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { Text(text, color = MaterialTheme.colorScheme.onSurface, maxLines = 1) }
                }
                IconButton(onClick = { onRemoveSearch(text) }) {
                    Icon(Icons.Outlined.Close, "Remove $text from recent searches", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.suggestionsBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp)) }
        itemsIndexed(provider, key = { i, suggestion -> "$i:${suggestion.track?.libraryKey() ?: suggestion.text}" }) { _, suggestion ->
            val track = suggestion.track
            if (track != null) TrackRow(track, state.saved.any { it.libraryKey() == track.libraryKey() }, state.current?.libraryKey() == track.libraryKey(), { onPlay(track) }, null)
            else TextButton(onClick = { onSelect(suggestion.text) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                contentPadding = PaddingValues(vertical = 12.dp)) {
                Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { Text(suggestion.text, color = MaterialTheme.colorScheme.onSurface) }
            }
        }
        if (state.query.isNotBlank()) item {
            TextButton(onClick = { onSelect(state.query) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Search for “${state.query}”")
            }
        }
    }
}
