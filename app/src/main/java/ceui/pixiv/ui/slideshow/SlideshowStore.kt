package ceui.pixiv.ui.slideshow

import ceui.pixiv.api.model.Illust
import java.util.UUID

object SlideshowStore {

    private const val MAX_SESSIONS = 8

    /**
     * 放映队列里的一张图。
     *
     * 为什么要带着 [illust] 与 [page]：幻灯片是**跨作品**放映，把一批作品拍平成 url 列表之后，
     * 「这一页属于哪个作品的第几页」这个配对就丢了 —— 而「本地已下载就直读、不再重下」这条判定
     * （[ceui.pixiv.download.DownloadedPageIndex]）恰恰要 (illustId, page)：它的第 2 段兜底要按
     * 当前命名模板算文件名，而算文件名得读 illust 的 meta_pages 定扩展名，只给 id 是补不回来的。
     *
     * 留着 [illust] 引用不会额外占内存：这些 bean 就是启动放映那个列表里的同一批实例，
     * 而 Session 在放映真正离开任务时（SlideshowFragment.onDestroy）就会被移除。
     */
    data class Slide(
        val url: String,
        val title: String,
        val illust: Illust,
        /** 0-based 页码，与 [ceui.lisa.download.FileCreator.customFileName] / 下载记录同基准。 */
        val page: Int,
    )

    data class Session(
        val slides: List<Slide>,
        val startIndex: Int,
        val random: Boolean,
    )

    private val sessions = LinkedHashMap<String, Session>()

    @Synchronized
    fun put(session: Session): String {
        val id = UUID.randomUUID().toString()
        sessions[id] = session.copy(slides = session.slides.toList())
        if (sessions.size > MAX_SESSIONS) {
            val oldest = sessions.keys.firstOrNull()
            if (oldest != null && oldest != id) sessions.remove(oldest)
        }
        return id
    }

    @Synchronized
    fun get(id: String): Session? = sessions[id]

    @Synchronized
    fun remove(id: String) {
        sessions.remove(id)
    }
}
