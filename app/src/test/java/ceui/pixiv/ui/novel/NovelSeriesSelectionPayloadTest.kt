package ceui.pixiv.ui.novel

import ceui.loxia.Novel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelSeriesSelectionPayloadTest {
    private val renderer = novelSeriesCardRenderer()
    private val initial = NovelSeriesCardFeedItem(Novel(id = 42L, title = "Old title"), false, false)

    @Test
    fun `enter select deselect and exit can merge into one selection bind`() {
        val states = listOf(
            initial,
            initial.copy(isMultiSelectMode = true),
            initial.copy(isMultiSelectMode = true, isSelected = true),
            initial.copy(isMultiSelectMode = true),
            initial,
        )
        val payloads = states.zipWithNext { old, new ->
            requireNotNull(renderer.changePayload(old, new))
        }

        payloads.forEach { assertSame(NovelSeriesSelectionPayload, it) }
        assertTrue(NovelSeriesSelectionPayload.canBindSelectionOnly(payloads))
    }

    @Test
    fun `content refresh merged with selection still requires full binding in either order`() {
        val refreshed = initial.copy(novel = initial.novel.copy(title = "New title"))
        assertNull(renderer.changePayload(initial, refreshed))
        // FeedDiff replaces null with its non-null FULL_REBIND sentinel before dispatching.
        val fullRebind = Any()
        val selection = requireNotNull(
            renderer.changePayload(refreshed, refreshed.copy(isMultiSelectMode = true)),
        )

        assertFalse(NovelSeriesSelectionPayload.canBindSelectionOnly(listOf(fullRebind, selection)))
        assertFalse(NovelSeriesSelectionPayload.canBindSelectionOnly(listOf(selection, fullRebind)))
    }

    @Test
    fun `content and selection changed together require full binding`() {
        val changed = initial.copy(
            novel = initial.novel.copy(title = "New title"),
            isMultiSelectMode = true,
            isSelected = true,
        )
        assertNull(renderer.changePayload(initial, changed))
    }

    @Test
    fun `missing or unknown payload requires full binding`() {
        assertFalse(NovelSeriesSelectionPayload.canBindSelectionOnly(emptyList()))
        assertFalse(NovelSeriesSelectionPayload.canBindSelectionOnly(listOf(Any())))
    }
}
