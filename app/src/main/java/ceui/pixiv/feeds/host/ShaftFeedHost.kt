package ceui.pixiv.feeds.host

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.loxia.getHumanReadableMessage
import ceui.loxia.requireNetworkStateManager
import ceui.pixiv.feeds.FeedFramework
import ceui.pixiv.feeds.FeedHost
import ceui.pixiv.feeds.FeedTheme
import ceui.pixiv.utils.NetworkStateManager
import com.hjq.toast.Toaster

/**
 * :feeds 框架在本 app 里的宿主实现——框架自己不认识 V3 主题、不认识 Toaster、不认识
 * NetworkStateManager，这些全部在这里接回去。装配点见 [FeedFramework.install]，
 * 由 `Shaft.onCreate` 在任何列表页创建之前调一次。
 *
 * 无状态、无捕获：它被进程级持有，Context 一律用传进来的那个。
 */
object ShaftFeedHost : FeedHost {

    /** 在 Application.onCreate 里调一次。 */
    fun install() {
        FeedFramework.install(this)
    }

    /**
     * V3Palette.from 解析 ?attr/colorPrimary 并按当前 uiMode 分深浅支，所以拿 Activity 的
     * context 现算即可跟上主题 / 日夜（两者都会重建 Activity）。
     *
     * - accent 取 textAccent：readability-adjusted 的主题色，对齐 ArtworkV3Fragment /
     *   UserActivityV3 刷新头的既有取色惯例；
     * - spinnerTrack 取 cardFill（「隐约带主题色的不透明悬浮底」，语义正是刷新那块浮起的小圆饼），
     *   与 textAccent 日夜两支恒为「浅箭头深底 / 深箭头浅底」，对比度不会塌；
     * - rootBackground 取 v3_bg —— 「发现」页 fragment_new_center 的底色，也是 V3 各页的统一底。
     */
    override fun theme(context: Context): FeedTheme {
        val palette = V3Palette.from(context)
        return FeedTheme(
            rootBackground = ContextCompat.getColor(context, R.color.v3_bg),
            accent = palette.textAccent,
            spinnerTrack = palette.cardFill,
        )
    }

    /** 空态那只箱子，与 legacy empty_layout 同一张图。 */
    override fun emptyStateImage(context: Context): Int = R.mipmap.empty_img

    /**
     * 复用全 app 统一的映射（[getHumanReadableMessage]：服务端 user_message 优先，
     * 断网 / 超时 / SSL / 反序列化按 AppError 分档取本地化文案）。
     */
    override fun humanReadableError(context: Context, throwable: Throwable): String? =
        throwable.getHumanReadableMessage(context)

    override fun showMessage(context: Context, message: CharSequence) {
        Toaster.showShort(message)
    }

    /**
     * 只认 NONE→online 的迁移，跳过 observe 注册时的粘性首发（判定对齐
     * [ceui.pixiv.ui.task.PageLoadRetryController]）。绑 viewLifecycleOwner 自动解绑。
     */
    override fun observeNetworkRestored(fragment: Fragment, onRestored: () -> Unit) {
        var lastNetworkType: NetworkStateManager.NetworkType? = null
        fragment.requireNetworkStateManager().networkState
            .observe(fragment.viewLifecycleOwner) { type ->
                val wasOffline = lastNetworkType == NetworkStateManager.NetworkType.NONE
                lastNetworkType = type
                if (!wasOffline || !type.isOnline) return@observe
                onRestored()
            }
    }
}
