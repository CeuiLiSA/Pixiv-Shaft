package ceui.pixiv.download.config

import ceui.lisa.activities.Shaft
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.model.Bucket
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

    /**
     * 当前配置的 JSON 快照，写进备份文件 / 云端 payload。
     *
     * 绝不抛异常：备份链路 [ceui.lisa.utils.BackupUtils.writeBackupToFile] 里这段
     * 炸了就是整个备份崩掉。真出问题就返回空串——宁可这份备份少一段下载配置。
     */
    @JvmStatic
    fun export(): String = try {
        export(DownloadsRegistry.store.loadOrFallback())
    } catch (t: Throwable) {
        Timber.e(t, "DownloadConfigBackup: 导出下载配置失败，本次备份不含下载配置")
        ""
    }

    /** 已经拿到配置时的重载，省掉一次 MMKV 读。 */
    @JvmStatic
    fun export(config: DownloadConfig): String = DownloadConfigJson.toJson(config)

    /**
     * 把备份里的配置 merge 回本地并落盘。空内容 / 解析失败 / 版本比本地新都直接
     * 跳过并返回 false —— 备份的其余部分（settings、屏蔽词、搜索历史……）不受影响。
     *
     * @return true 表示确实写回了配置
     */
    @JvmStatic
    fun restore(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        val incoming = try {
            sanitize(DownloadConfigJson.fromJson(json))
        } catch (t: Throwable) {
            Timber.w(t, "DownloadConfigBackup: 下载配置解析失败，跳过")
            null
        } ?: return false
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
     * Gson 走 Unsafe 造对象，JSON 里缺字段时即使 Kotlin 声明成非空也会拿到 null，
     * 而非空 `copy(...)` 参数带着 null 进去会直接抛 IllegalArgumentException。手改过 /
     * 截断的备份文件就属于这种，统一堵在入口：必填项缺失整份判废，能推导的补默认值。
     */
    @Suppress("SENSELESS_COMPARISON", "UNNECESSARY_SAFE_CALL", "USELESS_ELVIS")
    private fun sanitize(parsed: DownloadConfig): DownloadConfig? {
        val defaults = parsed?.defaults
        if (defaults == null || defaults.storage == null || defaults.template.isNullOrBlank()) {
            Timber.w("DownloadConfigBackup: 备份里的下载配置不完整，跳过")
            return null
        }
        return parsed.copy(
            defaults = defaults.copy(overwrite = defaults.overwrite ?: OverwritePolicy.Replace),
            perBucket = parsed.perBucket ?: emptyMap(),
        )
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
        // 一份配置里同一个 SAF 目录会出现在 defaults 和每个 bucket 上，判定结果只跟
        // StorageChoice 有关，记一下省掉重复的 persistedUriPermissions 跨进程查询。
        val verdicts = HashMap<StorageChoice, Boolean>()
        val usable = { choice: StorageChoice -> verdicts.getOrPut(choice) { isUsableHere(choice) } }

        val mergedDefaults = local.defaults.copy(
            template = incoming.defaults.template,
            overwrite = incoming.defaults.overwrite,
            storage = incoming.defaults.storage.takeIf(usable) ?: local.defaults.storage,
        )
        val mergedPerBucket = local.perBucket.dropBrokenEntries()
        for ((bucket, incomingBucket) in incoming.perBucket.dropBrokenEntries()) {
            val localBucket = mergedPerBucket[bucket]
            mergedPerBucket[bucket] = BucketConfig(
                template = incomingBucket.template ?: localBucket?.template,
                // null 表示继承 defaults.storage，本身就是合法状态
                storage = incomingBucket.storage?.takeIf(usable) ?: localBucket?.storage,
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

    /**
     * 丢掉 Gson 可能造出的 null key / null value。JSON 里若有本版本已经删掉的 Bucket：
     * 枚举 key 解析成 null，写回时变成一个 `"null"` 键一直赖在配置里；value 为 null 时
     * 下面按非空用就是 NPE，整份还原都会被跳过。本地存量配置同样可能有，两边都过一遍。
     */
    @Suppress("SENSELESS_COMPARISON")
    private fun Map<Bucket, BucketConfig>.dropBrokenEntries(): MutableMap<Bucket, BucketConfig> =
        filterTo(LinkedHashMap()) { (bucket, config) -> bucket != null && config != null }

    /** SAF 目录只在本机还握着 persistable 写权限时可用；其他存储位置都可以跨设备。 */
    private fun usableOnThisDevice(choice: StorageChoice): Boolean = when (choice) {
        is StorageChoice.Saf -> runCatching {
            Shaft.getContext().contentResolver.persistedUriPermissions
                .any { it.uri == choice.treeUri && it.isWritePermission }
        }.getOrDefault(false)

        else -> true
    }
}
