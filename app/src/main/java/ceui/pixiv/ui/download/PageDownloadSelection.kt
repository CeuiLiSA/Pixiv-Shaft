package ceui.pixiv.ui.download

/** Zero-based page identity stays independent of thumbnail loading and download state. */
internal class PageDownloadSelection(val pageCount: Int, initial: Iterable<Int>) {
    private val selected = initial.filterTo(sortedSetOf()) { it in 0 until pageCount }
    fun contains(page: Int): Boolean = page in selected
    fun toggle(page: Int) {
        if (page !in 0 until pageCount) return
        if (!selected.remove(page)) selected.add(page)
    }
    fun toggleAll() {
        if (selected.size == pageCount) selected.clear()
        else selected.addAll(0 until pageCount)
    }
    fun selectMissing(downloaded: Set<Int>, inProgress: Set<Int>) {
        selected.clear()
        selected.addAll((0 until pageCount).filter { it !in downloaded && it !in inProgress })
    }
    fun snapshot(): IntArray = selected.toIntArray()
}
