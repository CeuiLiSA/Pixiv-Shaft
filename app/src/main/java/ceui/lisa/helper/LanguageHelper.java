package ceui.lisa.helper;

import java.util.Locale;

import ceui.pixiv.i18n.AppLocales;

/**
 * 把当前 app locale 转成 Pixiv API 的 `accept-language` 值。
 * Pixiv 用下划线形式（`zh_CN` / `zh_TW`），其它标准 ISO-639-1 下划线单段。
 */
public final class LanguageHelper {

    private LanguageHelper() {}

    public static String getRequestHeaderAcceptLanguageFromAppLanguage() {
        Locale locale = AppLocales.INSTANCE.currentLocale();
        String language = locale.getLanguage();
        if ("zh".equals(language)) {
            String country = locale.getCountry();
            if ("TW".equalsIgnoreCase(country) || "HK".equalsIgnoreCase(country) || "MO".equalsIgnoreCase(country)) {
                return "zh_TW";
            }
            return "zh_CN";
        }
        return language.isEmpty() ? "en" : language;
    }
}
