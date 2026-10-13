package ceui.pixiv.ui.detail

import android.os.Bundle
import ceui.pixiv.api.model.Illust
import ceui.pixiv.snapshot.AutoSnapshotEngine

/**
 * 详情页的自动快照计时凭证：什么时候开表、挂起、续算、结算。
 *
 * [ArtworkV3Fragment] 只把生命周期与视口信号转进来；「横滑降级」「旋屏只结算不评估」「同一实例不重复计
 * 进入」这些规则全在这里。快照模式不调用本类（快照页本身不参与自动生成）。
 */
internal class ArtworkAutoSnapshotTracker(
    private val engine: Engine = Engine.Default,
) {
    /** [AutoSnapshotEngine] 里本类用到的那几个入口；单测替换成记录调用的假实现。 */
    interface Engine {
        fun visible(illustId: Long, type: String?, pageCount: Int, countAsEntry: Boolean): AutoSnapshotEngine.ArtworkVisit?
        fun suspended(visit: AutoSnapshotEngine.ArtworkVisit)
        fun resumed(visit: AutoSnapshotEngine.ArtworkVisit)
        fun left(visit: AutoSnapshotEngine.ArtworkVisit, evaluate: Boolean)

        object Default : Engine {
            override fun visible(illustId: Long, type: String?, pageCount: Int, countAsEntry: Boolean) =
                AutoSnapshotEngine.onArtworkPageVisible(illustId, type, pageCount, countAsEntry)

            override fun suspended(visit: AutoSnapshotEngine.ArtworkVisit) =
                AutoSnapshotEngine.onArtworkPageSuspended(visit)

            override fun resumed(visit: AutoSnapshotEngine.ArtworkVisit) =
                AutoSnapshotEngine.onArtworkPageResumed(visit)

            override fun left(visit: AutoSnapshotEngine.ArtworkVisit, evaluate: Boolean) =
                AutoSnapshotEngine.onArtworkPageLeft(visit, evaluate)
        }
    }

    private var visit: AutoSnapshotEngine.ArtworkVisit? = null

    /**
     * 本页实例是否已经计过一次「进入」：切后台回来、横滑滑回都不重复计。
     * 过 onSaveInstanceState 带过旋屏重建 —— 否则旋几次就能凭空凑满「反复进入」阈值。
     */
    private var entered = false

    /**
     * 本页被横滑降级（onPause 时宿主仍 RESUMED）、还没被滑回来。这样的页视觉上已经离开，
     * 旋屏时也要评估 —— 否则它挂起着的那段停留只结算不评估，触发就丢了。
     */
    private var demoted = false

    fun restore(savedInstanceState: Bundle?) {
        entered = savedInstanceState?.getBoolean(KEY_ENTERED, false) ?: false
    }

    fun save(outState: Bundle) {
        outState.putBoolean(KEY_ENTERED, entered)
    }

    /**
     * 凭证还在手里说明上一次「可见」还没结算（进二级大图页 / 横滑离开 / 滚出作品区再回来）：
     * 表不重开、不重复计进入，只把停过的那一段接着算（续算由随后的 [syncViewport] 按视口决定）。
     */
    fun onResume(illustId: Long, illust: Illust?) {
        demoted = false
        if (visit != null) return
        visit =
            engine.visible(
                illustId = illustId,
                type = illust?.type,
                pageCount = illust?.page_count ?: 0,
                countAsEntry = !entered,
            )
        entered = true
    }

    /**
     * 宿主还 RESUMED ⇒ 是「本页被降级」（横滑到相邻作品），宿主还在、随时会滑回来：
     * 挂起表，不结算也不评估。被自家半透明层（二级大图页及更上层）盖住、或切后台时
     * 宿主自己先 paused，这里什么都不做：计时继续走，不结算也不评估。
     */
    fun onPause(hostStillResumed: Boolean) {
        val visit = visit ?: return
        if (!hostStillResumed) return
        engine.suspended(visit)
        demoted = true
    }

    /**
     * 宿主停止 = 切后台 / 页面结束；旋屏也走这里，但不算离开，只结算不评估。
     * 横滑走的页例外：旋屏前它就已经不在视线里了。
     */
    fun onStop(changingConfigurations: Boolean) {
        settle(evaluate = demoted || !changingConfigurations)
    }

    /** 兜底：页面被销毁（pager 页回收 / 进程内导航销毁）时把还没结算的那一段交出去。 */
    fun onDestroyView() {
        settle(evaluate = true)
    }

    /**
     * 按视口把表挂起 / 续算。[artworkInView] 只在真有凭证时才求值 —— 它要遍历列表子 view，
     * 而本方法挂在每帧滚动与排版回调上。
     *
     * 只管当前页：排版监听挂在**窗口**那份 observer 上，横滑走的相邻页也会跟着当前页的每次排版
     * 回调到这里；不拦的话，它的列表还停在作品区，就会被续上表，把看别的作品的时间算进它。
     * 横滑走的页归 [onPause] 挂起 / [onResume] 续算；被半透明层盖住时本页也不在 RESUMED，表本来就在走。
     */
    fun syncViewport(isResumed: Boolean, artworkInView: () -> Boolean) {
        val visit = visit ?: return
        if (!isResumed) return
        if (artworkInView()) engine.resumed(visit) else engine.suspended(visit)
    }

    /**
     * 结算并停表。凭证只消费一次，所以 onStop 之后接着来的 onDestroyView 是空操作。
     *
     * [evaluate] 为假用于旋屏：视觉没离开，不该触发生成，但停留确实发生了，记下来不丢。
     */
    private fun settle(evaluate: Boolean) {
        val visit = visit ?: return
        this.visit = null
        engine.left(visit, evaluate)
    }

    private companion object {
        /** 旋屏重建时把「已计过进入」带过去，见 [entered]。 */
        const val KEY_ENTERED = "auto_snapshot_entered"
    }
}
