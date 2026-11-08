package ceui.pixiv.snapshot

import java.io.File
import java.util.UUID

/** 只在正式快照验证完整后回收备份；中断事务优先找回旧副本。 */
internal object SnapshotImportRecovery {
    private val backupName = Regex("^\\.backup_([A-Za-z0-9_-]+)_(\\d+)$")

    fun recover(root: File, valid: (File, String) -> Boolean) {
        val backups = root.listFiles().orEmpty().mapNotNull { file ->
            val match = backupName.matchEntire(file.name) ?: return@mapNotNull null
            val time = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            if (!file.isDirectory) return@mapNotNull null
            Triple(file, match.groupValues[1], time)
        }.sortedByDescending { it.third }
        for ((backup, id) in backups) {
            if (!valid(backup, id)) continue
            val target = File(root, id)
            if (valid(target, id)) {
                backup.deleteRecursively()
                continue
            }
            // 不完整的新目录也保留，恢复失败时能原样退回，不删除用户文件。
            val displaced = if (target.exists()) {
                File(root, ".incomplete_${id}_${UUID.randomUUID()}")
            } else null
            if (displaced != null && !target.renameTo(displaced)) continue
            if (!backup.renameTo(target) && displaced != null) displaced.renameTo(target)
        }
    }
}
