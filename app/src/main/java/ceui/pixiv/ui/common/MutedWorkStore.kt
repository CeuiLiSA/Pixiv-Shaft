package ceui.pixiv.ui.common

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.MuteEntity
import ceui.lisa.database.SearchDao
import ceui.lisa.utils.Params
import com.tencent.mmkv.MMKV
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「屏蔽此作品」的名单，真源是 Room 的 `tag_mute_table`（[Params.MUTE_ILLUST] / [Params.MUTE_NOVEL]），
 * 也就是「屏蔽记录」页第三个 tab 读的那张表（[ceui.pixiv.ui.muted.MutedObjectsFeedFragment]）。
 *
 * 屏蔽的表现是**遮罩**而不是过滤：条目留在列表原位，卡片图换成 Glide 模糊图 + 叠一层
 * [ceui.pixiv.widget.SpoilerParticleView] 粒子（Telegram spoiler 的观感），长按菜单能取消。
 * 所以 [ceui.lisa.helper.IllustNovelFilter.judgeID] 那条「命中就整条删掉」的过滤**不能**再挂在
 * feeds 的条目过滤链上——条目被删掉了，UI 上根本不存在，取消屏蔽就无处下手。
 *
 * ⚠️ 但这条界只对**画得出遮罩的 renderer** 成立。挂 feeds 却画不出遮罩的卡片（曾经的
 * [ceui.pixiv.ui.dynamic.timelineIllustRenderer] 就是）必须自己补上打码，否则屏蔽在那张卡上
 * 等于没发生：图原样铺开、连个记号都没有。新增插画/小说 renderer 时先回答「屏蔽态长什么样」。
 *
 * ## 只有一个状态：[isMuted]
 *
 * 在名单里 = 一定打码（模糊图 + 粒子），不在 = 一定正常。菜单标签（屏蔽 / 取消屏蔽）、
 * renderer 画卡片、[ceui.lisa.helper.IllustNovelFilter] 的老列表过滤，读的都是这一个。
 *
 * **打码卡的点击 = 取消屏蔽**（`setMuted(id, false)`），和长按菜单里「取消屏蔽此作品」、
 * 详情页的取消按钮、记录页的删除完全等价：一律删掉那条持久化记录。曾经这里还有第三种
 * 状态「揭开看一眼」（只放开本进程的显示、不动名单），已按产品决定去掉——代价是点一下卡片
 * 就删掉一条屏蔽记录，且卡上除了图变清楚没有别的反馈。别再把「只是想看一眼」的语义加回来，
 * 那会让同一个手势有两种结果、还得再引一个不落盘的状态。
 *
 * ## 存储与线程
 *
 * 曾经这份名单存在设备本地的 MMKV（`illust_spoiler_v1` / `novel_spoiler_v1`），与 Room 那张屏蔽表
 * 各存各的，于是长按屏蔽多少作品，「屏蔽记录」页里一条都看不到。现在统一到 Room：一次屏蔽 = 一条记录，
 * 能在记录页看见、能删、能跟着备份走（[ceui.lisa.utils.BackupUtils] 本来就整表 dump MuteEntity）。
 * 老 MMKV 名单一次性迁进 Room，见 [migrateLegacyMmkv]（**异步**，绝不占 [ensureLoaded] 的调用线程）。
 *
 * 内存里另存一份 id Set：判定发生在列表 bind 的热路径上（fling 时每秒几十次），不能每次都查库。
 * 写入先改内存（当帧生效）、再排到单线程 executor 落库。**所有写库都必须排进这个 executor**——
 * 包括别处「我知道库里有这行，删掉」的取消屏蔽（[setMuted] 的 false 方向是无条件删的，就是给
 * 这类入口用的）。绕过它直接 `deleteMuteEntity` 会和排队中的 insert 抢顺序：DELETE 先跑、
 * 排队的 INSERT 后落，那行就复活了，内存说没屏蔽、库里躺着一条。
 *
 * 插画与小说各持一个实例（[IllustMuteStore] / [NovelMuteStore]），**必须分开**：插画 id 与小说 id
 * 是两条互相独立的自增序列，同号完全可能，合表会让屏蔽一本小说顺带糊掉一张同号插画。
 * 这也正是 `tag_mute_table` 主键是复合 `{id, type}` 的原因。
 */
open class MutedWorkStore(
    private val muteType: Int,
    private val legacyMmkvId: String,
) {

    /** 线程安全：读在主线程（bind），写在 [io]，迁移可能发生在任意首次访问的线程。 */
    private val mutedIds = ConcurrentHashMap.newKeySet<Long>()

    /**
     * 名单的版本号，每次变更 +1。
     *
     * 给已经绑好的卡片做补绑用：屏蔽态不在条目上，靠 bind 时现读，而别的页面（详情页取消屏蔽、
     * 「屏蔽记录」页删除、备份恢复）改完名单是没法通知到那些卡片的。光靠「等它自己滑动复用重绑」
     * 不成立——短列表和已经在屏幕上的卡永远不会被回收，那张卡就一直糊着（或一直不糊）到页面重建。
     *
     * 消费方式一律是订阅 [revisionLive]，见 [IllustFeedFragment.observeMuteRevision]。
     * 这个同步读口只给「我刚自己改完，把水位记下来别再补一遍」用。
     */
    private val revisionCounter = AtomicInteger()

    private val revisionLiveData = MutableLiveData(0)

    val revision: Int
        get() = revisionCounter.get()

    /**
     * [revision] 的可观察版本，列表页/详情页挂 `viewLifecycleOwner` 订阅它来补绑。
     *
     * **不要退回「onResume 里比对版本号」那种写法**：本仓大量 pager 用的是单参
     * `FragmentPagerAdapter`（= `BEHAVIOR_SET_USER_VISIBLE_HINT`，如 `MainActivity.initFragment`）
     * 外加 `setOffscreenPageLimit(n-1)`，同一个 pager 里的所有 tab 是**同时常驻 RESUMED** 的，
     * 左右滑动一次 onResume 都不会触发。在 A tab 屏蔽一张卡，滑到同样含这张卡的 B tab，
     * 那张卡就一直不打码。LiveData 没这个问题：它按 STARTED 投递，兄弟页当场就收到。
     *
     * 变更可能发生在任意线程（主线程点屏蔽/取消、io 线程迁移/作废），故一律 `postValue`。
     * 值单调递增，只表示「变过了」，订阅方自己比对水位去重。
     */
    val revisionLive: LiveData<Int>
        get() = revisionLiveData

    private fun bumpRevision() {
        revisionLiveData.postValue(revisionCounter.incrementAndGet())
    }

    @Volatile
    private var loaded = false

    /** 老 MMKV 名单是否已经排过迁移（进程级一次性，见 [scheduleLegacyMigration]）。 */
    @Volatile
    private var legacyMigrationScheduled = false

    private fun dao(): SearchDao = AppDatabase.getAppDatabase(Shaft.getContext()).searchDao()

    /**
     * 首次访问时把整份名单读进内存。
     *
     * 正常路径上这一步由 [MutedWorkStores.warmUp] 在启动时的后台线程跑掉；这里的同步兜底是给
     * 「预热还没跑完就滑到第一屏」准备的——一次只取 id 列的小查询，好过让首屏漏掉遮罩。
     *
     * ⚠️ 这个方法可能跑在**主线程**（bind 热路径），所以临界区里只准放那一次小查询。
     * 老 MMKV 名单的迁移是成百上千次 insert，绝不能挂在这儿：它排到 [io] 上异步跑
     *（[scheduleLegacyMigration]），跑完自己 bump 版本号让已绑好的卡补绑。曾经它就在
     * `load()` 里、且整段被 `synchronized` 圈着，老用户首屏第一次 bind 能卡到 ANR。
     *
     * 读库失败也照样置 [loaded]：DB 都坏了整个 app 都不工作，不值得让每一次 bind 都去重试一遍。
     * 但这一趟**不能**接着去迁移：[migrateLegacyMmkv] 拿内存名单当「哪些行已经有完整 JSON 了」
     * 的判据，读失败时它是空的，迁移就会把库里每一条完整记录 REPLACE 成 id 壳。
     */
    private fun ensureLoaded() {
        if (loaded) return
        val loadedOk: Boolean
        synchronized(this) {
            if (loaded) return
            loadedOk = runCatching {
                dao().getMutedWorkIds(muteType).forEach { mutedIds.add(it.toLong()) }
            }.onFailure { Timber.w(it, "MutedWorkStore($muteType) load failed") }.isSuccess
            loaded = true
        }
        if (loadedOk) {
            scheduleLegacyMigration()
        }
    }

    /**
     * 把老 MMKV 名单的迁移排到 [io] 上，每个进程只排一次。
     *
     * 排队而不是就地跑：调用方是 [ensureLoaded]，它可能在主线程的 bind 里。排进 [io] 还顺带
     * 与其他落库写共用一条时间线，不会和排队中的 insert/delete 抢顺序。
     */
    private fun scheduleLegacyMigration() {
        if (legacyMigrationScheduled) return
        synchronized(this) {
            if (legacyMigrationScheduled) return
            legacyMigrationScheduled = true
        }
        io.execute {
            runCatching { migrateLegacyMmkv() }
                .onFailure { Timber.w(it, "MutedWorkStore($muteType) mmkv migration failed") }
        }
    }

    /**
     * 一次性把老的 MMKV 遮罩名单搬进 Room。**只在 [io] 上跑**，见 [scheduleLegacyMigration]。
     *
     * MMKV 里只存过 id（值恒为 true），作品 JSON 无从补回，于是写一份只有 id 的壳；
     * 「屏蔽记录」页对拿不到标题的行显示 `#id` 占位（见
     * [ceui.pixiv.ui.muted.MutedObjectsFeedFragment] 的 bind）。这类壳行会在用户下次
     * 对同一作品调 [setMuted]`(true)` 时被升级成完整记录。
     *
     * 已经在内存名单里的 id 一律跳过——它们在库里的 tagJson 是完整作品，被 REPLACE 成 id 壳
     * 就等于把记录页那张卡的封面标题作者全抹了。整批写包在一个事务里：一条一个事务就是一条一次
     * fsync，老名单上千条时能跑上好几秒。搬完 [MMKV.clearAll] 收尾。
     */
    private fun migrateLegacyMmkv() {
        val legacy = MMKV.mmkvWithID(legacyMmkvId)
        val keys = legacy.allKeys() ?: return
        val pending = keys.mapNotNull { it.toIntOrNull() }
            .filter { it > 0 && !mutedIds.contains(it.toLong()) }
        if (pending.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val dao = dao()
            AppDatabase.getAppDatabase(Shaft.getContext()).runInTransaction {
                pending.forEach { id ->
                    dao.insertMuteTag(MuteEntity().apply {
                        this.id = id
                        this.type = muteType
                        this.tagJson = idOnlyJson(id)
                        this.searchTime = now
                    })
                }
            }
            pending.forEach { mutedIds.add(it.toLong()) }
            // 迁移是异步的，首屏那批卡多半已经绑完了：不发这一下，刚迁进来的作品要等滑动复用才打码
            bumpRevision()
        }
        legacy.clearAll()
    }

    /** 在不在屏蔽名单里 = 这一刻该不该打码。renderer、菜单标签、老列表过滤一律读这个。 */
    fun isMuted(workId: Long): Boolean {
        if (workId <= 0L) return false
        ensureLoaded()
        return mutedIds.contains(workId)
    }

    /**
     * 开关某个作品的屏蔽。内存当帧生效，DB 异步落地（排进 [io]，与其他写保持先来后到）。
     *
     * **false 方向是无条件删库的**，哪怕内存里本来就没这个 id：给「屏蔽记录」页删行、详情页
     * 取消屏蔽这类「库里确实有这行」的入口用。它们绝不能自己 `deleteMuteEntity` ——
     * 那会绕开 [io] 的排队，和还没落盘的 insert 抢顺序（见类注释）。
     *
     * @param payload 只在**屏蔽**方向调用，且在 IO 线程上调——序列化整个作品 bean 不该占主线程。
     * 交出来的东西按 [MutedObjectFeedItem][ceui.pixiv.ui.muted.MutedObjectFeedItem] 的解析口径来：
     * 插画给 [ceui.loxia.Illust]、小说给 [ceui.loxia.Novel]（字段名与 Novel 对得上，
     * gson 直接吃）。序列化不出东西就退回 id 壳，记录页仍能列出这一条、仍能删。
     *
     * @return 名单状态是否**真的**变了。已经是目标态返回 false，调用方据此跳过重绑——幂等守卫
     * 放在这里，省得每个入口各写一遍。注意「返回 false」不代表没写库：见上面 false 方向的说明，
     * 以及下面 id 壳升级那段。
     */
    fun setMuted(workId: Long, muted: Boolean, payload: () -> Any? = { null }): Boolean {
        if (workId <= 0L) return false
        ensureLoaded()
        val changed = if (muted) mutedIds.add(workId) else mutedIds.remove(workId)
        if (changed) {
            bumpRevision()
        }
        val id = workId.toInt()
        io.execute {
            runCatching {
                if (muted) {
                    val json = jsonOf(payload, id)
                    // 已经在名单里还照写一遍，是为了把迁移来的 id 壳行升级成完整作品 JSON
                    //（记录页靠它画封面/标题/作者）。但只在这次真拿得出内容时写——否则就是
                    // 拿一个 id 壳把库里已有的完整记录覆盖没了。
                    if (changed || json != idOnlyJson(id)) {
                        dao().insertMuteTag(MuteEntity().apply {
                            this.id = id
                            this.type = muteType
                            this.tagJson = json
                            this.searchTime = System.currentTimeMillis()
                        })
                    }
                } else {
                    dao().deleteMuteEntity(MuteEntity().apply {
                        this.id = id
                        this.type = muteType
                    })
                }
            }.onFailure { Timber.w(it, "MutedWorkStore($muteType) persist $id=$muted failed") }
        }
        return changed
    }

    /** 整份名单作废，下次访问重读（备份恢复 / 屏蔽记录导入这类绕过本类的批量写入之后调）。 */
    fun invalidate() {
        synchronized(this) {
            loaded = false
            mutedIds.clear()
        }
        bumpRevision()
    }

    /**
     * 把名单读进内存，排到 [io] 上跑。
     *
     * 排队而不是新起线程有两个好处：与落库写共用一条时间线（不会和排队中的 insert 打架），
     * 且 [invalidate] 之后能立刻补一次预热——否则重读会拖到下一次 bind，在**主线程**上查库
     * （Room 开了 allowMainThreadQueries，不会抛，只会在滚动中卡一下；刚导入几千条时尤其明显）。
     */
    internal fun warmUp() {
        io.execute { ensureLoaded() }
    }

    private fun jsonOf(payload: () -> Any?, id: Int): String {
        val json = runCatching { Shaft.sGson.toJson(payload()) }.getOrNull()
        return json?.takeIf { it.isNotBlank() && it != "null" } ?: idOnlyJson(id)
    }

    private fun idOnlyJson(id: Int): String = """{"id":$id}"""

    private companion object {
        /**
         * 落库单线程。单线程不只是省资源：它保证同一作品上「屏蔽 → 立刻取消」的两次写按发生顺序
         * 执行，线程池并发跑的话终态可能反过来，内存说未屏蔽、库里躺着一行。
         */
        val io = Executors.newSingleThreadExecutor { r ->
            Thread(r, "muted-work-store").apply { isDaemon = true }
        }
    }
}

/** 插画卡（recy_illust_stagger / recy_timeline_illust）的屏蔽名单。 */
object IllustMuteStore : MutedWorkStore(Params.MUTE_ILLUST, "illust_spoiler_v1")

/** 小说卡（recy_novel）的屏蔽名单。 */
object NovelMuteStore : MutedWorkStore(Params.MUTE_NOVEL, "novel_spoiler_v1")

/** 两份名单的统一入口，给启动预热和「绕过本类的批量写库」之后的作废用。 */
object MutedWorkStores {

    /**
     * 把名单预热进内存，别让首屏第一次 bind 在主线程查库。自带异步，调用方不必另起线程。
     * 从 [ceui.lisa.activities.Shaft.onCreate] 调。
     */
    @JvmStatic
    fun warmUp() {
        IllustMuteStore.warmUp()
        NovelMuteStore.warmUp()
    }

    /**
     * 备份恢复 / 屏蔽记录导入直接往 `tag_mute_table` 灌了行，内存名单必须整份重读。
     * 作废完立刻补一次预热，别把重读留给下一次 bind（那是主线程）。
     */
    @JvmStatic
    fun invalidateAll() {
        IllustMuteStore.invalidate()
        NovelMuteStore.invalidate()
        warmUp()
    }
}
