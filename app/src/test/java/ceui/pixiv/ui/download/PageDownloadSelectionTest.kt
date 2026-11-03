package ceui.pixiv.ui.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageDownloadSelectionTest {
    @Test fun restoresOnlyDistinctValidPagesInPageOrder() {
        val selection = PageDownloadSelection(20, listOf(11, 2, 6, 2, -1, 20))
        assertArrayEquals(intArrayOf(2, 6, 11), selection.snapshot())
    }

    @Test fun missingPagesExcludeLocalFilesAndExistingTasks() {
        val selection = PageDownloadSelection(5, listOf(0))
        selection.selectMissing(setOf(0, 3), setOf(1))
        assertArrayEquals(intArrayOf(2, 4), selection.snapshot())
    }

    @Test fun allToggleClearsOnlyWhenEverythingIsSelected() {
        val selection = PageDownloadSelection(3, listOf(1))
        selection.toggleAll()
        assertArrayEquals(intArrayOf(0, 1, 2), selection.snapshot())
        selection.toggleAll()
        assertTrue(selection.snapshot().isEmpty())
    }

    @Test fun invalidTapsCannotCreateDownloadRequests() {
        val selection = PageDownloadSelection(3, emptyList())
        selection.toggle(-1)
        selection.toggle(3)
        assertTrue(selection.snapshot().isEmpty())
        selection.toggle(2)
        assertTrue(selection.contains(2))
        selection.toggle(2)
        assertFalse(selection.contains(2))
    }

    @Test fun snapshotCanRestoreAnIntentionallyEmptySelection() {
        val selection = PageDownloadSelection(20, listOf(2))
        selection.toggle(2)
        val restored = PageDownloadSelection(20, selection.snapshot().asIterable())
        assertTrue(restored.snapshot().isEmpty())
    }

    @Test fun changingReturnedArrayDoesNotMutateSelection() {
        val selection = PageDownloadSelection(3, listOf(1))
        selection.snapshot()[0] = 2
        assertArrayEquals(intArrayOf(1), selection.snapshot())
    }
}
