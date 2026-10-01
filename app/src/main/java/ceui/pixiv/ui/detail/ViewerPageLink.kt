package ceui.pixiv.ui.detail

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「二级大图停在哪一页」→「一级详情视口」的进程内交接。
 *
 * 二级大图（[ceui.lisa.activities.ImageDetailActivity]）在翻页落定时广播一次
 * `(作品 id, 进场页, 当前页)`；一级详情页（[ArtworkV3Fragment] /
 * [ceui.lisa.fragments.FragmentIllust]）在 STARTED 期间收下，把列表滚到当前页 —— 于是退出大图
 * 时，详情页视口已经停在用户最后看的那一页。
 *
 * 为什么是 `replay = 0` 的 SharedFlow 而不是 StateFlow：
 *
 * - 详情页在大图之下是 **paused-but-visible**（大图窗口 `windowIsTranslucent`，身后详情可见），
 *   它只走 `onPause`、生命周期仍是 STARTED，收集器全程活着 —— 广播必然收得到，不需要重放兜底。
 * - 反过来，**重放会出错**：详情页视图重建（旋屏 / 回退栈重显）会重新收集，若重放旧值，就会把
 *   用户后来自己滚出来的位置顶掉。`replay = 0` 只投给当下活着的收集器，正好。
 */
object ViewerPageLink {

    /** 一次翻页落定的广播。 */
    data class Ping(
        /** 作品 id：详情页只认自己那一个，避免同页多实例（feed 相邻页）误响应。 */
        val illustId: Long,
        /** 大图的进场页（= 详情页里被点的那一页）。用来区分「用户是真的翻走了」。 */
        val entryPage: Int,
        /** 大图此刻停在哪一页。 */
        val page: Int,
    )

    private val _pings =
        MutableSharedFlow<Ping>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val pings: SharedFlow<Ping> = _pings.asSharedFlow()

    /**
     * 大图翻页落定后调用。没有收集者时静默丢弃（详情页不在，本来也无需联动），
     * 因此用 `tryEmit` 而不是挂起 —— 调用点在 `ViewPager` 的翻页回调里，不能阻塞。
     */
    fun publish(illustId: Long, entryPage: Int, page: Int) {
        _pings.tryEmit(Ping(illustId, entryPage, page))
    }

    /**
     * 详情页回传：当前页那一格在屏幕上的矩形，供大图退出时归位。
     *
     * `screenRect` 是屏幕坐标 `[left, top, right, bottom]`；详情页还没排到那一格时给 null。
     * 大图退出时直接读 [viewport] 的最新值，不需要订阅。
     */
    class Viewport(
        val illustId: Long,
        val page: Int,
        val screenRect: IntArray?,
    )

    private val _viewport = MutableStateFlow<Viewport?>(null)

    val viewport: StateFlow<Viewport?> = _viewport.asStateFlow()

    fun publishViewport(illustId: Long, page: Int, screenRect: IntArray?) {
        _viewport.value = Viewport(illustId, page, screenRect)
    }

    /**
     * 大图会话开始时调用：上一次会话回传的矩形对本次无效（用户退出后可能又滚过详情页）。
     * 不清的话，本次没翻页、停在同一页退出时会匹配上旧值，缩向一个早已不在那里的格子。
     */
    fun clearViewport() {
        _viewport.value = null
    }
}
