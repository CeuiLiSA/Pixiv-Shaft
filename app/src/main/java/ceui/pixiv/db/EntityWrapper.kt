package ceui.pixiv.db

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.loxia.Novel
import ceui.loxia.User
import ceui.pixiv.api.model.Illust
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap


class EntityWrapper(
    private val context: Context
) {

    // 这几个 id 集合都是「IO 线程写(initialize/insert/delete/clear),主线程读
    // (isWorkBlocked/isInWatchLater)」,必须用并发 set;普通 HashSet 跨线程读写有可见性/并发问题。
    private val _blockingIllustIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val _blockingUserIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val _blockingNovelIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    // 稍后再看的插画 id,内存缓存用于长按菜单即时判断「已加入 / 未加入」,避免每次查 DB。
    private val _watchLaterIllustIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    // 稍后再看的小说 id（与插画各占一个 recordType，见 RecordType.WATCH_LATER_NOVEL 的注释：
    // 插画号段与小说号段会撞，共用 recordType 会在 (id, recordType) 主键上互相覆盖）。
    private val _watchLaterNovelIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    // 置顶作者的 uid，作者主页「更多」菜单要即时判断摆「置顶」还是「取消置顶」，不能等一次 DB 往返。
    private val _pinnedUserIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun initialize() {
        MainScope().launch {
            withContext(Dispatchers.IO) {
                val database = AppDatabase.getAppDatabase(context)

                _blockingIllustIds.addAll(database.generalDao().getAllIdsByRecordType(RecordType.BLOCK_ILLUST))
                _blockingUserIds.addAll(database.generalDao().getAllIdsByRecordType(RecordType.BLOCK_USER))
                _blockingNovelIds.addAll(database.generalDao().getAllIdsByRecordType(RecordType.BLOCK_NOVEL))
                _watchLaterIllustIds.addAll(database.generalDao().getAllIdsByRecordType(RecordType.WATCH_LATER))
                _watchLaterNovelIds.addAll(database.generalDao().getAllIdsByRecordType(RecordType.WATCH_LATER_NOVEL))
                _pinnedUserIds.addAll(database.generalDao().getAllIdsByRecordType(RecordType.PINNED_USER))
            }
        }
    }

    // 通用插入方法
    private suspend fun insertEntity(context: Context, entity: GeneralEntity) {
        try {
            AppDatabase.getAppDatabase(context).generalDao().insert(entity)
            if (entity.recordType == RecordType.BLOCK_ILLUST) {
                _blockingIllustIds.add(entity.id)
            } else if (entity.recordType == RecordType.BLOCK_USER) {
                _blockingUserIds.add(entity.id)
            } else if (entity.recordType == RecordType.BLOCK_NOVEL) {
                _blockingNovelIds.add(entity.id)
            } else if (entity.recordType == RecordType.WATCH_LATER) {
                _watchLaterIllustIds.add(entity.id)
            } else if (entity.recordType == RecordType.WATCH_LATER_NOVEL) {
                _watchLaterNovelIds.add(entity.id)
            } else if (entity.recordType == RecordType.PINNED_USER) {
                _pinnedUserIds.add(entity.id)
            }
            Timber.d("EntityWrapper insertEntity done ${entity.id}")
        } catch (ex: Exception) {
            Timber.e(ex, "Error inserting entity: ${entity.id}")
        }
    }

    // 通用删除方法
    private suspend fun deleteEntity(context: Context, recordType: Int, id: Long) {
        try {
            AppDatabase.getAppDatabase(context).generalDao().deleteByRecordTypeAndId(recordType, id)
            if (recordType == RecordType.BLOCK_ILLUST) {
                _blockingIllustIds.remove(id)
            } else if (recordType == RecordType.BLOCK_USER) {
                _blockingUserIds.remove(id)
            } else if (recordType == RecordType.BLOCK_NOVEL) {
                _blockingNovelIds.remove(id)
            } else if (recordType == RecordType.WATCH_LATER) {
                _watchLaterIllustIds.remove(id)
            } else if (recordType == RecordType.WATCH_LATER_NOVEL) {
                _watchLaterNovelIds.remove(id)
            } else if (recordType == RecordType.PINNED_USER) {
                _pinnedUserIds.remove(id)
            }
            Timber.d("EntityWrapper deleteEntity done $id")
        } catch (ex: Exception) {
            Timber.e(ex, "Error deleting entity: $id")
        }
    }

    // 插入访问记录
    private fun visit(context: Context, id: Long, entityJson: String, entityType: Int, recordType: Int) {
        MainScope().launch(Dispatchers.IO) {
            val entity = GeneralEntity(id, entityJson, entityType, recordType)
            insertEntity(context, entity)
        }
    }

    // 插入或删除块操作
    private fun block(context: Context, id: Long, entityJson: String, entityType: Int, recordType: Int) {
        MainScope().launch(Dispatchers.IO) {
            val entity = GeneralEntity(id, entityJson, entityType, recordType)
            insertEntity(context, entity)
        }
    }

    // 调用 `visit` 方法
    fun visitIllust(context: Context, illust: Illust) {
        val json = Shaft.sGson.toJson(illust)
        // local-only: the FragmentHistoryTabs illust/novel tabs read illust_table
        // (legacy Illust), reported separately from PixivOperate. Reporting
        // ceui.pixiv.api.model.Illust here would pollute the remote with the wrong model.
        visit(context, illust.id, json, EntityType.ILLUST, RecordType.VIEW_ILLUST_HISTORY)
    }

    fun visitNovel(context: Context, novel: Novel) {
        val json = Shaft.sGson.toJson(novel)
        visit(context, novel.id, json, EntityType.NOVEL, RecordType.VIEW_NOVEL_HISTORY)
    }

    fun visitUser(context: Context, user: User) {
        val json = Shaft.sGson.toJson(user)
        // user tab reads general_table (ceui.loxia.User) — same model, so report here.
        visit(context, user.id, json, EntityType.USER, RecordType.VIEW_USER_HISTORY)
        // isolated: a report failure must never affect visiting a user page.
        runCatching { HistoryReporter.enqueue("user", user.id, Shaft.sGson.toJsonTree(user)) }
    }

    // 调用 `block` 方法
    fun blockIllust(context: Context, illust: Illust) {
        val json = Shaft.sGson.toJson(illust)
        block(context, illust.id, json, EntityType.ILLUST, RecordType.BLOCK_ILLUST)
    }

    fun blockNovel(context: Context, novel: Novel) {
        val json = Shaft.sGson.toJson(novel)
        block(context, novel.id, json, EntityType.NOVEL, RecordType.BLOCK_NOVEL)
    }

    fun blockUser(context: Context, user: User) {
        val json = Shaft.sGson.toJson(user)
        block(context, user.id, json, EntityType.USER, RecordType.BLOCK_USER)
    }

    // 调用删除方法
    fun unblockIllust(context: Context, illust: Illust) {
        MainScope().launch(Dispatchers.IO) {
            deleteEntity(context, RecordType.BLOCK_ILLUST, illust.id)
        }
    }

    fun unblockNovel(context: Context, novel: Novel) {
        MainScope().launch(Dispatchers.IO) {
            deleteEntity(context, RecordType.BLOCK_NOVEL, novel.id)
        }
    }

    fun unblockUser(context: Context, user: User) {
        MainScope().launch(Dispatchers.IO) {
            deleteEntity(context, RecordType.BLOCK_USER, user.id)
        }
    }

    fun isWorkBlocked(illustId: Long): Boolean {
        return _blockingIllustIds.contains(illustId)
    }

    // ---- 稍后再看 ----

    fun addToWatchLater(context: Context, illust: Illust) {
        val json = Shaft.sGson.toJson(illust)
        MainScope().launch(Dispatchers.IO) {
            insertEntity(context, GeneralEntity(illust.id, json, EntityType.ILLUST, RecordType.WATCH_LATER))
            notifyWatchLaterChanged(EntityType.ILLUST)
        }
    }

    fun addNovelToWatchLater(context: Context, novel: Novel) {
        val json = Shaft.sGson.toJson(novel)
        MainScope().launch(Dispatchers.IO) {
            insertEntity(context, GeneralEntity(novel.id, json, EntityType.NOVEL, RecordType.WATCH_LATER_NOVEL))
            notifyWatchLaterChanged(EntityType.NOVEL)
        }
    }

    fun removeFromWatchLater(context: Context, illustId: Long) {
        MainScope().launch(Dispatchers.IO) {
            deleteEntity(context, RecordType.WATCH_LATER, illustId)
            notifyWatchLaterChanged(EntityType.ILLUST)
        }
    }

    fun removeNovelFromWatchLater(context: Context, novelId: Long) {
        MainScope().launch(Dispatchers.IO) {
            deleteEntity(context, RecordType.WATCH_LATER_NOVEL, novelId)
            notifyWatchLaterChanged(EntityType.NOVEL)
        }
    }

    fun clearIllustWatchLater(context: Context) {
        MainScope().launch(Dispatchers.IO) {
            AppDatabase.getAppDatabase(context).generalDao().deleteAllByRecordType(RecordType.WATCH_LATER)
            _watchLaterIllustIds.clear()
            notifyWatchLaterChanged(EntityType.ILLUST)
        }
    }

    fun clearNovelWatchLater(context: Context) {
        MainScope().launch(Dispatchers.IO) {
            AppDatabase.getAppDatabase(context).generalDao().deleteAllByRecordType(RecordType.WATCH_LATER_NOVEL)
            _watchLaterNovelIds.clear()
            notifyWatchLaterChanged(EntityType.NOVEL)
        }
    }

    // 插画与小说各查各的集合：两条 id 序列会撞号，取并集会把「隔壁那个同号作品已加入」
    // 误报成本作品已加入，再点一下就把隔壁那条删了。
    fun isInWatchLater(illustId: Long): Boolean {
        return _watchLaterIllustIds.contains(illustId)
    }

    fun isNovelInWatchLater(novelId: Long): Boolean {
        return _watchLaterNovelIds.contains(novelId)
    }

    // ---- 置顶作者 ----

    /**
     * 置顶一个作者。存全量 User JSON —— 搜索首页那排要画头像和昵称，只存 uid 的话每次进
     * 搜索页都得为每个置顶作者发一次 detail 请求，离线时还什么都画不出来。
     * 昵称/头像会过期，但点进去就是作者主页，那里拿的是新鲜数据；这份快照只用于那一排格子。
     */
    fun pinUser(context: Context, user: User) {
        val json = Shaft.sGson.toJson(user)
        MainScope().launch(Dispatchers.IO) {
            insertEntity(context, GeneralEntity(user.id, json, EntityType.USER, RecordType.PINNED_USER))
        }
    }

    fun unpinUser(context: Context, userId: Long) {
        MainScope().launch(Dispatchers.IO) {
            deleteEntity(context, RecordType.PINNED_USER, userId)
        }
    }

    fun isUserPinned(userId: Long): Boolean {
        return _pinnedUserIds.contains(userId)
    }

    /**
     * 取消置顶并**等落库完成**。列表页删完要立刻重查 DB，用上面那个 fire-and-forget 的
     * [unpinUser] 会和重查赛跑、刷出还没删掉的旧行。菜单那种「点完就走」的调用点仍用 [unpinUser]。
     */
    suspend fun deletePinnedUser(context: Context, userId: Long) = withContext(Dispatchers.IO) {
        deleteEntity(context, RecordType.PINNED_USER, userId)
    }

    /** 清空置顶作者，同样等落库完成（理由见 [deletePinnedUser]）。 */
    suspend fun clearPinnedUsers(context: Context) = withContext(Dispatchers.IO) {
        AppDatabase.getAppDatabase(context).generalDao()
            .deleteAllByRecordType(RecordType.PINNED_USER)
        _pinnedUserIds.clear()
    }

    /**
     * 稍后再看列表变更后发本地广播，对应 tab 收到重新拉 DB。
     * LocalBroadcastManager.sendBroadcast 内部 post 到主线程，IO 线程调也安全。
     *
     * 带上 [EXTRA_ENTITY_TYPE]：插画 tab 和小说 tab 是同时活着的（ViewPager 离屏页也建视图），
     * 不分流的话，加一本小说会连带把整张插画表重读一遍并逐条 Gson 反序列化 —— 那边一个字节
     * 都没变。收方按类型早退即可。
     */
    private fun notifyWatchLaterChanged(entityType: Int) {
        LocalBroadcastManager.getInstance(context).sendBroadcast(
            Intent(ACTION_WATCH_LATER_CHANGED).putExtra(EXTRA_ENTITY_TYPE, entityType)
        )
    }

    companion object {
        const val ACTION_WATCH_LATER_CHANGED = "ceui.pixiv.action.WATCH_LATER_CHANGED"

        /** 本次变更的作品类型（[EntityType]）。读不到就当「两边都可能变了」，各自照常刷。 */
        const val EXTRA_ENTITY_TYPE = "entityType"
    }
}
