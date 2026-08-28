package ceui.pixiv.muzei

import android.content.Context
import androidx.annotation.StringRes
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import ceui.lisa.R
import ceui.lisa.activities.Shaft

/** Muzei 壁纸来源(issue #548)。`key` 落盘,别改;顺序即设置页单选顺序。 */
enum class MuzeiSource(val key: String, @StringRes val labelRes: Int) {
    DAILY_RANK("daily_rank", R.string.muzei_source_daily_rank),
    RECOMMEND("recommend", R.string.muzei_source_recommend),
    BOOKMARKS("bookmarks", R.string.muzei_source_bookmarks),
    WALLPAPER_RANK("wallpaper_rank", R.string.muzei_source_wallpaper_rank);

    companion object {
        fun fromKey(key: String?): MuzeiSource = entries.firstOrNull { it.key == key } ?: DAILY_RANK
    }
}

/**
 * Muzei 相关偏好 + 拉图任务的调度入口。直接走 MMKV 而不进 [ceui.lisa.utils.Settings] 的
 * Gson 大对象:Provider 可能在 Muzei 拉起的独立时机被访问,不想依赖 sSettings 已反序列化。
 */
object MuzeiPrefs {
    private const val KEY_SOURCE = "muzei_source"
    private const val KEY_ALLOW_R18 = "muzei_allow_r18"

    const val WORK_NAME = "muzei_load_artwork"
    const val INPUT_REPLACE = "replace"

    const val MUZEI_PACKAGE = "net.nurik.roman.muzei"

    fun authority(context: Context): String = "${context.packageName}.muzei"

    var source: MuzeiSource
        get() = MuzeiSource.fromKey(Shaft.getMMKV().decodeString(KEY_SOURCE))
        set(value) {
            Shaft.getMMKV().encode(KEY_SOURCE, value.key)
        }

    var allowR18: Boolean
        get() = Shaft.getMMKV().decodeBool(KEY_ALLOW_R18, false)
        set(value) {
            Shaft.getMMKV().encode(KEY_ALLOW_R18, value)
        }

    /**
     * 排一次拉图。[replace] = true 时用 setArtwork 整批替换(换来源 / 改 R18 开关后旧图不该再轮播),
     * 否则 addArtwork 追加(Muzei 自己维护最近 100 张)。
     *
     * 唯一任务 + REPLACE:Muzei 在图快轮完时会连续 onLoadRequested,只保留最后一次即可;
     * 用户在设置页手动点「换一批」也走这里,不会和后台请求叠成两批。
     */
    fun enqueueLoad(context: Context, replace: Boolean) {
        val request = OneTimeWorkRequestBuilder<MuzeiArtworkWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(workDataOf(INPUT_REPLACE to replace))
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun isMuzeiInstalled(context: Context): Boolean =
        context.packageManager.getLaunchIntentForPackage(MUZEI_PACKAGE) != null
}
