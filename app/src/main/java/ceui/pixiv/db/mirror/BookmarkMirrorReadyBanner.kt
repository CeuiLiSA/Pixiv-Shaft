package ceui.pixiv.db.mirror

import android.content.Context
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.pixiv.banner.BannerCategory
import ceui.pixiv.banner.BannerDisplayPolicy
import ceui.pixiv.banner.BannerIcon
import ceui.pixiv.banner.BannerPriority
import ceui.pixiv.banner.BannerRequest
import ceui.pixiv.banner.host.InAppBanners
import timber.log.Timber
import java.util.Locale

/**
 * 「收藏库准备好了」的一次性引导。
 *
 * 整个镜像过程是**刻意静默**的：用户从头到尾没被打扰过，也就完全不知道后台攒好了一份
 * 可以倒序、可以按标签/作者/年份筛、可以全文搜的本地副本。补齐的那一刻是唯一值得说一句话
 * 的时点 —— 在此之前说等于催促，在此之后说就永远没有由头了。
 *
 * **只弹一次，而且是永久性的一次**：标记落 MMKV（按书架），不是内存标志位。
 * 进程重启、重进页面、甚至用户把镜像重建一遍，都不会再弹第二次 ——
 * 引导只在「第一次知道有这个东西」时有价值，之后每一次都只是打扰。
 */
object BookmarkMirrorReadyBanner {

    private const val TAG = "BookmarkMirror"

    /** MMKV key 前缀。带书架键 = 插画/小说、公开/悄悄各自最多一次。 */
    private const val KEY_PREFIX = "bookmark_mirror_ready_announced_"

    fun announce(context: Context, shelf: BookmarkShelf, rows: Int) {
        val key = KEY_PREFIX + shelf.key
        val prefs = Shaft.getMMKV() ?: return
        if (prefs.decodeBool(key, false)) {
            Timber.tag(TAG).d("[%s] 引导 banner 之前已经弹过，不再弹", shelf.label)
            return
        }
        // **先落标记再弹**：反过来的话，enqueue 抛异常 / 当时没有前台宿主接住这条 banner，
        // 标记就没写上，下次全量完成时又会弹一次。引导宁可漏一次，也不能重复打扰。
        prefs.encode(key, true)

        val deepLink = "shaft://bookmark-library?restrict=${shelf.restrict.apiValue}"
        val shown = runCatching {
            InAppBanners.manager.enqueue(
                BannerRequest.Text(
                    id = "bookmark-mirror-ready-${shelf.key}",
                    title = context.getString(R.string.bookmark_mirror_ready_title),
                    message = context.getString(
                        R.string.bookmark_mirror_ready_message,
                        String.format(Locale.getDefault(), "%,d", rows),
                    ),
                    caption = context.getString(R.string.bookmark_library_title),
                    icon = BannerIcon.Resource(R.drawable.ic_baseline_filter_24),
                    action = ceui.pixiv.banner.BannerAction(
                        label = context.getString(R.string.bookmark_mirror_ready_action),
                        deepLink = deepLink,
                    ),
                    dedupKey = "bookmark-mirror-ready",
                    priority = BannerPriority.NORMAL,
                    category = BannerCategory.System,
                    // Enqueue 而不是 Replace：这条不该把用户正在看的（比如刚收到的私信）挤掉。
                    policy = BannerDisplayPolicy.Enqueue,
                    // 比默认 4 秒长一点：这是一条要读、要理解、还要决定点不点的引导。
                    autoDismissMillis = 7000L,
                    deepLink = deepLink,
                )
            )
        }.getOrElse {
            Timber.tag(TAG).w(it, "[%s] 引导 banner 入队失败", shelf.label)
            false
        }
        Timber.tag(TAG).i("[%s] 收藏库就绪引导 banner 已入队=%b（%d 件）", shelf.label, shown, rows)
    }
}
