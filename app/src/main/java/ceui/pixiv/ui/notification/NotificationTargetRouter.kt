package ceui.pixiv.ui.notification

import android.content.Context
import android.content.Intent
import android.net.Uri
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.utils.Params
import ceui.pixiv.utils.extractPixivId
import timber.log.Timber

/**
 * 通知 target_url 路由统一出口:
 *  - pixiv://illusts/N  → "Plaza打开作品" route(ArtworkV3Fragment lazy load)
 *  - pixiv://novels/N   → "小说详情"
 *  - pixiv://users/N    → UActivity
 *  - http(s)            → ACTION_VIEW
 *  - 其它 scheme 不动,只 log。不抛异常,确保通知列表点不挂。
 */
fun Context.routeNotificationTargetUrl(targetUrl: String?) {
    if (targetUrl.isNullOrEmpty()) return
    val info = extractPixivId(targetUrl)
    when (info.type) {
        "illusts" -> info.value.toLongOrNull()?.let { id ->
            startActivity(Intent(this, TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, "Plaza打开作品")
                putExtra(Params.ILLUST_ID, id.toInt())
            })
        }
        "novels" -> info.value.toLongOrNull()?.let { id ->
            startActivity(Intent(this, TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情")
                putExtra(Params.NOVEL_ID, id)
                putExtra("hideStatusBar", true)
            })
        }
        "users" -> info.value.toLongOrNull()?.let { id ->
            startActivity(Intent(this, UActivity::class.java).apply {
                putExtra(Params.USER_ID, id)
            })
        }
        else -> runCatching {
            val uri = Uri.parse(targetUrl)
            if (uri.scheme?.startsWith("http") == true) {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } else {
                Timber.w("notification target_url not handled: $targetUrl")
            }
        }
    }
}
