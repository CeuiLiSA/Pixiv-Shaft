package ceui.pixiv.db.mirror

import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * 把 [SupportSQLiteQuery] 的绑定值抓出来。
 *
 * `SimpleSQLiteQuery` 不暴露 args，只能通过 `bindTo` 往外吐 —— 拿一个只记账的
 * [SupportSQLiteProgram] 接住即可。索引是 1-based（SQLite 约定）。
 */
internal fun SupportSQLiteQuery.captureArgs(): List<Any?> {
    val recorder = ArgRecorder()
    bindTo(recorder)
    return recorder.toList()
}

private class ArgRecorder : SupportSQLiteProgram {
    private val slots = HashMap<Int, Any?>()
    private var maxIndex = 0

    private fun put(index: Int, value: Any?) {
        slots[index] = value
        if (index > maxIndex) maxIndex = index
    }

    fun toList(): List<Any?> = (1..maxIndex).map { slots[it] }

    override fun bindNull(index: Int) = put(index, null)
    override fun bindLong(index: Int, value: Long) = put(index, value)
    override fun bindDouble(index: Int, value: Double) = put(index, value)
    override fun bindString(index: Int, value: String) = put(index, value)
    override fun bindBlob(index: Int, value: ByteArray) = put(index, value)
    override fun clearBindings() {
        slots.clear()
        maxIndex = 0
    }
    override fun close() = Unit
}
