package ceui.lisa.fragments

import android.content.Intent
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.UActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.CellHistoryUserBinding
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.HistoryEntry
import ceui.loxia.User
import ceui.pixiv.db.EntityType
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.db.RecordType
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.session.SessionManager
import ceui.pixiv.utils.clearGlideOnRecycle
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

// ── FeedItem 模型（原 HistoryUserHolder 的数据部分）。isSelectionMode/isSelected 由
//    FragmentHistoryUserList.syncSelection 通过 updateItems 回灌，键为 entity.id(uid)。──

data class HistoryUserFeedItem(
    val entity: GeneralEntity,
    val user: User?,
    val isSelectionMode: Boolean = false,
    val isSelected: Boolean = false,
) : FeedItem {
    override val feedKey: Any get() = entity.id
}

/** entity.json 反序列化成 User 放进 item(在 source 的 IO 线程做一次),避免 renderer 每次 onBind 都 parse。 */
private fun GeneralEntity.toUserFeedItem(): HistoryUserFeedItem {
    val user = runCatching { Shaft.sGson.fromJson(json, User::class.java) }.getOrNull()
    return HistoryUserFeedItem(this, user)
}

// ── FeedSource：远端 pixshaft("user") 优先，失败/未登录/未同意回退本地 general_table。
//    「用户」tab 无搜索(对齐旧 HistoryUserViewModel)。原 fetchPage 逻辑整体搬来。──

class HistoryUserFeedSource : FeedSource<String> {

    private val dao = AppDatabase.getAppDatabase(Shaft.getContext()).generalDao()
    private var forcedLocal = false

    private fun useRemote(): Boolean =
        SessionManager.loggedInUid > 0L &&
            Shaft.sSettings.isCloudHistorySync && Shaft.sSettings.isCloudHistoryConsentShown

    override suspend fun load(cursor: String?): FeedPage<String> = withContext(Dispatchers.IO) {
        if (cursor == null) forcedLocal = false
        if (useRemote() && !forcedLocal) {
            try {
                val resp = Client.pixshaft.listHistory(
                    SessionManager.loggedInUid, "user", null, cursor, PAGE_SIZE,
                )
                val mapped = resp.items.mapNotNull { remoteToEntity(it) }
                if (cursor == null && mapped.isEmpty()) {
                    forcedLocal = true
                } else {
                    return@withContext FeedPage(mapped.map { it.toUserFeedItem() }, resp.nextCursor)
                }
            } catch (ex: Exception) {
                Timber.w(ex, "remote user-history unavailable, falling back to local DB")
                forcedLocal = true
            }
        }
        // 本地游标显式带前缀,别拿远端游标硬转 offset —— 详见 HistoryFeedSource 同处的注释
        //（远端翻页中途失败会带着服务端的不透明游标落到这里，硬转要么退成 0 死锁在
        // reachedEnd、要么在纯数字游标上「转成功」拿到十几亿的 offset）。
        val offset = cursor?.takeIf { it.startsWith(HistoryFeedSource.LOCAL_CURSOR_PREFIX) }
            ?.removePrefix(HistoryFeedSource.LOCAL_CURSOR_PREFIX)?.toIntOrNull() ?: 0
        val entities = dao.getByRecordType(RecordType.VIEW_USER_HISTORY, offset, PAGE_SIZE)
        val next = if (entities.size >= PAGE_SIZE) {
            "${HistoryFeedSource.LOCAL_CURSOR_PREFIX}${offset + entities.size}"
        } else {
            null
        }
        FeedPage(entities.map { it.toUserFeedItem() }, next)
    }

    private fun remoteToEntity(entry: HistoryEntry): GeneralEntity? {
        val payload = entry.payload ?: return null
        return GeneralEntity(
            entry.target_id,
            Shaft.sGson.toJson(payload),
            EntityType.USER,
            RecordType.VIEW_USER_HISTORY,
            entry.viewed_at,
        )
    }

    companion object {
        const val PAGE_SIZE = 30
    }
}

/** 删除用户浏览历史（本地 + 远端）。永远删本地，server 挂时回退本地不会「复活」。 */
suspend fun deleteUserHistoryEntities(entities: List<GeneralEntity>) = withContext(Dispatchers.IO) {
    val dao = AppDatabase.getAppDatabase(Shaft.getContext()).generalDao()
    val useRemote = SessionManager.loggedInUid > 0L &&
        Shaft.sSettings.isCloudHistorySync && Shaft.sSettings.isCloudHistoryConsentShown
    entities.forEach { entity ->
        dao.deleteByRecordTypeAndId(RecordType.VIEW_USER_HISTORY, entity.id)
        if (useRemote) {
            runCatching {
                Client.pixshaft.deleteHistory(SessionManager.loggedInUid, "user", entity.id)
            }.onFailure { Timber.w(it, "remote user-history delete failed (local deleted)") }
        }
    }
}

// ── Renderer（原 HistoryUserViewHolder 的绑定逻辑）。FragmentHistoryUserList 扩展。──

fun FragmentHistoryUserList.historyUserRenderer(): FeedRenderer<HistoryUserFeedItem, CellHistoryUserBinding> =
    feedRenderer(
        inflate = CellHistoryUserBinding::inflate,
        create = { cell ->
            // 监听只挂一次,点击那一刻经 cell.item 取当下条目(框架约定:绑定零 lambda 分配)
            val binding = cell.binding
            binding.root.setOnClickListener { v ->
                val item = cell.itemOrNull ?: return@setOnClickListener
                if (item.isSelectionMode) {
                    toggleUserHistorySelect(item.entity)
                } else {
                    v.context.startActivity(Intent(v.context, UActivity::class.java).apply {
                        putExtra(Params.USER_ID, item.entity.id.toInt())
                    })
                }
            }
            binding.root.setOnLongClickListener {
                val item = cell.itemOrNull ?: return@setOnLongClickListener false
                if (!item.isSelectionMode) confirmDeleteUserHistory(item.entity)
                true
            }
            binding.deleteItem.setOnClickListener {
                cell.itemOrNull?.let { item -> confirmDeleteUserHistory(item.entity) }
            }
        },
        recycle = { it.binding.userAvatar.clearGlideOnRecycle() },
        // 选择态变化只刷勾标与删除钮，不重跑头像 Glide。理由见 PAYLOAD_HISTORY_SELECTION。
        //
        // 本 tab 的 equals 是 data class 自动生成的（entity + user + 两个选择态），而 GeneralEntity
        // 与 User 参与的是各自的 equals；选择态回灌走 copy()，两个引用都原样带过来，因此这里按
        // 引用判「内容没变」比 equals 更严格 —— 严格的那一侧只会多回落全量绑定，不会漏刷。
        changePayload = { old, new ->
            if (old.entity === new.entity &&
                old.user === new.user &&
                (old.isSelectionMode != new.isSelectionMode || old.isSelected != new.isSelected)
            ) {
                PAYLOAD_HISTORY_SELECTION
            } else {
                null
            }
        },
        bindPayloads = { cell, payloads ->
            val item = cell.item
            HistorySelectBadge.bindSelectionPayload(
                payloads, cell.binding.selectCheck, cell.binding.deleteItem,
                item.isSelectionMode, item.isSelected,
            )
        },
    ) { cell ->
        val binding = cell.binding
        val item = cell.item
        val entity = item.entity
        val context = binding.root.context

        val user = item.user
        binding.userName.text = user?.name ?: "User #${entity.id}"
        binding.visitTime.text = userTimeFormat.format(entity.updatedTime)
        val avatarUrl = user?.profile_image_urls?.medium
        if (!avatarUrl.isNullOrEmpty()) {
            Glide.with(context).load(GlideUtil.getUrl(avatarUrl)).into(binding.userAvatar)
        }

        HistorySelectBadge.bindSelection(
            binding.selectCheck, binding.deleteItem, item.isSelectionMode, item.isSelected,
        )
    }
