package ceui.pixiv.ui.history

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.IllustHistoryEntity
import ceui.lisa.utils.Local
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.db.HistoryBackfill
import ceui.pixiv.db.RecordType
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * 浏览记录的本地备份导出 / 导入(issue #890)。
 *
 * 历史已迁到云端(pixshaft-api)后,设置页那份 Shaft-Backup.json 还原进的是**本地**
 * illust_table,而开了云同步时历史页是**读云端**的,导入的本地行因此看不见。这里给浏览
 * 历史一个独立的导入/导出入口(对齐屏蔽记录那套 FragmentViewPager),并在导入后:
 * 关云同步 → 本地直接可见;开云同步 → 顺手把导入的条目推一份到云端,云端读取才显示得出来。
 *
 * 文件覆盖三类:插画/漫画 + 小说(都在 illust_table,靠 [IllustHistoryEntity.type] 区分)
 * 和用户(general_table 里 [RecordType.VIEW_USER_HISTORY] 那批)。
 */
object BrowseHistoryBackup {

    /** 本页导出的文件结构:两张表各一段,Gson 直接序列化 entity。 */
    data class Payload(
        val illustHistory: List<IllustHistoryEntity> = emptyList(),
        val userHistory: List<GeneralEntity> = emptyList(),
    )

    /**
     * 导入时的兼容壳:既吃本页导出的 [Payload],也吃设置页那份 Shaft-Backup.json
     * (BackupUtils.BackupEntity,历史字段名是 `illustHistoryEntityList`,且从不含用户历史)。
     * 用户填 #890 时手里多半是后者,读不了旧格式等于对存量用户没修。其余 settings/mute
     * 字段不映射,Gson 自动忽略——导历史绝不顺手覆盖别的配置。
     */
    private data class RawBackup(
        val illustHistory: List<IllustHistoryEntity>? = null,
        val illustHistoryEntityList: List<IllustHistoryEntity>? = null, // 旧 Shaft-Backup.json
        val userHistory: List<GeneralEntity>? = null,
    )

    /** 分页读库时的批大小,任何时刻内存里只有一批实体(#981)。 */
    private const val EXPORT_PAGE_SIZE = 500

    /** 有任何可导出的本地历史吗?空时调用方提示「无可导出」,不落盘空文件。 */
    fun hasAnythingToExport(context: Context): Boolean {
        val db = AppDatabase.getAppDatabase(context)
        return db.downloadDao().viewHistoryCount > 0 ||
            db.generalDao().getByRecordType(RecordType.VIEW_USER_HISTORY, 0, 1).isNotEmpty()
    }

    /**
     * 流式导出全部本地浏览历史到 [target],返回总条数。字段名与旧版
     * `Shaft.sGson.toJson(Payload)` 一致,导出的文件 [importFromJson] 照常认。
     * 之前是全表读出 + 全量 toJson 成巨型 String,大历史库直接 OOM(#981)。
     */
    fun exportToFile(context: Context, target: File): Int {
        val db = AppDatabase.getAppDatabase(context)
        var count = 0
        JsonWriter(OutputStreamWriter(FileOutputStream(target), Charsets.UTF_8).buffered()).use { writer ->
            writer.beginObject()
            writer.name("illustHistory")
            writer.beginArray()
            var offset = 0
            while (true) {
                val page = db.downloadDao().getAllViewHistory(EXPORT_PAGE_SIZE, offset).orEmpty()
                if (page.isEmpty()) break
                page.forEach { Shaft.sGson.toJson(it, IllustHistoryEntity::class.java, writer) }
                count += page.size
                if (page.size < EXPORT_PAGE_SIZE) break
                offset += page.size
            }
            writer.endArray()
            writer.name("userHistory")
            writer.beginArray()
            offset = 0
            while (true) {
                val page = db.generalDao().getByRecordType(RecordType.VIEW_USER_HISTORY, offset, EXPORT_PAGE_SIZE)
                if (page.isEmpty()) break
                page.forEach { Shaft.sGson.toJson(it, GeneralEntity::class.java, writer) }
                count += page.size
                if (page.size < EXPORT_PAGE_SIZE) break
                offset += page.size
            }
            writer.endArray()
            writer.endObject()
        }
        return count
    }

    /**
     * 解析 + 写本地库,返回导入条数。JSON 解析失败抛异常(调用方提示「格式不正确」)。
     *
     * 导入成功后清掉回填标记再触发 [HistoryBackfill]:云端推送统一走那一条通道
     * (keyset 分页、≤100/批、批间限速、失败中止下次重试、按服务端保留上限封顶),
     * 不再自己起一个无限速循环把服务端 60/min 的写限流打爆。条目带真实浏览时间,
     * 服务端只认「更新的浏览」——导入老备份不会把云端顺序刷成「刚刚看过」;代价是
     * 比云端保留窗口(每类最新 1000 条)更老的条目只活在本地,云端模式下翻不到它们。
     */
    suspend fun importFromJson(context: Context, json: String): Int = withContext(Dispatchers.IO) {
        val raw = Shaft.sGson.fromJson(json, RawBackup::class.java)
            ?: return@withContext 0
        val payload = Payload(
            illustHistory = raw.illustHistory ?: raw.illustHistoryEntityList ?: emptyList(),
            userHistory = raw.userHistory ?: emptyList(),
        )
        val db = AppDatabase.getAppDatabase(context)
        var imported = 0
        payload.illustHistory.forEach { e ->
            if (!e.illustJson.isNullOrEmpty() && e.illustID != 0) {
                db.downloadDao().insert(e)
                imported++
            }
        }
        payload.userHistory.forEach { e ->
            if (e.json.isNotEmpty() && e.id != 0L) {
                // recordType 兜底,防手改文件把它写歪导致这批不进「用户」tab。
                db.generalDao().insert(e.copy(recordType = RecordType.VIEW_USER_HISTORY))
                imported++
            }
        }
        if (imported > 0) {
            // 本地库刚被导入改写 → 回填标记作废,重跑一遍把新行推上云端(幂等,旧行全是 no-op)。
            Shaft.sSettings.cloudHistoryBackfillDoneUid = 0L
            Local.setSettings(Shaft.sSettings)
            HistoryBackfill.maybeSchedule()
        }
        imported
    }
}
