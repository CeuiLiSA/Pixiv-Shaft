package ceui.pixiv.ui.detail

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import ceui.lisa.activities.Shaft
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.updateItems
import ceui.pixiv.ui.common.getHumanReadableMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException
import timber.log.Timber

/**
 * 详情页的懒加载区块(评论 / 作者其他作品 / 相关作品)。每个枚举常量声明**怎么拉自己 + 拉到后
 * 怎么把数据落回 [FeedViewModel] 里的条目**;至于**何时拉**——各自区块首次滚到可见——统一由
 * [SectionLoader] 触发。
 *
 * 关键语义:进页时这些区块都还没滚到,一律不触发。所以「池里已有完整 illust」时点进详情页
 * 不会发任何多余请求(评论 / 作者作品 / 相关都等真正滚到那一块才拉)。抓取纯函数在
 * [ArtworkV3FeedSource] 文件里(data layer),本枚举只做编排,不碰 View / Fragment。
 */
enum class ArtworkSection {

    /** 评论预览:前 3 条塞进 [ArtworkCommentsItem]。 */
    COMMENTS {
        override suspend fun load(illustId: Long, vm: FeedViewModel<String>) {
            val comments = try {
                fetchArtworkComments(illustId)
            } catch (e: HttpException) {
                // #592: app-api 屏蔽的作品评论接口是永久 404,自动重试/网络恢复重试都无意义;
                // 正常返回让 SectionLoader 记成功不再触发,条目渲染成人类可读的报错文案
                // (服务端 user_message,如「找不到页面」)。其余 HTTP 错误照旧抛给三层重试。
                if (e.code() != 404) throw e
                // runCatching 跟 NovelFeedFragment 同款:取文案自身再失败也不能把
                // 「定论性 404」升级回通用重试路径
                val context = Shaft.getContext()
                val message = runCatching { e.getHumanReadableMessage(context) }
                    .getOrDefault(context.getString(ceui.lisa.R.string.msg_load_fail))
                vm.updateItems<ArtworkCommentsItem> { it.withLoadFailed(message) }
                return
            }
            vm.updateItems<ArtworkCommentsItem> { it.withComments(comments) }
        }
    },

    /** 作者其他作品:横向卡片塞进 [ArtworkAuthorWorksItem]。 */
    AUTHOR_WORKS {
        override suspend fun load(illustId: Long, vm: FeedViewModel<String>) {
            // 直接读 Feed 条目携带的作者 id；详情 bean 可能来自离线兜底且未留在 ObjectPool，
            // 再回池取会拿不到并让区块永久卡在 loading。
            val userId = vm.uiState.value.items
                .filterIsInstance<ArtworkAuthorWorksItem>()
                .firstOrNull()
                ?.userId
                ?: return
            if (userId <= 0) {
                vm.updateItems<ArtworkAuthorWorksItem> { it.copy(works = emptyList()) }
                return
            }
            val works = fetchAuthorWorks(userId, illustId)
            vm.updateItems<ArtworkAuthorWorksItem> { it.copy(works = works) }
        }
    },

    /**
     * 相关作品:第 1 页卡片 append 到列表尾(相关头之后)、交接游标开启后续分页(滚到底再
     * loadMore 拉第 2 页起),并把相关头 [ArtworkRelatedHeaderItem.state] 从 null 翻成有 / 无。
     * 三件事通过 [FeedViewModel.adoptCursorAndMutateItems] 一次提交。
     */
    RELATED {
        override suspend fun load(illustId: Long, vm: FeedViewModel<String>) {
            val (related, nextUrl) = fetchArtworkRelated(illustId, null)
            // header 状态 + 首批相关卡 + next cursor 一次提交，避免同一网络结果触发多轮 diff。
            // 虽然新卡都追加在尾部，但 related header 也在同一次提交中换了实例；这不是“旧前缀
            // 引用完全不变”的纯尾部追加，必须推进 structureVersion，避免增量消费者误判。
            vm.adoptCursorAndMutateItems(nextUrl) { existing ->
                val seen = existing.mapTo(HashSet()) { it.javaClass to it.feedKey }
                val fresh = related.filter { seen.add(it.javaClass to it.feedKey) }
                val hasRelated = related.isNotEmpty()
                var changed = fresh.isNotEmpty()
                val result = ArrayList<FeedItem>(existing.size + fresh.size)
                existing.forEach { item ->
                    if (item is ArtworkRelatedHeaderItem && item.state != hasRelated) {
                        result += item.copy(state = hasRelated)
                        changed = true
                    } else {
                        result += item
                    }
                }
                if (fresh.isNotEmpty()) result.addAll(fresh)
                if (changed) result else existing
            }
        }
    };

    abstract suspend fun load(illustId: Long, vm: FeedViewModel<String>)
}

/**
 * 懒加载区块触发器:同一区块在本视图生命周期内只成功跑一次(单飞去重)。协程挂在 [owner]
 *(viewLifecycleOwner)的作用域,视图销毁自动取消;换视图重建一个新实例即自然重置去重集——
 * 不用在 Fragment 里为每个区块各攒一个布尔 flag。
 *
 * 对区块类型 [S] 泛型：插画详情用 [ArtworkSection]，小说详情用
 * [ceui.pixiv.ui.novel.NovelDetailSection]，怎么拉一个区块由 [loadSection] 注入。
 *
 * 失败恢复有三层，按「用户什么都不用做」到「用户主动做点什么」排：
 * 1. **有界自动重试**([MAX_AUTO_RETRIES] 次，退避 [RETRY_BASE_DELAY_MS] 起步)——覆盖最常见的
 *    网络抖动；
 * 2. **网络恢复补触发**([retryFailed]，由宿主 Fragment 在断网→有网时调)；
 * 3. **滚出屏幕再滚回**(自动重试耗尽后交还触发权给 attach)。
 *
 * 三层都需要，因为触发信号只有 attach 一个：区块 holder 停在屏幕内不动就不会再 attach，
 * 只靠第 3 层的话，评论 / 作者作品区块一次失败就会**永远**卡在转圈上，用户完全没有重试入口。
 */
class SectionLoader<S : Any>(
    private val owner: LifecycleOwner,
    private val loadSection: suspend (S) -> Unit,
) {
    /** 已成功或正在飞(含自动重试等待中)的区块：不重复触发。 */
    private val triggered = HashSet<S>()

    /** 自动重试已耗尽、仍未成功的区块。[retryFailed] 的候选集。 */
    private val exhausted = HashSet<S>()

    /** 区块 holder attach 且数据仍空时调用。 */
    fun onVisible(section: S) {
        if (triggered.add(section)) {
            Timber.tag(ARTWORK_LAZY_TAG).d("区块滚到可见,触发懒加载: %s", section)
            launchLoad(section)
        } else {
            Timber.tag(ARTWORK_LAZY_TAG).v("区块再次可见(已加载/加载中,跳过): %s", section)
        }
    }

    /** 网络恢复等时机：把自动重试已耗尽的区块再拉一轮。没有失败区块时是免费的 no-op。 */
    fun retryFailed() {
        if (exhausted.isEmpty()) return
        val pending = exhausted.toList()
        exhausted.clear()
        pending.forEach { section ->
            if (triggered.add(section)) {
                Timber.tag(ARTWORK_LAZY_TAG).d("网络恢复,重试失败区块: %s", section)
                launchLoad(section)
            }
        }
    }

    private fun launchLoad(section: S) {
        owner.lifecycleScope.launch {
            var attempt = 0
            while (true) {
                try {
                    loadSection(section)
                    exhausted.remove(section)
                    return@launch
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    // lifecycleScope 的未捕获异常会直接交给主线程并崩进程；
                    // 不要把网络/解析异常升级成详情页崩溃。
                    attempt++
                    if (attempt > MAX_AUTO_RETRIES) {
                        // 交还触发权：此后靠 retryFailed（网络恢复）或再次 attach（滚出去再回来）
                        triggered.remove(section)
                        exhausted.add(section)
                        Timber.tag(ARTWORK_LAZY_TAG).e(t, "区块懒加载失败且自动重试耗尽: %s", section)
                        return@launch
                    }
                    Timber.tag(ARTWORK_LAZY_TAG).w(
                        t, "区块懒加载失败,第 %d 次自动重试: %s", attempt, section,
                    )
                    delay(RETRY_BASE_DELAY_MS shl (attempt - 1))
                }
            }
        }
    }

    private companion object {
        /** 失败后自动重试次数（之上还有网络恢复补触发和滚动补触发两层兜底）。 */
        const val MAX_AUTO_RETRIES = 2

        /** 首次自动重试的退避，之后翻倍（1.5s → 3s）。 */
        const val RETRY_BASE_DELAY_MS = 1500L
    }
}
