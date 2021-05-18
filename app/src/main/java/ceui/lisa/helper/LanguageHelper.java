package ceui.lisa.helper;

import java.util.Arrays;

import ceui.lisa.activities.Shaft;
import ceui.lisa.utils.Settings;

public class LanguageHelper {
    public static String getRequestHeaderAcceptLanguageFromAppLanguage() {
        String[] allLanguages = Settings.ALL_LANGUAGE;
        String currentLanguage = Shaft.sSettings.getAppLanguage();
        int index = Arrays.asList(allLanguages).indexOf(currentLanguage);

        switch (index) {
            case 0:
                return "zh_CN";
            case 1:
                return "ja";
            case 3:
                return "zh_TW";
            case 5:
                return "ko";
            case 2:
            case 4:
            default:
                return "en";
        }
    }
}
