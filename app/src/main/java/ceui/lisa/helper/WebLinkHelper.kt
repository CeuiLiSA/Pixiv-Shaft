package ceui.lisa.helper

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.URLSpan
import android.view.View
import android.content.res.Configuration
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import ceui.lisa.R
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.lisa.utils.Common
import ceui.pixiv.ui.settings.ChromeTabsPrefs
import timber.log.Timber

/**
 * 统一处理应用内外部网页链接的打开行为。
 *
 * 行为矩阵：
 * 1. 有 Chrome 且未被用户手动禁用时，使用 Chrome Custom Tabs (CCT) 打开；
 * 2. 其它情况（未安装 Chrome 或用户手动关闭）回退到系统旧版行为：
 *    - OAuth 登录/注册：走无 package 绑定的旧版 CustomTabsIntent（系统默认浏览器）；
 *    - 普通链接：走系统默认的 ACTION_VIEW 跳转；
 * 3. 运行期 Chrome 抛出异常（如中途被卸载/冻结）时，静默捕获并立即回退到旧版行为，避免崩溃。
 */
object WebLinkHelper {

    /** @return 页面真的开出去了没有（调用方据此决定要不要进入「等用户回来」之类的状态）。 */
    @JvmStatic
    fun openUrl(context: Context?, url: String?): Boolean {
        if (context == null || url.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return openUrl(context, uri)
    }

    @JvmStatic
    fun openUrl(context: Context?, uri: Uri?): Boolean {
        if (context == null || uri == null) return false
        if (ChromeTabsPrefs.shouldUseChromeCustomTabs(context)) {
            val launched = launchChromeCustomTab(context, uri)
            if (launched) return true
        }
        // 回退到旧版普通链接行为（Intent.ACTION_VIEW）
        return launchLegacyView(context, uri)
    }

    @JvmStatic
    fun openOAuthUrl(context: Context?, url: String?) {
        if (context == null || url.isNullOrBlank()) return
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        openOAuthUrl(context, uri)
    }

    @JvmStatic
    fun openOAuthUrl(context: Context?, uri: Uri?) {
        if (context == null || uri == null) return
        if (ChromeTabsPrefs.shouldUseChromeCustomTabs(context)) {
            val launched = launchChromeCustomTab(context, uri)
            if (launched) return
        }
        // 回退到旧版 OAuth 行为（无 package 绑定的 CustomTabsIntent）
        launchLegacyCustomTabs(context, uri)
    }

    private fun buildCustomTabsIntent(context: Context): CustomTabsIntent {
        val palette = runCatching { V3Palette.from(context) }.getOrNull()
        val isDark = palette?.isDark ?: run {
            val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            nightMode == Configuration.UI_MODE_NIGHT_YES
        }

        // 构建深色与浅色双模的色彩参数（采用 V3Palette 的主题染色底色与边框色）
        val darkPalette = if (palette?.isDark == true) palette else {
            val primary = palette?.primary ?: 0xFF6200EE.toInt()
            V3Palette(primary, true, 0f)
        }
        val lightPalette = if (palette?.isDark == false) palette else {
            val primary = palette?.primary ?: 0xFF6200EE.toInt()
            V3Palette(primary, false, 0f)
        }

        val darkParams = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(darkPalette.cardFill)
            .setSecondaryToolbarColor(darkPalette.cardFill)
            .setNavigationBarColor(darkPalette.cardFill)
            .setNavigationBarDividerColor(darkPalette.cardHairline)
            .build()

        val lightParams = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(lightPalette.cardFill)
            .setSecondaryToolbarColor(lightPalette.cardFill)
            .setNavigationBarColor(lightPalette.cardFill)
            .setNavigationBarDividerColor(lightPalette.cardHairline)
            .build()

        // 显式跟随当前 Shaft 宿主应用的深浅模式，避免 Chrome 因系统为浅色而忽略 Chrome 应用内的深色偏好
        val activeScheme = if (isDark) {
            CustomTabsIntent.COLOR_SCHEME_DARK
        } else {
            CustomTabsIntent.COLOR_SCHEME_LIGHT
        }

        return CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setColorScheme(activeScheme)
            .setDefaultColorSchemeParams(if (isDark) darkParams else lightParams)
            .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, darkParams)
            .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_LIGHT, lightParams)
            .build()
    }

    private fun launchChromeCustomTab(context: Context, uri: Uri): Boolean {
        return try {
            val customTabsIntent = buildCustomTabsIntent(context)
            customTabsIntent.intent.setPackage(ChromeTabsPrefs.CHROME_PACKAGE)
            if (context !is Activity) {
                customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            customTabsIntent.launchUrl(context, uri)
            true
        } catch (e: Exception) {
            Timber.w(e, "Chrome Custom Tab 启动失败，将回退到默认行为")
            false
        }
    }

    private fun launchLegacyCustomTabs(context: Context, uri: Uri) {
        try {
            val customTabsIntent = buildCustomTabsIntent(context)
            if (context !is Activity) {
                customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            customTabsIntent.launchUrl(context, uri)
        } catch (_: ActivityNotFoundException) {
            Common.showToast(context.getString(R.string.msg_no_browser))
        } catch (_: SecurityException) {
            Common.showToast(context.getString(R.string.msg_no_browser))
        }
    }

    private fun launchLegacyView(context: Context, uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            Common.showToast(context.getString(R.string.msg_no_browser))
            false
        } catch (_: SecurityException) {
            Common.showToast(context.getString(R.string.msg_no_browser))
            false
        }
    }
}

/**
 * 将 HtmlCompat.fromHtml 解析出的标准 URLSpan 拦截重定向到 [WebLinkHelper.openUrl]。
 */
class CustomTabUrlSpan(url: String) : URLSpan(url) {
    override fun onClick(widget: View) {
        val targetUrl = url
        if (!targetUrl.isNullOrEmpty()) {
            WebLinkHelper.openUrl(widget.context, targetUrl)
        }
    }
}

/**
 * 遍历 [Spanned] 中所有 [URLSpan]，替换为 [CustomTabUrlSpan]，使点按走 Chrome Custom Tabs / 回退机制。
 */
fun Spanned.replaceWithCustomTabSpans(): Spanned {
    val spannable = if (this is Spannable) this else SpannableStringBuilder(this)
    val urlSpans = spannable.getSpans(0, spannable.length, URLSpan::class.java)
    for (span in urlSpans) {
        val start = spannable.getSpanStart(span)
        val end = spannable.getSpanEnd(span)
        val flags = spannable.getSpanFlags(span)
        val url = span.url
        spannable.removeSpan(span)
        spannable.setSpan(CustomTabUrlSpan(url), start, end, flags)
    }
    return spannable
}
