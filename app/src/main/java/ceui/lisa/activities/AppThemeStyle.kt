package ceui.lisa.activities

import android.content.Context
import ceui.lisa.R
import ceui.pixiv.ui.settings.CustomThemeColor

/**
 * 按设置把用户选的主题（十档预设 / 自定义 HEX）套到一个 Context 上。
 *
 * Application、[BaseActivity] 与应用锁页（[ceui.pixiv.applock.AppLock] 的 ThemeApplier）共用这一份，
 * 此前前两处各有一份同样的 switch。
 */
object AppThemeStyle {

    @JvmStatic
    fun applyTo(context: Context) {
        // 自定义主题色（issue #1014）：先把 @color/custom_theme_primary 换成用户的色值，再
        // setTheme —— theme attr 是 setTheme 那一刻解析的，顺序反了就拿到占位色。
        // 系统不支持（< Android 11）或存的色值非法时 isActive() 为 false，索引 -1 落进下面
        // when 的 else，回落默认预设。
        if (CustomThemeColor.isActive()) {
            CustomThemeColor.applyResourceOverride(context)
            context.setTheme(R.style.AppTheme_Custom)
            return
        }
        context.setTheme(
            when (Shaft.sSettings.themeIndex) {
                0 -> R.style.AppTheme_Index0
                1 -> R.style.AppTheme_Index1
                2 -> R.style.AppTheme_Index2
                3 -> R.style.AppTheme_Index3
                4 -> R.style.AppTheme_Index4
                5 -> R.style.AppTheme_Index5
                6 -> R.style.AppTheme_Index6
                7 -> R.style.AppTheme_Index7
                8 -> R.style.AppTheme_Index8
                9 -> R.style.AppTheme_Index9
                else -> R.style.AppTheme_Default
            },
        )
    }
}
