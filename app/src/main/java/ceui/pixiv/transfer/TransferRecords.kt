package ceui.pixiv.transfer

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.utils.BackupUtils
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.db.RecordType
import ceui.pixiv.ui.synonym.SynonymDictBackup
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.io.IOException

/** Portable records in addition to the regular Shaft backup. */
internal object TransferRecords {
    private val recordTypes = setOf(
        RecordType.VIEW_ILLUST_HISTORY, RecordType.VIEW_NOVEL_HISTORY, RecordType.VIEW_USER_HISTORY,
        RecordType.BLOCK_ILLUST, RecordType.BLOCK_NOVEL, RecordType.BLOCK_USER,
        RecordType.WATCH_LATER, RecordType.WATCH_LATER_NOVEL,
    )

    @JvmStatic
    fun write(context: Context, writer: JsonWriter, checkActive: Runnable) {
        val dao = AppDatabase.getAppDatabase(context).generalDao()
        writer.beginObject()
        writer.name("records").beginArray()
        for (type in recordTypes) {
            var offset = 0
            while (true) {
                checkActive.run()
                val page = dao.getByRecordType(type, offset, BackupUtils.HISTORY_BATCH_SIZE)
                page.forEach { Shaft.sGson.toJson(it, GeneralEntity::class.java, writer) }
                if (page.size < BackupUtils.HISTORY_BATCH_SIZE) break
                offset += page.size
            }
        }
        writer.endArray()
        writer.name("synonyms").value(SynonymDictBackup.exportToJson(context).first)
        writer.endObject()
    }

    @JvmStatic
    fun read(context: Context, reader: JsonReader, checkActive: Runnable) {
        val dao = AppDatabase.getAppDatabase(context).generalDao()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "records" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        checkActive.run()
                        val incoming = Shaft.sGson.fromJson<GeneralEntity>(reader, GeneralEntity::class.java)
                        if (incoming.recordType !in recordTypes) throw IOException("Unsupported record type")
                        val local = dao.getByRecordTypeAndId(incoming.recordType, incoming.id)
                        if (local == null || local.updatedTime < incoming.updatedTime) dao.insert(incoming)
                    }
                    reader.endArray()
                }
                "synonyms" -> SynonymDictBackup.importFromJson(context, reader.nextString())
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }
}
