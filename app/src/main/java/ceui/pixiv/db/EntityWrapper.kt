package ceui.pixiv.db

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.loxia.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber


class EntityWrapper(
    private val impl: AppDatabase,
) {

    private val _blockingIllustIds = mutableSetOf<Long>()
    private val _blockingUserIds = mutableSetOf<Long>()
    private val _blockingNovelIds = mutableSetOf<Long>()

    fun initialize() {
        MainScope().launch {
            withContext(Dispatchers.IO) {
                _blockingIllustIds.addAll(
                    impl.generalDao().getAllIdsByRecordType(RecordType.BLOCK_ILLUST)
                )
                _blockingUserIds.addAll(
                    impl.generalDao().getAllIdsByRecordType(RecordType.BLOCK_USER)
                )
                _blockingNovelIds.addAll(
                    impl.generalDao().getAllIdsByRecordType(RecordType.BLOCK_USER)
                )
            }
        }
    }

    // 通用插入方法
    private suspend fun insertEntity(context: Context, entity: GeneralEntity) {
        try {
            impl.generalDao().insert(entity)
            if (entity.recordType == RecordType.BLOCK_ILLUST) {
                _blockingIllustIds.add(entity.id)
            } else if (entity.recordType == RecordType.BLOCK_USER) {
                _blockingUserIds.add(entity.id)
            } else if (entity.recordType == RecordType.BLOCK_NOVEL) {
                _blockingNovelIds.add(entity.id)
            }
            Timber.d("EntityWrapper insertEntity done ${entity.id}")
        } catch (ex: Exception) {
            Timber.e(ex, "Error inserting entity: ${entity.id}")
        }
    }

    // 通用删除方法
    private suspend fun deleteEntity(context: Context, recordType: Int, id: Long) {
        try {
            impl.generalDao().deleteByRecordTypeAndId(recordType, id)
            if (recordType == RecordType.BLOCK_ILLUST) {
                _blockingIllustIds.remove(id)
            } else if (recordType == RecordType.BLOCK_USER) {
                _blockingUserIds.remove(id)
            } else if (recordType == RecordType.BLOCK_NOVEL) {
                _blockingNovelIds.remove(id)
            }
            Timber.d("EntityWrapper deleteEntity done $id")
        } catch (ex: Exception) {
            Timber.e(ex, "Error deleting entity: $id")
        }
    }

    // 插入访问记录
    private fun visit(
        context: Context,
        id: Long,
        entityJson: String,
        entityType: Int,
        recordType: Int
    ) {
        MainScope().launch(Dispatchers.IO) {
            val entity = GeneralEntity(id, entityJson, entityType, recordType)
            insertEntity(context, entity)
        }
    }

    // 插入或删除块操作
    private fun block(
        context: Context,
        id: Long,
        entityJson: String,
        entityType: Int,
        recordType: Int
    ) {
        MainScope().launch(Dispatchers.IO) {
            val entity = GeneralEntity(id, entityJson, entityType, recordType)
            insertEntity(context, entity)
        }
    }

    // 调用 `visit` 方法
    fun visitIllust(context: Context, illust: Illust) {
        val json = Shaft.sGson.toJson(illust)
        visit(context, illust.id, json, EntityType.ILLUST, RecordType.VIEW_ILLUST_HISTORY)
    }

    fun visitNovel(context: Context, novel: Novel) {
        val json = Shaft.sGson.toJson(novel)
        visit(context, novel.id, json, EntityType.NOVEL, RecordType.VIEW_NOVEL_HISTORY)
    }

    fun visitUser(context: Context, user: User) {
        val json = Shaft.sGson.toJson(user)
        visit(context, user.id, json, EntityType.USER, RecordType.VIEW_USER_HISTORY)
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
}
