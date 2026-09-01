package ceui.pixiv.ui.pinned

import android.content.Context
import ceui.lisa.database.AppDatabase
import ceui.pixiv.db.RecordType
import ceui.loxia.User
import timber.log.Timber

/**
 * 置顶作者的读取口（写入在 [ceui.pixiv.db.EntityWrapper.pinUser] / unpinUser）。
 *
 * 存的是 general_table 里 recordType=[RecordType.PINNED_USER] 的 [User] 全量 JSON，
 * 和「置顶标签」（search_table.pinned 的搜索历史行）是两套完全独立的数据 —— 用户反馈的
 * 正是「搜作者再置顶搜索词」会挤占置顶标签的格子。
 */
object PinnedUsers {

    /**
     * 一次最多取这么多。置顶是手挑的，几十个封顶；给个上限只是别让某天写脏的表
     * 把搜索首页的 onResume 拖住（这条链路和它旁边的置顶标签一样跑在主线程上）。
     */
    private const val MAX_COUNT = 100

    /** 按置顶时间倒序。JSON 坏掉的行跳过，不让一条脏数据把整排作者干掉。 */
    @JvmStatic
    fun load(context: Context): List<User> {
        return AppDatabase.getAppDatabase(context).generalDao()
            .getByRecordType(RecordType.PINNED_USER, 0, MAX_COUNT)
            .mapNotNull { entity ->
                runCatching { entity.typedObject<User>() }
                    .onFailure { Timber.w(it, "pinned user ${entity.id} json broken, skipped") }
                    .getOrNull()
            }
    }
}
