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
     * 搜索首页那一排横向格子的上限。那里只是个快捷入口，超出的部分在「我置顶的内容」页看，
     * 且这条链路和它旁边的置顶标签一样跑在主线程上，不能不设防。
     */
    private const val SEARCH_ROW_LIMIT = 100

    /**
     * 全量取，**不设上限**。
     * 「我置顶的内容 → 作者」是这份数据的完整列表页，截断就是 issue #524 那个坑的翻版
     * （置顶标签当年就是被塞进「最近搜索」的 LIMIT 里静默挤掉的，所以 searchDao().getAllPinned()
     * 至今没有 LIMIT）—— 列表页少显示几条，用户只会以为置顶丢了。
     */
    @JvmStatic
    fun loadAll(context: Context): List<User> = query(context, Int.MAX_VALUE)

    /** 搜索首页那一排用，带上限，理由见 [SEARCH_ROW_LIMIT]。 */
    @JvmStatic
    fun loadForSearchRow(context: Context): List<User> = query(context, SEARCH_ROW_LIMIT)

    /** 按置顶时间倒序。JSON 坏掉的行跳过，不让一条脏数据把整排作者干掉。 */
    private fun query(context: Context, limit: Int): List<User> {
        return AppDatabase.getAppDatabase(context).generalDao()
            .getByRecordType(RecordType.PINNED_USER, 0, limit)
            .mapNotNull { entity ->
                runCatching { entity.typedObject<User>() }
                    .onFailure { Timber.w(it, "pinned user ${entity.id} json broken, skipped") }
                    .getOrNull()
            }
    }
}
