package ceui.pixiv.i18n

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import ceui.lisa.utils.Local
import ceui.lisa.utils.Settings

/**
 * 启动时做一次：把旧 [Settings.appLanguage] 字段迁到 AppCompat per-app locale，然后清空旧字段；
 * 首次安装或从未选过语言时，按系统 locale 做合理 fallback。
 */
object AppLocalesBootstrap {

    /** 旧的显示名 → BCP-47 tag。显示名取自已删除的 `Settings.ALL_LANGUAGE`。 */
    private val legacyNameToTag: Map<String, String> = mapOf(
        "简体中文" to "zh-CN",
        "日本語" to "ja",
        "English" to "en",
        "繁體中文" to "zh-TW",
        "русский" to "ru",
        "한국어" to "ko",
    )

    fun bootstrap(settings: Settings) {
        migrateLegacyField(settings)
        AppLocales.ensureInitialized()
    }

    @Suppress("DEPRECATION")
    private fun migrateLegacyField(settings: Settings) {
        val legacy = settings.appLanguage
        if (legacy.isNullOrBlank()) return
        val alreadySet = !AppCompatDelegate.getApplicationLocales().isEmpty
        if (!alreadySet) {
            val tag = legacyNameToTag[legacy]
            if (tag != null) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
        }
        // 不管有没有成功迁移，旧字段都应标记为"已配置过"，防止 ensureInitialized 再插手。
        AppLocales.markUserConfigured()
        settings.appLanguage = ""
        Local.setSettings(settings)
    }
}
