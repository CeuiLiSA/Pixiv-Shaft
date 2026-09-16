package ceui.pixiv.debug

import java.io.File

/**
 * 日志文件的**分代轮转策略**，只认 [File]，不碰 Android、不碰 Timber。
 *
 * 单拎出来的理由有两条：
 * - 这段逻辑的出错方式都很安静 —— 下标差一位就是「多删一代」或者「导出时新旧颠倒」，
 *   编译器和真机冒烟都发现不了，只有单测能钉死（见 `LogFileRotationTest`）；
 * - [TimberFileTree] 剩下的部分全是 Android 依赖（SystemClock / Log），进不了 JVM 单测。
 *
 * 命名：当前文件固定叫 [CURRENT_NAME]，历史代按 `shaft-log.1.txt`（最新的历史）到
 * `shaft-log.N.txt`（最老）编号 —— 数字越大越旧，这是 logrotate 的既有约定。
 */
object LogFileRotation {

    /** 当前正在写的那一份。 */
    const val CURRENT_NAME = "shaft-log.txt"

    /** 保留几代历史（不含当前）。总占用上限 = (KEEP_GENERATIONS + 1) × 单文件上限。 */
    const val KEEP_GENERATIONS = 3

    fun generationName(index: Int): String = "shaft-log.$index.txt"

    /**
     * 整体降一代：最老的一代删掉，其余依次后移，当前文件变成第 1 代。
     *
     * 必须**从老到新**遍历着改名，反过来会把还没搬走的那一代覆盖掉。
     */
    fun rotate(dir: File, keep: Int = KEEP_GENERATIONS) {
        runCatching { File(dir, generationName(keep)).delete() }
        for (i in keep - 1 downTo 1) {
            val from = File(dir, generationName(i))
            if (from.isFile) runCatching { from.renameTo(File(dir, generationName(i + 1))) }
        }
        val current = File(dir, CURRENT_NAME)
        if (current.isFile) runCatching { current.renameTo(File(dir, generationName(1))) }
    }

    /**
     * 现存的各代日志，**从旧到新**。
     *
     * 合并导出要按这个顺序：读日志的人是从上往下顺着时间看的，倒过来等于没法读。
     * 空文件跳过 —— 刚轮转完的新文件只有一行会话头，混进导出只是噪音。
     */
    fun existingOldestFirst(dir: File, keep: Int = KEEP_GENERATIONS): List<File> {
        val ordered = ArrayList<File>(keep + 1)
        for (i in keep downTo 1) {
            val f = File(dir, generationName(i))
            if (f.isFile && f.length() > 0L) ordered += f
        }
        val current = File(dir, CURRENT_NAME)
        if (current.isFile && current.length() > 0L) ordered += current
        return ordered
    }
}
