package ceui.pixiv.snapshot

import java.io.File
import java.io.IOException
import java.util.UUID

/** 只有明确完成的替换才回收旧备份；文件存在不能证明复制已经结束。 */
internal object SnapshotImportRecovery {
    private val backupName = Regex("^\\.backup_([A-Za-z0-9_-]+)_(\\d+)(\\.committed)?$")

    private data class Backup(val file: File, val id: String, val time: Long, val committed: Boolean)

    /** 全部目标文件写完后原子改名；状态留在库根目录，不会被导出或导入的 ZIP 携带。 */
    fun markCommitted(backup: File): File {
        if (!backup.exists()) return backup
        val committed = File(backup.parentFile, backup.name + ".committed")
        if (!backup.renameTo(committed)) throw IOException("cannot mark snapshot replacement complete")
        return committed
    }

    fun recover(
        root: File,
        valid: (File, String) -> Boolean,
        onRecovered: (File, String) -> Unit = { _, _ -> },
    ) {
        val backups = root.listFiles().orEmpty().mapNotNull { file ->
            val match = backupName.matchEntire(file.name) ?: return@mapNotNull null
            val time = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            if (!file.isDirectory) return@mapNotNull null
            Backup(file, match.groupValues[1], time, match.groupValues[3].isNotEmpty())
        }.sortedByDescending { it.time }
        val settled = mutableSetOf<String>()
        for ((backup, id, _, committed) in backups) {
            val target = File(root, id)
            // 提交标记落下时目标已整份写完，带标记的备份及更早的备份都已作废：删到一半被杀的
            // 不完整备份照样回收；目标之后被用户删掉也不能拿旧副本复活。
            if (committed || id in settled) {
                if (settled.add(id) && valid(target, id)) onRecovered(target, id)
                backup.deleteRecursively()
                continue
            }
            if (!valid(backup, id)) continue
            // 不完整的新目录也保留，恢复失败时能原样退回，不删除用户文件。
            val displaced = if (target.exists()) {
                File(root, ".incomplete_${id}_${UUID.randomUUID()}")
            } else null
            if (displaced != null && !target.renameTo(displaced)) continue
            if (backup.renameTo(target)) {
                settled.add(id)
                onRecovered(target, id)
            } else if (displaced != null) {
                displaced.renameTo(target)
            }
        }
    }
}
