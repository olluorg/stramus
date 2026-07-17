package stramus.ui

/**
 * What the page opens on. [LAST] picks up where the user left off — the collection they last had open,
 * remembered across reloads; [FIRST] always opens the first collection in the sidebar, whatever was on
 * screen when the page was last closed.
 */
internal enum class StartView(val id: String) {
    LAST("last"),
    FIRST("first"),
    ;

    /** The option's label in the current UI language. */
    fun label(s: Strings): String = when (this) {
        LAST -> s.startViewLast
        FIRST -> s.startViewFirst
    }

    companion object {
        fun from(id: String?): StartView = entries.firstOrNull { it.id == id } ?: LAST
    }
}

/** Where the id of the collection last looked at is kept, so the next open can go back to it. */
internal const val LAST_COLLECTION_PREF = "lastCollection"
