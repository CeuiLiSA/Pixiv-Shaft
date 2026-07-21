package ceui.pixiv.download.config

import ceui.lisa.activities.Shaft
import ceui.pixiv.download.DownloadsRegistry
import timber.log.Timber

/**
 * 备份 / 还原整份 V3 下载配置（「设置 · 下载」里的下载路径、文件名、文件重复时、
 * 多图页码起始、页码补零、仅 WiFi 下载）。
 *
 * 两条链路共用这里，格式也保持一致：
 *  - 设置页导出 / 导入的 `Shaft-Backup.json`（[ceui.lisa.utils.BackupUtils]）
 *  - 云同步 payload 里的 `downloadConfigV3`（[ceui.loxia.MoonSync]）
 *
 * 还原是 **merge 而不是整份覆盖**：storage（存储位置）跟设备走，备份里的 SAF
 * treeUri 换台机器或重装后并没有 persistable 权限，照抄回来会让下载全部失败。
 * 所以只有本机确实还持有写权限的 SAF 才会被还原（同机重装、同机导入导出的
 * 常见场景仍然生效），否则保留本机当前的存储位置；模板 / 重复策略 / 各开关
 * 则照单全收。
 */
object DownloadConfigBackup {

    /** 当前配置的 JSON 快照，写进备份文件 / 云端 payload。 */
    @JvmStatic
    fun export(): String = DownloadConfigJson.toJson(DownloadsRegistry.store.loadOrFallback())

    /**
     * 把备份里的配置 merge 回本地并落盘。空内容 / 解析失败 / 版本比本地新都直接
     * 跳过并返回 false —— 备份的其余部分（settings、屏蔽词、搜索历史……）不受影响。
     *
     * @return true 表示确实写回了配置
     */
    @JvmStatic
    fun restore(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        val parsed = try {
            DownloadConfigJson.fromJson(json)
        } catch (t: Throwable) {
            Timber.w(t, "DownloadConfigBackup: 下载配置解析失败，跳过")
            return false
        }
        // Gson 走 Unsafe 造对象，字段缺失时即使 Kotlin 声明成非空也会拿到 null，
        // 所以手改过的备份文件必须在这里挡掉，别等到渲染模板时才 NPE。
        @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
        val incoming = parsed.takeIf {
            it != null && it.defaults != null && it.defaults.storage != null &&
                    !it.defaults.template.isNullOrBlank()
        }?.let { it.copy(perBucket = it.perBucket ?: emptyMap()) }
        if (incoming == null) {
            Timber.w("DownloadConfigBackup: 备份里的下载配置不完整，跳过")
            return false
        }
        if (incoming.version > DownloadConfig.VERSION) {
            Timber.w(
                "DownloadConfigBackup: 备份配置版本 %d 高于本地 %d，跳过",
                incoming.version, DownloadConfig.VERSION,
            )
            return false
        }
        return try {
            DownloadsRegistry.store.update { local -> merge(local, incoming) }
            // 存储位置可能变了，缓存的 backend 必须丢掉重建。
            DownloadsRegistry.invalidateBackends()
            Timber.i(
                "DownloadConfigBackup: 已还原下载配置（perBucket=%d wifiOnly=%b pageFrom1=%b）",
                incoming.perBucket.size, incoming.wifiOnly, incoming.pageIndexFrom1,
            )
            true
        } catch (t: Throwable) {
            Timber.e(t, "DownloadConfigBackup: 写回下载配置失败")
            false
        }
    }

    /**
     * 备份配置合并进本地配置。[isUsableHere] 决定备份里的存储位置在本机是否可用，
     * 单测里可以注入假实现。
     */
    @JvmStatic
    @JvmOverloads
    fun merge(
        local: DownloadConfig,
        incoming: DownloadConfig,
        isUsableHere: (StorageChoice) -> Boolean = ::usableOnThisDevice,
    ): DownloadConfig {
        val mergedDefaults = local.defaults.copy(
            template = incoming.defaults.template,
            overwrite = incoming.defaults.overwrite,
            storage = incoming.defaults.storage.takeIf(isUsableHere) ?: local.defaults.storage,
        )
        val mergedPerBucket = local.perBucket.toMutableMap()
        for ((bucket, incomingBucket) in incoming.perBucket) {
            val localBucket = mergedPerBucket[bucket]
            mergedPerBucket[bucket] = BucketConfig(
                template = incomingBucket.template ?: localBucket?.template,
                // null 表示继承 defaults.storage，本身就是合法状态
                storage = incomingBucket.storage?.takeIf(isUsableHere) ?: localBucket?.storage,
                overwrite = incomingBucket.overwrite ?: localBucket?.overwrite,
            )
        }
        return local.copy(
            defaults = mergedDefaults,
            perBucket = mergedPerBucket,
            wifiOnly = incoming.wifiOnly,
            pageIndexFrom1 = incoming.pageIndexFrom1,
            padPageNumber = incoming.padPageNumber,
        )
    }

    /** SAF 目录只在本机还握着 persistable 写权限时可用；其他存储位置都可以跨设备。 */
    private fun usableOnThisDevice(choice: StorageChoice): Boolean = when (choice) {
        is StorageChoice.Saf -> runCatching {
            Shaft.getContext().contentResolver.persistedUriPermissions
                .any { it.uri == choice.treeUri && it.isWritePermission }
        }.getOrDefault(false)

        else -> true
    }
}
