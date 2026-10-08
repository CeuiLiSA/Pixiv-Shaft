package ceui.pixiv.download.toast

import androidx.annotation.StringRes
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Settings
import com.hjq.toast.Toaster
import java.util.LinkedHashSet

/**
 * 「下载相关提示消息」的唯一闸门：下载过程自己弹出来的 toast 都从这里走。
 *
 * 为什么要有这道闸门：以前只有一个总开关（`Settings.toastDownloadResult`）管着「逐张完成 /
 * 逐张失败 / aria2 / 收藏后自动下载」，其余提示（入队、批量汇总、逐篇进度）一律弹到底 ——
 * 想要安静一点的用户只能在「全弹」和「连失败都收不到」之间二选一。现在每条消息一个开关
 * （[DownloadToastKind]），默认全开，安静哪条由用户自己挑。
 *
 * **状态存 `Settings.getMutedDownloadToasts()`，空集 = 全开**。之所以是集合而不是十几个 boolean：
 * 以后新增一条消息天然默认「要提示」，不必再补一个默认 true 的字段 + 存取器；用户手改过 / 从
 * 新版本降级回来的配置里出现认不出的键，也只是被忽略，不会把别的消息连坐静音。
 *
 * 调用面只有两类：弹之前过闸的 `show*`；设置页与弹窗读写用的 [quietCount] / [applyChecked]。
 */
object DownloadToasts {

    /** 认得出名字的查找表；认不出的键静默忽略。 */
    private val BY_KEY: Map<String, DownloadToastKind> =
        DownloadToastKind.entries.associateBy { it.name }

    /** 静音集合里**认得出来**的消息。认不出的键不算数，也不进「已安静 N 条消息」的 N。 */
    @JvmStatic
    fun quietKinds(mutedKeys: Collection<String>?): Set<DownloadToastKind> =
        mutedKeys.orEmpty().mapNotNullTo(LinkedHashSet(), BY_KEY::get)

    /** 纯判定：给定静音集合，这条消息是否已安静。 */
    @JvmStatic
    fun isQuiet(kind: DownloadToastKind, mutedKeys: Collection<String>?): Boolean =
        mutedKeys?.contains(kind.name) == true

    /** 这条消息现在是否已安静（读当前设置）。 */
    @JvmStatic
    fun isQuiet(kind: DownloadToastKind): Boolean = isQuiet(kind, mutedKeys())

    /** 入口行摘要里的 N。 */
    @JvmStatic
    fun quietCount(): Int = quietKinds(mutedKeys()).size

    /**
     * 弹窗「确定」：以**仍然勾选的集合**为准重写静音键，没勾 = 安静。
     *
     * 之所以按「勾选集合」而不是「被关掉的那些」来写：以后新增一个 [DownloadToastKind] 却忘了加进
     * 弹窗时，它会自动回到「提示」，而不是被当成「没勾 = 安静」静默吞掉。
     */
    @JvmStatic
    fun applyChecked(visibleKinds: Set<DownloadToastKind>) {
        val muted = LinkedHashSet<String>()
        for (kind in DownloadToastKind.entries) {
            if (kind !in visibleKinds) muted.add(kind.name)
        }
        settings()?.mutedDownloadToasts = muted
    }

    // ── 弹之前的闸门：被安静的 kind 直接吞掉，不碰 Toaster ────────────────

    @JvmStatic
    fun show(kind: DownloadToastKind, message: CharSequence) {
        if (isQuiet(kind)) return
        Toaster.show(message)
    }

    @JvmStatic
    fun show(kind: DownloadToastKind, @StringRes messageRes: Int) {
        if (isQuiet(kind)) return
        Toaster.show(messageRes)
    }

    @JvmStatic
    fun showShort(kind: DownloadToastKind, message: CharSequence) {
        if (isQuiet(kind)) return
        Toaster.showShort(message)
    }

    @JvmStatic
    fun showShort(kind: DownloadToastKind, @StringRes messageRes: Int) {
        if (isQuiet(kind)) return
        Toaster.showShort(messageRes)
    }

    @JvmStatic
    fun showLong(kind: DownloadToastKind, message: CharSequence) {
        if (isQuiet(kind)) return
        Toaster.showLong(message)
    }

    private fun settings(): Settings? = Shaft.sSettings

    private fun mutedKeys(): Collection<String>? = settings()?.mutedDownloadToasts
}