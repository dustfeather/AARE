package net.prototux.aare.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseLayoutTest {
    private val list = ContentStyle.LIST
    private val grid = ContentStyle.GRID

    @Test
    fun contentStyleFromHint() {
        assertEquals(ContentStyle.LIST, ContentStyle.fromHint(1))
        assertEquals(ContentStyle.GRID, ContentStyle.fromHint(2))
        assertEquals(ContentStyle.CATEGORY_LIST, ContentStyle.fromHint(3))
        assertEquals(ContentStyle.CATEGORY_GRID, ContentStyle.fromHint(4))
        assertNull(ContentStyle.fromHint(0))
        assertNull(ContentStyle.fromHint(7))
        assertNull(ContentStyle.fromHint(null))
        assertTrue(ContentStyle.CATEGORY_GRID.isGrid)
        assertFalse(ContentStyle.CATEGORY_LIST.isGrid)
    }

    @Test
    fun nodeHintsOverrideRootThenDefaultToList() {
        val root = StyleHints(browsable = grid, playable = list)
        assertEquals(StyleHints(grid, grid), StyleHints(playable = grid).over(root))
        assertEquals(root, StyleHints().over(root))
        assertEquals(list, StyleHints().styleFor(browsable = true))
        assertEquals(list, StyleHints().styleFor(browsable = false))
        assertEquals(grid, StyleHints(browsable = grid).styleFor(browsable = true))
    }

    @Test
    fun uniformLevelIsOneSection() {
        val s = sections(List(3) { EntryHints(browsable = true) }, StyleHints(browsable = grid))
        assertEquals(listOf(Section(null, grid, listOf(0, 1, 2))), s)
    }

    @Test
    fun browsableGridThenPlayableList() {
        val entries = listOf(EntryHints(true), EntryHints(true), EntryHints(false), EntryHints(false))
        val s = sections(entries, StyleHints(browsable = grid, playable = list))
        assertEquals(listOf(Section(null, grid, listOf(0, 1)), Section(null, list, listOf(2, 3))), s)
    }

    @Test
    fun groupTitlesStartSectionsAndShowOnce() {
        val entries = listOf(
            EntryHints(false, "Recent"), EntryHints(true, "Recent"), EntryHints(true, "Recent"),
            EntryHints(false, "Albums"), EntryHints(false, null),
        )
        val s = sections(entries, StyleHints(browsable = grid, playable = list))
        assertEquals(
            listOf(
                Section("Recent", list, listOf(0)),
                // same group, style changed: no second header
                Section(null, grid, listOf(1, 2)),
                Section("Albums", list, listOf(3)),
                Section(null, list, listOf(4)),
            ),
            s,
        )
    }

    @Test
    fun singleItemHintOverridesLevelStyle() {
        val entries = listOf(EntryHints(false), EntryHints(false, singleStyle = grid), EntryHints(false))
        val s = sections(entries, StyleHints(playable = list))
        assertEquals(3, s.size)
        assertEquals(grid, s[1].style)
    }

    @Test
    fun blankGroupTitleMeansNoGroup() {
        val s = sections(listOf(EntryHints(false, " "), EntryHints(false, null)), StyleHints())
        assertEquals(listOf(Section(null, list, listOf(0, 1))), s)
    }

    @Test
    fun emptyLevel() {
        assertTrue(sections(emptyList(), StyleHints()).isEmpty())
    }

    @Test
    fun tabsFromOneToFourBrowsableRootChildren() {
        assertEquals(listOf(0), tabIndices(listOf(true)))
        assertEquals(listOf(0, 1, 2, 3), tabIndices(listOf(true, true, true, true)))
        assertEquals(listOf(0, 2), tabIndices(listOf(true, false, true)))
        assertTrue(tabIndices(emptyList()).isEmpty())
        assertTrue(tabIndices(listOf(false, false)).isEmpty())
        assertTrue(tabIndices(List(5) { true }).isEmpty())
    }

    @Test
    fun mergeAppendsAndDetectsEnd() {
        val first = mergePage(emptyList(), (0 until 50).map { "a$it" }, 50) { it }
        assertEquals(50, first.items.size)
        assertFalse(first.endReached)
        val second = mergePage(first.items, (50 until 70).map { "a$it" }, 50) { it }
        assertEquals(70, second.items.size)
        assertTrue(second.endReached)
    }

    @Test
    fun mergeStopsWhenServiceIgnoresPaging() {
        val all = (0 until 50).map { "a$it" }
        // the same full page again: nothing new
        val again = mergePage(all, all, 50) { it }
        assertEquals(50, again.items.size)
        assertTrue(again.endReached)
        // everything at once, more than asked
        val big = mergePage(emptyList(), (0 until 120).map { "b$it" }, 50) { it }
        assertEquals(120, big.items.size)
        assertTrue(big.endReached)
    }

    @Test
    fun mergeDropsDuplicateIds() {
        val m = mergePage(listOf("x", "y"), listOf("y", "z", "z"), 3) { it }
        assertEquals(listOf("x", "y", "z"), m.items)
    }
}
