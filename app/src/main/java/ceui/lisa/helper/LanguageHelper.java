package ceui.lisa.helper;

import com.tencent.mmkv.MMKV;

import java.util.Arrays;

import ceui.lisa.utils.Settings;
import ceui.pixiv.session.SessionManager;

public class LanguageHelper {
    public static String getRequestHeaderAcceptLanguageFromAppLanguage() {
        //     public static final String[] ALL_LANGUAGE = new String[]{"简体中文", "日本語", "English", "繁體中文", "русский", "한국어"};
        /**
         *     private val LANGUAGE_MAP = mapOf(
         *         "简体中文" to "zh-CN",
         *         "日本語" to "ja",
         *         "English" to "en",
         *         "繁體中文" to "zh-TW",
         *         "русский" to "ru",
         *         "한국어" to "ko"
         *     )
         */
        String[] allLanguages = Settings.ALL_LANGUAGE;
        MMKV prefStore = MMKV.defaultMMKV();
        String currentLanguage = prefStore.getString(SessionManager.CONTENT_LANGUAGE_KEY, Settings.ALL_LANGUAGE[0]);
        int index = Arrays.asList(allLanguages).indexOf(currentLanguage);

        switch (index) {
            case 0:
                return "zh_CN";
            case 1:
                return "ja";
            case 2:
                return "en";
            case 3:
                return "zh_TW";
            case 4:
                return "ru";
            case 5:
                return "ko";
            default:
                return "en";
        }
    }
}
