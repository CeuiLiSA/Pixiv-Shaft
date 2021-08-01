package ceui.lisa.utils;

import android.content.res.Resources;
import android.text.TextUtils;
import android.util.Patterns;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;

public class SearchTypeUtil {

    private static Resources resources = Shaft.getContext().getResources();
    public static final int defaultSearchType = 5;

    private static final Pattern WEB_URL_PATTERN = Patterns.WEB_URL;
    private static final Pattern NUMBERIC_PATTERN = Pattern.compile("(?:\\b|\\D)([1-9]\\d{3,9})(?:\\b|\\D)");

    public static String[] SEARCH_TYPE_NAME = new String[]{
            resources.getString(R.string.string_425),
            resources.getString(R.string.string_150),
            resources.getString(R.string.string_152),
            resources.getString(R.string.string_153),
            resources.getString(R.string.string_341),
            resources.getString(R.string.string_426)
    };

    public static final int SEARCH_TYPE_DB_KEYWORD = 0;
    public static final int SEARCH_TYPE_DB_ILLUSTSID = 1;
    public static final int SEARCH_TYPE_DB_USERKEYWORD = 2;//已经废弃、兼容历史版本
    public static final int SEARCH_TYPE_DB_USERID = 3;
    public static final int SEARCH_TYPE_DB_NOVELID = 4;


    public static int getSuggestSearchType(String content){
        try {
            if(TextUtils.isEmpty(content)){
                return defaultSearchType;
            }

            if(WEB_URL_PATTERN.matcher(content).matches()){
                return 4;
            }

            Matcher matcher = NUMBERIC_PATTERN.matcher(content);
            if(matcher.find()){
                long number = Long.parseLong(matcher.group(1));
                if(number > 10000000L){
                    return 1;
                }else{
                    return 2;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return defaultSearchType;
    }
}
