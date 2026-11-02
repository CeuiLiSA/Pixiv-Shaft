package ceui.pixiv.ui.debug

import ceui.lisa.utils.Params
import ceui.loxia.ImageUrls
import ceui.pixiv.api.model.Illust
import ceui.pixiv.snapshot.SnapshotIndex
import com.tencent.mmkv.MMKV
import timber.log.Timber

/**
 * 【调试】「失效作品」卡片的制造开关，入口在设置 · 试验性页的 debug 行
 * （[ceui.lisa.fragments.FragmentSettingsExperimental]，按 `BuildConfig.DEBUG` 门控）。
 *
 * ## 真机上的失效作品长什么样
 *
 * 「失效作品」指服务端不给看的三种状态（判据见 [ceui.pixiv.api.model.Illust.isUnavailable]）：
 * **非公开**（非公開）／**限定**（限定公開）／**无法显示**（表示できません）。
 *
 * `/v1/user/bookmarks/illust` 真机抓包里的一条（id 88820882）：
 * ```
 * {"title":"","caption":"","visible":false,"page_count":1,"width":100,"height":100,
 *  "x_restrict":0,"illust_ai_type":0,"tags":[],"total_view":0,"total_bookmarks":0,
 *  "image_urls":{"square_medium":"https://s.pximg.net/common/images/limit_unknown_360.png",
 *                "medium":"…同上","large":"…同上"},
 *  "user":{"id":0,"name":"","account":"",
 *          "profile_image_urls":{"medium":"https://s.pximg.net/common/images/no_profile.png"}}}
 * ```
 * 三个容易按「字段全空」想当然写错的点：
 * - 封面**不是灰底**，是服务端给的 `limit_unknown_360.png` 占位图，三档尺寸都指它；
 * - 宽高 **100×100**（卡片因此是 1:1），`page_count` 是 **1** 而不是 0；
 * - `title` / `caption` 是空串而不是 null；`user` 存在，但 id=0、名字空、头像 no_profile。
 *
 * [asInvalidPlaceholder] 就照这份样本造，模拟出来的卡与真机一致。
 *
 * ## 为什么需要它
 *
 * 这个状态全靠服务端下发，本地既造不出来、也不能靠等。想验「失效作品卡上仍然显示快照标记」
 * 这条路径，手上必须有失效卡。
 *
 * ## 怎么造：只换「绘制用的 bean」，不动数据
 *
 * 开关打开后，**两个收藏页**的卡片渲染器会把「本地有离线快照的作品」换成一份失效形状的副本
 * （[asInvalidPlaceholder]），之后的取图、角标、高度全走真实路径 —— 连封面都是那张占位图，
 * 渲染代码里没有任何「假分支」。副本只活在这一次绘制里：不写回条目、不进 `ObjectPool`、
 * 不落任何盘；关掉开关重进即恢复。长按菜单也一并按失效对待
 * （见 [ceui.pixiv.ui.common.showCardMenu]）。
 *
 * 只挑「有快照的作品」：没快照的失效卡上本来就没有标记，拿它验不了这条需求，反而会把整页
 * 都换成占位图。
 */
object DebugInvalidWorks {

    private const val TAG = "DebugInvalidWorks"
    private const val MMKV_ID = "debug_invalid_works_v1"
    private const val KEY_ENABLED = "enabled"

    /** 占位图边长（真机样本里的 `width` / `height`）：卡片因此是 1:1。 */
    private const val INVALID_COVER_SIDE = 100

    private const val NO_PROFILE_URL = "https://s.pximg.net/common/images/no_profile.png"

    /**
     * 失效作品的封面：三档尺寸都指同一张占位图（真机抓包就是这么给的）。
     * 地址直接复用 [Params.IMAGE_UNKNOWN] —— 仓里早就定义过它，一直没人用。
     */
    private val INVALID_COVER = ImageUrls(
        square_medium = Params.IMAGE_UNKNOWN,
        medium = Params.IMAGE_UNKNOWN,
        large = Params.IMAGE_UNKNOWN,
    )

    /** MMKV 初始化失败时降级为 null：本次进程开关恒关，但绝不把异常抛给调用方（同 [ceui.pixiv.snapshot.AutoSnapshotBehaviorStore]）。 */
    private val store: MMKV? by lazy {
        runCatching { MMKV.mmkvWithID(MMKV_ID) }
            .onFailure { Timber.tag(TAG).e(it, "MMKV init failed, invalid-work simulation disabled") }
            .getOrNull()
    }

    /**
     * 读侧在卡片 bind 的热路径上（fling 时每秒几十次），所以落一份进程内缓存；
     * 写侧（设置页那个开关）同步更新缓存，改完立刻生效。
     */
    @Volatile
    private var cached: Boolean? = null

    var enabled: Boolean
        get() = cached ?: (store?.decodeBool(KEY_ENABLED, false) ?: false).also { cached = it }
        set(value) {
            cached = value
            store?.encode(KEY_ENABLED, value)
        }

    /**
     * 这件作品要不要在本页伪装成失效作品。
     *
     * 判据刻意是「有离线快照」而不是「随便挑几件」：见类注释最后一段。
     */
    fun shouldFakeAsInvalid(illustId: Long): Boolean =
        enabled && SnapshotIndex.entryFor(illustId) != null

    /**
     * 把一件「其实还好好存在」的作品改成**失效作品的形状**，字段照真机抓包那条样本填。
     *
     * 只用于这一帧的绘制：调用方拿到它去取图 / 算角标 / 算高度，用完即弃，绝不写回条目或缓存。
     * `isRelated` / `trendingScore` 这两个客户端装饰也一并清掉 —— 真实失效作品不会有它们，
     * 留着会让伪装卡凭空多出一个「相关作品」或热度角标。
     */
    fun asInvalidPlaceholder(bean: Illust): Illust = bean.copy(
        visible = false,
        title = "",
        caption = "",
        image_urls = INVALID_COVER,
        meta_pages = emptyList(),
        meta_single_page = null,
        page_count = 1,
        width = INVALID_COVER_SIDE,
        height = INVALID_COVER_SIDE,
        x_restrict = 0,
        sanity_level = 2,
        illust_ai_type = 0,
        tags = emptyList(),
        series = null,
        total_view = 0,
        total_bookmarks = 0,
        isRelated = false,
        trendingScore = null,
        // 真机上 user 不是 null，而是 id=0 / 名字空 / 头像 no_profile 的壳；作者本来就没有时保持 null
        user = bean.user?.copy(
            id = 0L,
            user_id = 0L,
            name = "",
            account = "",
            is_followed = false,
            profile_image_urls = ImageUrls(medium = NO_PROFILE_URL),
        ),
    )
}
