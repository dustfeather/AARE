package net.prototux.aare.media

/**
 * Pure browse-layout rules (no Android types, unit-tested): how Android Auto turns the content-style
 * hints of a media tree into tabs, lists, grids and group headers, and how pages are merged.
 */

/** `MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_*`. */
enum class ContentStyle {
    LIST, GRID, CATEGORY_LIST, CATEGORY_GRID;

    val isGrid: Boolean get() = this == GRID || this == CATEGORY_GRID

    /** Category items are short labels with a small icon rather than artwork. */
    val isCategory: Boolean get() = this == CATEGORY_LIST || this == CATEGORY_GRID

    companion object {
        fun fromHint(value: Int?): ContentStyle? = when (value) {
            1 -> LIST
            2 -> GRID
            3 -> CATEGORY_LIST
            4 -> CATEGORY_GRID
            else -> null
        }
    }
}

/** Styles for the browsable and playable children of one node; null = not hinted. */
data class StyleHints(val browsable: ContentStyle? = null, val playable: ContentStyle? = null) {
    /**
     * Hints of a node's own extras override the root's defaults. Like Android Auto, deeper levels
     * do not inherit from their ancestors: only the node itself, then the root, then LIST.
     */
    fun over(root: StyleHints) = StyleHints(browsable ?: root.browsable, playable ?: root.playable)

    fun styleFor(browsable: Boolean): ContentStyle =
        (if (browsable) this.browsable else playable) ?: ContentStyle.LIST
}

/** The layout-relevant facts of one child item. */
data class EntryHints(
    val browsable: Boolean,
    /** CONTENT_STYLE_GROUP_TITLE_HINT: consecutive items with the same title share a header. */
    val groupTitle: String? = null,
    /** CONTENT_STYLE_SINGLE_ITEM_HINT: overrides the level's style for this item only. */
    val singleStyle: ContentStyle? = null,
)

/** A run of consecutive items drawn the same way, with an optional header above it. */
data class Section(val title: String?, val style: ContentStyle, val indices: List<Int>)

/**
 * Splits a level's children into sections: a new section starts when the group title changes or
 * when the style changes (e.g. a grid of albums followed by a list of tracks). Only the first run
 * of a group shows the group's header.
 */
fun sections(entries: List<EntryHints>, level: StyleHints): List<Section> {
    val out = ArrayList<Section>()
    var title: String? = null
    var style: ContentStyle? = null
    var run = ArrayList<Int>()
    var headerShown = false
    var group: String? = null
    entries.forEachIndexed { i, e ->
        val s = e.singleStyle ?: level.styleFor(e.browsable)
        val g = e.groupTitle?.takeIf { it.isNotBlank() }
        if (run.isNotEmpty() && (g != group || s != style)) {
            out += Section(title, style!!, run)
            run = ArrayList()
        }
        if (run.isEmpty()) {
            if (g != group) headerShown = false
            group = g
            style = s
            title = if (g != null && !headerShown) g else null
            headerShown = headerShown || g != null
        }
        run += i
    }
    if (run.isNotEmpty()) out += Section(title, style!!, run)
    return out
}

/** Android Auto shows the root's children as tabs when there are at most this many. */
const val MAX_TABS = 4

/** Tabs when the root has 1..[MAX_TABS] children and all of them are browsable. */
fun useTabs(rootChildrenBrowsable: List<Boolean>): Boolean =
    rootChildrenBrowsable.size in 1..MAX_TABS && rootChildrenBrowsable.all { it }

/** Result of appending one page of children. */
data class Paged<T>(val items: List<T>, val endReached: Boolean)

/**
 * Appends [page] (requested with [pageSize]) to [loaded], dropping ids already present.
 *
 * The end is reached on a short page, but also when a page brings nothing new or is larger than
 * asked: some services ignore paging and return everything for every page.
 */
fun <T> mergePage(loaded: List<T>, page: List<T>, pageSize: Int, id: (T) -> String): Paged<T> {
    val seen = loaded.mapTo(HashSet()) { id(it) }
    val fresh = page.filter { seen.add(id(it)) }
    val end = page.size < pageSize || page.size > pageSize || fresh.isEmpty()
    return Paged(loaded + fresh, end)
}
