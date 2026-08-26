package ceui.loxia

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.utils.BackupUtils
import ceui.lisa.utils.Common
import ceui.lisa.utils.Local
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.config.DownloadConfigBackup
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * moonAPI(自建后端)上的设置同步:登录后拉取 / 用户主动上传。
 *
 * 详细日志走 Timber tag `MoonSync`,过滤命令:`adb logcat -s MoonSync:*`
 * 网络请求/响应不写日志，避免同步内容或认证材料进入 logcat。
 */
object MoonSync {

    private const val TAG = "MoonSync"

    /** 服务端返回 429 时,从 Retry-After 头提取秒数,默认 60。 */
    private fun retryAfterSeconds(e: HttpException): Int =
        e.response()?.headers()?.get("Retry-After")?.toIntOrNull() ?: 60

    /** 本设备已应用的云端版本(按 uid 区分,支持多账号切换)。 */
    private fun localAppliedVersion(uid: Long): Int =
        Shaft.sSettings.moonAppliedVersions[uid.toString()] ?: 0

    private fun saveLocalAppliedVersion(uid: Long, version: Int) {
        val map = Shaft.sSettings.moonAppliedVersions
        map[uid.toString()] = version
        Shaft.sSettings.moonAppliedVersions = map
        Local.setSettings(Shaft.sSettings)
    }

    /**
     * 登录成功后调用。拉取云端 settings,如果版本与本设备已应用的版本号(按 uid 区分)
     * 不一致,弹出 QMUI 对话框询问是否应用并重启。无论结果如何(404/异常/用户取消),
     * 最终都会调一次 [onComplete] 由调用方继续后续登录流程,**除非**用户选择"应用"
     * 并触发了 restart——这种情况下进程会被重启,onComplete 不再调用。
     */
    @JvmStatic
    fun syncFromCloudOnLogin(
        activity: FragmentActivity,
        uid: Long,
        onComplete: Runnable,
    ) {
        Timber.tag(TAG).i("[sync] enter uid=%d", uid)
        if (uid <= 0L) {
            Timber.tag(TAG).w("[sync] skip: uid<=0")
            onComplete.run()
            return
        }
        activity.lifecycleScope.launch {
            val localApplied = localAppliedVersion(uid)
            Timber.tag(TAG).d("[sync] local applied=%d for uid=%d, GET /v1/settings/%d",
                localApplied, uid, uid)

            val cloud = try {
                Client.moonAPI.getSettings(uid)
            } catch (e: HttpException) {
                if (e.code() == 404) {
                    Timber.tag(TAG).i("[sync] cloud 404 (no remote settings yet)")
                } else {
                    Timber.tag(TAG).w(e, "[sync] HTTP %d %s", e.code(), e.message())
                }
                null
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "[sync] network/parse error")
                Common.showLog("moonAPI sync error: $e")
                null
            }

            if (cloud == null) {
                Timber.tag(TAG).d("[sync] no cloud data → onComplete")
                onComplete.run()
                return@launch
            }

            val payloadSize = cloud.payload.toString().toByteArray(Charsets.UTF_8).size
            Timber.tag(TAG).i(
                "[sync] cloud got: version=%d updatedAt=%s payload=%d bytes hasDC3=%b",
                cloud.version,
                prettyTime(cloud.updatedAt),
                payloadSize,
                cloud.payload.has("downloadConfigV3"),
            )

            if (cloud.version == localApplied) {
                Timber.tag(TAG).d("[sync] versions match (v%d) → onComplete", cloud.version)
                onComplete.run()
                return@launch
            }
            Timber.tag(TAG).i("[sync] version diff: local=%d cloud=%d → prompting user",
                localApplied, cloud.version)

            if (activity.isFinishing || activity.isDestroyed) {
                Timber.tag(TAG).w("[sync] activity gone before dialog → onComplete")
                onComplete.run()
                return@launch
            }

            WitDialog.MessageDialogBuilder(activity)
                .setTitle(R.string.moon_sync_title)
                .setMessage(
                    activity.getString(
                        R.string.moon_sync_message,
                        cloud.version,
                        prettyTime(cloud.updatedAt),
                    )
                )
                .addAction(activity.getString(R.string.moon_sync_ignore)) { d, _ ->
                    Timber.tag(TAG).i("[sync] user dismissed (ignore)")
                    d.dismiss()
                    onComplete.run()
                }
                .addAction(0, activity.getString(R.string.moon_sync_apply_action), WitDialogAction.ACTION_PROP_POSITIVE) { d, _ ->
                    Timber.tag(TAG).i("[sync] user accepted → applying v%d", cloud.version)
                    d.dismiss()
                    activity.lifecycleScope.launch {
                        val ok = applyCloudPayload(activity, uid, cloud)
                        if (ok) {
                            Timber.tag(TAG).i("[sync] apply OK, restarting (skip onComplete)")
                            Common.showToast(activity.getString(R.string.moon_sync_apply_success))
                            Common.restart()
                            // restart 后流程由新进程 handle,这里不再调 onComplete
                            // 避免 OutWakeActivity 的 R18 弹窗与 restart 竞争。
                        } else {
                            Timber.tag(TAG).e("[sync] apply FAILED → onComplete to continue R18 flow")
                            Common.showToast(activity.getString(R.string.moon_sync_apply_failed))
                            onComplete.run()
                        }
                    }
                }
                .create()
                .show()
        }
    }

    /**
     * 用户主动点"上传配置到云端"按钮时调用。打包内容:
     * settings + 屏蔽记录(BackupEntity 形状)+ 整份 V3 下载配置(envelope 字段)。
     */
    @JvmStatic
    fun uploadToCloud(activity: FragmentActivity, uid: Long) {
        Timber.tag(TAG).i("[upload] enter uid=%d", uid)
        if (uid <= 0L) {
            Timber.tag(TAG).w("[upload] skip: uid<=0 (not logged in)")
            Common.showToast(activity.getString(R.string.moon_login_required))
            return
        }
        activity.lifecycleScope.launch {
            try {
                val payload = withContext(Dispatchers.IO) {
                    val entity = BackupUtils.BackupEntity()
                    entity.settings = Shaft.sSettings
                    val mutes = AppDatabase.getAppDatabase(activity).searchDao().allMuteEntities
                    entity.muteEntityList = mutes
                    // downloadConfigV3 现在是 BackupEntity 自己的字段(和设置页导出的
                    // Shaft-Backup.json 同一份格式),不用再往 envelope 上手动挂。
                    val cfg = DownloadsRegistry.store.loadOrFallback()
                    entity.downloadConfigV3 = DownloadConfigBackup.export(cfg)
                    val obj = Shaft.sGson.toJsonTree(entity).asJsonObject

                    Timber.tag(TAG).d(
                        "[upload] packed: muteCount=%d v3Size=%d perBucket=%d wifiOnly=%b pageFrom1=%b",
                        mutes.size,
                        entity.downloadConfigV3.length,
                        cfg.perBucket.size,
                        cfg.wifiOnly,
                        cfg.pageIndexFrom1,
                    )
                    obj
                }

                val totalBytes = payload.toString().toByteArray(Charsets.UTF_8).size
                Timber.tag(TAG).i("[upload] PUT /v1/settings/%d (%d bytes)", uid, totalBytes)
                val ack = Client.moonAPI.putSettings(uid, payload)
                Timber.tag(TAG).i(
                    "[upload] ack: version=%d updatedAt=%s",
                    ack.version,
                    prettyTime(ack.updatedAt),
                )

                saveLocalAppliedVersion(uid, ack.version)
                Timber.tag(TAG).d("[upload] local applied[uid=%d] ← %d", uid, ack.version)

                Common.showToast(activity.getString(R.string.moon_upload_success, ack.version))
            } catch (e: HttpException) {
                val errBody = try { e.response()?.errorBody()?.string() } catch (_: Throwable) { null }
                Timber.tag(TAG).e(e, "[upload] HTTP %d body=%s", e.code(), errBody ?: "<none>")
                val msg = if (e.code() == 429) {
                    activity.getString(R.string.moon_rate_limited, retryAfterSeconds(e))
                } else {
                    activity.getString(R.string.moon_upload_failed_http, e.code())
                }
                Common.showToast(msg)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "[upload] failed")
                val reason = e.message ?: e.javaClass.simpleName
                Common.showToast(activity.getString(R.string.moon_upload_failed_other, reason))
            }
        }
    }

    /**
     * 用户在设置页主动触发"从云端同步"。与 [syncFromCloudOnLogin] 的区别是:
     * 所有结果都给用户即时反馈(404 / 已是最新 / 网络错误),而非静默 onComplete。
     */
    @JvmStatic
    fun manualSyncFromCloud(activity: FragmentActivity, uid: Long) {
        Timber.tag(TAG).i("[manual] enter uid=%d", uid)
        if (uid <= 0L) {
            Common.showToast(activity.getString(R.string.moon_login_required))
            return
        }
        activity.lifecycleScope.launch {
            val cloud = try {
                Client.moonAPI.getSettings(uid)
            } catch (e: HttpException) {
                when (e.code()) {
                    404 -> {
                        Timber.tag(TAG).i("[manual] cloud 404")
                        Common.showToast(activity.getString(R.string.moon_sync_no_remote))
                    }
                    429 -> {
                        Timber.tag(TAG).w(e, "[manual] HTTP 429 rate limited")
                        Common.showToast(
                            activity.getString(R.string.moon_rate_limited, retryAfterSeconds(e))
                        )
                    }
                    else -> {
                        Timber.tag(TAG).w(e, "[manual] HTTP %d", e.code())
                        Common.showToast(
                            activity.getString(R.string.moon_upload_failed_http, e.code())
                        )
                    }
                }
                return@launch
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "[manual] network/parse error")
                val reason = e.message ?: e.javaClass.simpleName
                Common.showToast(
                    activity.getString(R.string.moon_sync_fetch_error, reason)
                )
                return@launch
            }

            val localApplied = localAppliedVersion(uid)
            Timber.tag(TAG).i(
                "[manual] cloud v=%d updatedAt=%s local=%d",
                cloud.version, prettyTime(cloud.updatedAt), localApplied,
            )

            if (cloud.version == localApplied) {
                Common.showToast(
                    activity.getString(R.string.moon_sync_already_latest, cloud.version)
                )
                return@launch
            }

            if (activity.isFinishing || activity.isDestroyed) {
                Timber.tag(TAG).w("[manual] activity gone before dialog")
                return@launch
            }

            WitDialog.MessageDialogBuilder(activity)
                .setTitle(R.string.moon_sync_title)
                .setMessage(
                    activity.getString(
                        R.string.moon_sync_message,
                        cloud.version,
                        prettyTime(cloud.updatedAt),
                    )
                )
                .addAction(activity.getString(R.string.moon_sync_ignore)) { d, _ ->
                    Timber.tag(TAG).i("[manual] user dismissed")
                    d.dismiss()
                }
                .addAction(0, activity.getString(R.string.moon_sync_apply_action),
                    WitDialogAction.ACTION_PROP_POSITIVE) { d, _ ->
                    Timber.tag(TAG).i("[manual] user accepted → applying v%d", cloud.version)
                    d.dismiss()
                    activity.lifecycleScope.launch {
                        val ok = applyCloudPayload(activity, uid, cloud)
                        if (ok) {
                            Common.showToast(
                                activity.getString(R.string.moon_sync_apply_success)
                            )
                            Common.restart()
                        } else {
                            Common.showToast(
                                activity.getString(R.string.moon_sync_apply_failed)
                            )
                        }
                    }
                }
                .create()
                .show()
        }
    }

    /**
     * 把云端 payload 应用回本地:
     * - BackupEntity 部分(settings + muteEntityList + downloadConfigV3)走
     *   [BackupUtils.restoreBackups];下载配置的 merge 语义见 [DownloadConfigBackup]
     *   (只 merge 模板/策略,本机用不了的 SAF treeUri 不会被盖上来)
     * - 最后把当前 uid 的 applied version 设为本次 cloud.version
     */
    private suspend fun applyCloudPayload(
        activity: FragmentActivity,
        uid: Long,
        cloud: MoonAPI.MoonSettings,
    ): Boolean = withContext(Dispatchers.IO) {
        Timber.tag(TAG).d("[apply] start: cloud v%d", cloud.version)
        val payloadObj = cloud.payload
        // 「浏览记录同意框是否已弹过」是按设备的隐私状态(见 Settings.cloudHistoryConsentShown
        // 注释:每台设备一次)。从云端 payload 里剔掉,否则应用别的设备上传的配置会把
        // consentShown=true 带过来,新设备进浏览历史就再也不弹同意框了。
        val settingsEl = payloadObj.get("settings")
        if (settingsEl != null && settingsEl.isJsonObject) {
            settingsEl.asJsonObject.remove("cloudHistoryConsentShown")
        }
        val payloadJson = payloadObj.toString()

        val ok = BackupUtils.restoreBackups(activity, payloadJson)
        Timber.tag(TAG).i("[apply] BackupUtils.restoreBackups → %b", ok)
        if (!ok) return@withContext false

        saveLocalAppliedVersion(uid, cloud.version)
        Timber.tag(TAG).i("[apply] done; applied[uid=%d]=%d", uid, cloud.version)
        true
    }

    private fun prettyTime(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
}
