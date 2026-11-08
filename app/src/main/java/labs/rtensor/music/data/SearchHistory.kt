package labs.rtensor.music.data

/** Searches you ran, newest first, kept on this device like YouTube's recent searches. */
object SearchHistory {
    const val LIMIT = 30
    const val SHOWN = 10

    /** Links are pasted, not searched for, so they never become a recent search. */
    fun worthKeeping(query: String): Boolean {
        val value = query.trim()
        return value.isNotEmpty() && !value.startsWith("http://", true) && !value.startsWith("https://", true)
    }

    /** The search goes to the top; the same words in another case or spacing replace the older entry. */
    fun record(history: List<String>, query: String): List<String> {
        if (!worthKeeping(query)) return history
        val value = query.trim().replace(Regex("\\s+"), " ").take(120)
        return (listOf(value) + history.filterNot { it.equals(value, ignoreCase = true) }).take(LIMIT)
    }

    fun remove(history: List<String>, entry: String): List<String> = history.filterNot { it.equals(entry, ignoreCase = true) }

    /** An empty box shows the latest searches; typing narrows them to the ones that contain what was typed. */
    fun shown(history: List<String>, typed: String): List<String> {
        val needle = typed.trim()
        return history.filter { needle.isEmpty() || (it.contains(needle, ignoreCase = true) && !it.equals(needle, ignoreCase = true)) }.take(SHOWN)
    }
}
