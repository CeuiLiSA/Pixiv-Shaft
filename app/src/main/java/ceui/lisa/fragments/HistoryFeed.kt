package ceui.lisa.fragments

import android.content.Intent
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.IllustHistoryEntity
import ceui.lisa.databinding.CellHistoryIllustV3Binding
import ceui.lisa.databinding.CellHistoryNovelV3Binding
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.NovelBean
import ceui.lisa.models.UserBean
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.HistoryEntry
import ceui.loxia.ObjectPool
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.tryOpenNovelReaderDirect
import ceui.pixiv.utils.clearGlideOnRecycle
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale

/** 历史卡封面高度下限 = 宽的 0.5 倍(对齐改造前 `coerceAtLeast(itemWidth / 2)` 的语义)。 */
private const val MIN_HISTORY_HEIGHT_RATIO = 0.5f

/** 历史选择态变化的增量 payload：只刷勾标/删除钮，不重跑图片加载。 */
private val PAYLOAD_HISTORY_SELECTION = Any()

// ── FeedItem 模型（原 HistoryIllustHolder / HistoryNovelHolder 的数据部分）─────────────
// isSelectionMode / isSelected 由 FragmentHistoryList.syncSelection 通过 updateItems 回灌。

data class HistoryIllustFeedItem(
    val entity: IllustHistoryEntity,
    val illust: IllustsBean,
    val isSelectionMode: Boolean = false,
    val isSelected: Boolean = false,
) : FeedItem {
    override val feedKey: Any get() = entity.illustID

    // entity(Java) / IllustsBean 都没有 equals，data class 默认实现退化成引用比较——刷新产出的
    // 同内容新实例会被 DiffUtil 判「变了」而全表重绑（Glide 请求全部重发）。渲染消费的快照内容
    // 由 illustID + time 指纹（重看同一作品会刷新 time，快照 JSON 随之更新），再加两个选择态。
    // 手写按这四样比，对齐 IllustFeedItem 手写 equals 的先例。
    override fun equals(other: Any?): Boolean {
        return other is HistoryIllustFeedItem &&
                other.entity.illustID == entity.illustID &&
                other.entity.time == entity.time &&
                other.isSelectionMode == isSelectionMode &&
                other.isSelected == isSelected
    }

    override fun hashCode(): Int {
        var result = entity.illustID
        result = 31 * result + entity.time.hashCode()
        result = 31 * result + isSelectionMode.hashCode()
        result = 31 * result + isSelected.hashCode()
        return result
    }
}

data class HistoryNovelFeedItem(
    val entity: IllustHistoryEntity,
    val novel: NovelBean,
    val isSelectionMode: Boolean = false,
    val isSelected: Boolean = false,
) : FeedItem {
    override val feedKey: Any get() = entity.illustID

    // 同 HistoryIllustFeedItem：手写内容相等性，防引用比较导致的全表重绑。
    override fun equals(other: Any?): Boolean {
        return other is HistoryNovelFeedItem &&
                other.entity.illustID == entity.illustID &&
                other.entity.time == entity.time &&
                other.isSelectionMode == isSelectionMode &&
                other.isSelected == isSelected
    }

    override fun hashCode(): Int {
        var result = entity.illustID
        result = 31 * result + entity.time.hashCode()
        result = 31 * result + isSelectionMode.hashCode()
        result = 31 * result + isSelected.hashCode()
        return result
    }
}

// ── FeedSource：远端 pixshaft(按 uid) 优先，失败/未登录/未同意回退本地 DAO。搜索走单页。──
// 原 HistoryListViewModel 的 fetchPage / applySearch / remoteToEntity 逻辑整体搬来。
// searchVm 是 activity-scope（跨旋转存活），source 由 FeedViewModel 长期持有也安全。

class HistoryFeedSource(
    private val historyType: Int,
    private val searchVm: HistorySearchSharedViewModel,
) : FeedSource<String> {

    private val dao = AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao()
    private val serverType = if (historyType == 0) "illust,manga" else "novel"

    /** 远端这次会话已失败/为空 → 本会话后续翻页直接走本地，避免远端/本地混页。 */
    private var forcedLocal = false

    private fun useRemote(): Boolean =
        SessionManager.loggedInUid > 0L &&
            Shaft.sSettings.isCloudHistorySync && Shaft.sSettings.isCloudHistoryConsentShown

    override suspend fun load(cursor: String?): FeedPage<String> {
        val page = loadPage(cursor)
        // ObjectPool.store 是普通 map + setValue(见 ObjectPool),后台线程写会与主线程 get/update
        // 竞争撞 ConcurrentModificationException。load 由 FeedViewModel 的 viewModelScope(Main)调起,
        // 这里回主线程再喂池,详情页 ObjectPool.get 才拿得到 illust。
        //
        // 只做 putIfAbsent,绝不覆盖:历史条目的 illustJson 是**浏览当时**冻结的快照,直接
        // updateIllust 会拿旧的 is_bookmarked=false / is_followed=false 盖掉当前会话里更新的池值
        // (mergeKeepingExisting 不把 false 当空值——用户刚在别处收藏了作品 X,打开含 X 的历史页,
        // 红心就被旧快照打回灰心)。V3 详情页要的是「池里有这条」,不是「池里是旧快照」。
        // user 单独判:作品 miss 但作者已在池里(在别处刚关注)时,不能让陈旧关注态顺手合进去。
        if (historyType == 0) {
            withContext(Dispatchers.Main.immediate) {
                page.items.forEach { item ->
                    val illust = (item as? HistoryIllustFeedItem)?.illust ?: return@forEach
                    if (ObjectPool.getIllust(illust.id.toLong()).value == null) {
                        ObjectPool.update(illust)
                    }
                    illust.user?.let { user ->
                        if (ObjectPool.get<UserBean>(user.id.toLong()).value == null) {
                            ObjectPool.updateUser(user)
                        }
                    }
                }
            }
        }
        return page
    }

    private suspend fun loadPage(cursor: String?): FeedPage<String> = withContext(Dispatchers.IO) {
        val query = searchVm.query.value?.trim().orEmpty().ifEmpty { null }
        // 只在首页加载时记账：翻页沿用同一个搜索词，不该改写「这一代列表对应哪个词」。
        // 记在 activity 作用域的 searchVm 里，本 tab 的视图 / Fragment 重建都冲不掉，
        // 重建后 [FragmentHistoryList] 据此判断要不要补刷。
        if (cursor == null) searchVm.markQueryApplied(historyType, query)
        if (query != null) {
            // 搜索：单页、无翻页（nextCursor = null）
            val entities = if (useRemote()) {
                try {
                    Client.pixshaft.listHistory(
                        SessionManager.loggedInUid, serverType, query, null, SEARCH_LIMIT,
                    ).items.mapNotNull { remoteToEntity(it) }
                } catch (ex: Exception) {
                    Timber.w(ex, "remote history search unavailable, falling back to local")
                    dao.searchViewHistoryByType(query, historyType)
                }
            } else {
                dao.searchViewHistoryByType(query, historyType)
            }
            return@withContext FeedPage(entities.toFeedItems(), null)
        }

        if (cursor == null) forcedLocal = false
        if (useRemote() && !forcedLocal) {
            try {
                val resp = Client.pixshaft.listHistory(
                    SessionManager.loggedInUid, serverType, null, cursor, PAGE_SIZE,
                )
                val mapped = resp.items.mapNotNull { remoteToEntity(it) }
                // 刚同意云同步时云端可能还是空的 → 回退本地，否则列表会突然刷成空白。
                if (cursor == null && mapped.isEmpty()) {
                    forcedLocal = true
                } else {
                    return@withContext FeedPage(mapped.toFeedItems(), resp.nextCursor)
                }
            } catch (ex: Exception) {
                Timber.w(ex, "remote history unavailable, falling back to local DB")
                forcedLocal = true
            }
        }
        // 本地分页。
        //
        // 手上的 cursor 可能是**远端游标**（远端翻页中途失败、刚回退到这里），它和本地 offset
        // 是两套完全不同的语义，只是都被 FeedSource<String> 挤进了同一个 String。过去这里直接
        // `cursor.toIntOrNull() ?: 0` 硬转，两条路都是错的：
        // - 不透明 token（如 base64）转不出 → 退成 0 → 返回本地第 1 页。那页全是已加载过的重复
        //   id，被 loadMore 的 dedupByIdentity 滤空 → 空页追载按 30/60/90… 往后跳，而
        //   FeedViewModel.MAX_EMPTY_PAGE_HOPS 只有个位数（当前 2），只覆盖本地最前面的几页；
        //   已加载内容超出那个范围时每一跳都是重复 → reachedEnd 永久置位，列表再也翻不动
        //   （只能下拉刷新救回来）。
        // - 若服务端游标恰好是纯数字（秒级时间戳），toIntOrNull() 会**成功** → offset 十几亿 →
        //   查回空页 → 当场判到底。更隐蔽。
        // 所以本地游标显式带前缀：认得出的才是本地 offset，认不出的一律当「从头开始本地翻页」。
        // 远端与本地都按浏览时间倒序，重开一次本地分页最多与已加载内容重叠，由 dedupByIdentity
        // 兜住，不会像上面那样死锁在 reachedEnd。
        val offset = cursor?.takeIf { it.startsWith(LOCAL_CURSOR_PREFIX) }
            ?.removePrefix(LOCAL_CURSOR_PREFIX)?.toIntOrNull() ?: 0
        val entities = dao.getViewHistoryByType(historyType, PAGE_SIZE, offset)
        val next = if (entities.size >= PAGE_SIZE) {
            "$LOCAL_CURSOR_PREFIX${offset + entities.size}"
        } else {
            null
        }
        FeedPage(entities.toFeedItems(), next)
    }

    /** 把远端条目映射回 illust_table 的 entity 形态，复用既有渲染。 */
    private fun remoteToEntity(entry: HistoryEntry): IllustHistoryEntity? {
        val payload = entry.payload ?: return null
        return IllustHistoryEntity().apply {
            illustID = entry.target_id.toInt()
            illustJson = Shaft.sGson.toJson(payload)
            time = entry.viewed_at
            type = historyType
        }
    }

    private fun List<IllustHistoryEntity>.toFeedItems(): List<FeedItem> = mapNotNull { entity ->
        if (historyType == 0) {
            val illust = Shaft.sGson.fromJson(entity.illustJson, IllustsBean::class.java)
                ?: return@mapNotNull null
            HistoryIllustFeedItem(entity, illust)
        } else {
            val novel = Shaft.sGson.fromJson(entity.illustJson, NovelBean::class.java)
                ?: return@mapNotNull null
            HistoryNovelFeedItem(entity, novel)
        }
    }

    companion object {
        const val PAGE_SIZE = 30
        const val SEARCH_LIMIT = 100

        /**
         * 本地分页游标的前缀。远端游标是服务端的不透明字符串，两者共用 FeedSource<String> 的
         * 同一个游标位——不显式区分就没法在拿到一个游标时判断它属于哪套语义。
         */
        const val LOCAL_CURSOR_PREFIX = "local:"
    }
}

/**
 * 删除历史条目（本地 + 远端）。永远删本地（双写副本），server 挂时回退本地才不会「复活」。
 * 独立 suspend 函数：调用方（[FragmentHistoryList]）拿不到 FeedViewModel 内部的 source，直接调这里。
 */
suspend fun deleteHistoryEntities(historyType: Int, entities: List<IllustHistoryEntity>) =
    withContext(Dispatchers.IO) {
        val dao = AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao()
        val useRemote = SessionManager.loggedInUid > 0L &&
            Shaft.sSettings.isCloudHistorySync && Shaft.sSettings.isCloudHistoryConsentShown
        entities.forEach { entity ->
            dao.delete(entity)
            if (useRemote) {
                val tt = if (historyType == 1) {
                    "novel"
                } else {
                    val ib = runCatching {
                        Shaft.sGson.fromJson(entity.illustJson, IllustsBean::class.java)
                    }.getOrNull()
                    if (ib?.type == "manga") "manga" else "illust"
                }
                runCatching {
                    Client.pixshaft.deleteHistory(SessionManager.loggedInUid, tt, entity.illustID.toLong())
                }.onFailure { Timber.w(it, "remote history delete failed (local deleted)") }
            }
        }
    }

// ── Renderers（原 HistoryIllustViewHolder / HistoryNovelViewHolder 的绑定逻辑）──────────
// 定义成 FragmentHistoryList 扩展：renderer 只在 onCreateRenderers() 里 new，随 view 生死，
// 引用 fragment 的方法安全（不违反 FeedSource 的零捕获约定）。

fun FragmentHistoryList.historyIllustRenderer(): FeedRenderer<HistoryIllustFeedItem, CellHistoryIllustV3Binding> =
    feedRenderer(
        inflate = CellHistoryIllustV3Binding::inflate,
        create = { cell ->
            // 监听只挂一次,点击那一刻经 cell.item 取当下条目(框架约定:绑定零 lambda 分配)
            val binding = cell.binding
            binding.root.setOnClickListener {
                val item = cell.itemOrNull ?: return@setOnClickListener
                if (item.isSelectionMode) toggleHistorySelect(item.entity)
                else openHistoryIllust(item.illust)
            }
            binding.root.setOnLongClickListener {
                val item = cell.itemOrNull ?: return@setOnLongClickListener false
                if (!item.isSelectionMode) confirmDeleteHistory(item.entity)
                true
            }
            binding.deleteItem.setOnClickListener {
                cell.itemOrNull?.let { item -> confirmDeleteHistory(item.entity) }
            }
            binding.author.setOnClickListener {
                cell.itemOrNull?.illust?.user?.id?.let { uid -> openHistoryUser(uid) }
            }
        },
        recycle = { it.binding.illustImage.clearGlideOnRecycle() },
        // 选择态变化（isSelectionMode/isSelected）只刷勾标与删除钮，不重跑 Glide——
        // 全量重绑会让 Glide 清掉当前 bitmap 换占位色再异步载入，造成勾选瞬间图片闪烁。
        changePayload = { old, new ->
            if (old.entity.illustID == new.entity.illustID &&
                old.entity.time == new.entity.time &&
                (old.isSelectionMode != new.isSelectionMode || old.isSelected != new.isSelected)
            ) {
                PAYLOAD_HISTORY_SELECTION
            } else {
                null
            }
        },
        bindPayloads = { cell, payloads ->
            if (payloads.all { it === PAYLOAD_HISTORY_SELECTION }) {
                val item = cell.item
                HistorySelectBadge.bind(cell.binding.selectCheck, item.isSelectionMode, item.isSelected)
                cell.binding.deleteItem.isVisible = !item.isSelectionMode
                true
            } else {
                false
            }
        },
    ) { cell ->
        val binding = cell.binding
        val item = cell.item
        val illust = item.illust
        val entity = item.entity
        val context = binding.root.context

        // 只按元数据驱动宽高比,宽度交给瀑布流列自身(DynamicHeightImageView 在 onMeasure 用真实
        // 列宽算高,对齐 recy_illust_stagger)。
        //
        // 这里曾经是 `itemWidth = (screenWidth - 4dp*6) / 2` 再把像素宽高钉死在 layoutParams 上。
        // 那个 /2 是 legacy 写死两列时代的残留:LayoutManager 改成跟随「每行几列」设置之后,3/4 列
        // 时 ImageView 仍按屏宽/2 量,塞进屏宽/3 的卡里被横向裁掉,而高度又按错的宽度算出来,
        // 卡整体高 1.5~2 倍。默认值是 2 列,所以只在非默认档露馅。
        // 顺带治了绝对像素的老毛病:复用的卡片横竖屏切换后会揣着旧方向的尺寸。
        val ratio = if (illust.width > 0 && illust.height > 0) {
            (illust.height.toFloat() / illust.width).coerceAtLeast(MIN_HISTORY_HEIGHT_RATIO)
        } else {
            1f
        }
        binding.illustImage.setHeightRatio(ratio)
        // 请求尺寸必须显式 override,不能靠 Glide 自己量。
        //
        // 旧代码把像素宽高钉在 layoutParams 上,恰好命中 Glide SizeDeterminer 的第一个分支
        //（`paramSize > 0` 直接采用）—— 它是无意中在替 override 干活。改成 wrap_content 之后
        // paramSize 是 -2,会掉到 `view.getHeight()`,而 setHeightRatio 只 requestLayout()、
        // 不同步改 getHeight(),bind 又发生在 measure 之前 → 复用的 holder 上读到的是**上一条目
        // 的高**。scaleType=centerCrop 让 into(ImageView) 按「请求尺寸的宽高比」解码裁图,随后
        // view 按新 ratio 重新量高、再 centerCrop 一次 → 二次裁切放大,图糊且只剩一小块
        //（Glide 不会因为 relayout 重发请求）。同 recy_illust_stagger 那段注释治的病。
        val columnWidth = (context.resources.displayMetrics.widthPixels /
                Shaft.sSettings.lineCount).coerceAtLeast(1)
        Glide.with(context).load(GlideUtil.getMediumImg(illust))
            .override(columnWidth, (columnWidth * ratio).toInt().coerceAtLeast(1))
            .placeholder(R.color.v3_surface_2).into(binding.illustImage)
        binding.title.text = illust.title
        binding.author.text = illust.user?.name.orEmpty()
        binding.time.text = historyTimeFormat.format(entity.time)

        when {
            illust.isGif -> { binding.pSize.isVisible = true; binding.pSize.text = "GIF" }
            illust.page_count > 1 -> {
                binding.pSize.isVisible = true
                binding.pSize.text = String.format(Locale.getDefault(), "%dP", illust.page_count)
            }
            else -> binding.pSize.isVisible = false
        }

        HistorySelectBadge.bind(binding.selectCheck, item.isSelectionMode, item.isSelected)
        binding.deleteItem.isVisible = !item.isSelectionMode
    }

fun FragmentHistoryList.historyNovelRenderer(): FeedRenderer<HistoryNovelFeedItem, CellHistoryNovelV3Binding> =
    feedRenderer(
        inflate = CellHistoryNovelV3Binding::inflate,
        create = { cell ->
            // 同 historyIllustRenderer:监听只挂一次,点击时经 cell.item 取当下条目
            val binding = cell.binding
            binding.root.setOnClickListener { v ->
                val item = cell.itemOrNull ?: return@setOnClickListener
                if (item.isSelectionMode) {
                    toggleHistorySelect(item.entity)
                } else if (!v.context.tryOpenNovelReaderDirect(item.novel.id.toLong())) {
                    v.context.startActivity(Intent(v.context, TemplateActivity::class.java).apply {
                        putExtra(Params.CONTENT, item.novel)
                        putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情")
                        putExtra("hideStatusBar", true)
                    })
                }
            }
            binding.root.setOnLongClickListener {
                val item = cell.itemOrNull ?: return@setOnLongClickListener false
                if (!item.isSelectionMode) confirmDeleteHistory(item.entity)
                true
            }
            binding.deleteItem.setOnClickListener {
                cell.itemOrNull?.let { item -> confirmDeleteHistory(item.entity) }
            }
            binding.author.setOnClickListener {
                cell.itemOrNull?.novel?.user?.id?.let { uid -> openHistoryUser(uid) }
            }
        },
        recycle = { it.binding.illustImage.clearGlideOnRecycle() },
        // 同 historyIllustRenderer：选择态只刷勾标与删除钮，避免重发 Glide 造成闪烁。
        changePayload = { old, new ->
            if (old.entity.illustID == new.entity.illustID &&
                old.entity.time == new.entity.time &&
                (old.isSelectionMode != new.isSelectionMode || old.isSelected != new.isSelected)
            ) {
                PAYLOAD_HISTORY_SELECTION
            } else {
                null
            }
        },
        bindPayloads = { cell, payloads ->
            if (payloads.all { it === PAYLOAD_HISTORY_SELECTION }) {
                val item = cell.item
                HistorySelectBadge.bind(cell.binding.selectCheck, item.isSelectionMode, item.isSelected)
                cell.binding.deleteItem.isVisible = !item.isSelectionMode
                true
            } else {
                false
            }
        },
    ) { cell ->
        val binding = cell.binding
        val item = cell.item
        val novel = item.novel
        val entity = item.entity
        val context = binding.root.context

        Glide.with(context).load(GlideUtil.getUrl(novel.image_urls?.medium))
            .placeholder(R.color.v3_surface_2).into(binding.illustImage)
        binding.title.text = novel.title
        binding.author.text = novel.user?.name.orEmpty()
        binding.time.text = historyTimeFormat.format(entity.time)
        binding.badgeAi.isVisible = novel.isCreatedByAI()

        HistorySelectBadge.bind(binding.selectCheck, item.isSelectionMode, item.isSelected)
        binding.deleteItem.isVisible = !item.isSelectionMode
    }