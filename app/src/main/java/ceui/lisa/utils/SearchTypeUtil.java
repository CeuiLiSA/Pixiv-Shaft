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
    public static final int defaultSearchType = 0;

    private static final Pattern WEB_URL_PATTERN = Patterns.WEB_URL;
    private static final Pattern NUMBERIC_PATTERN = Pattern.compile("^\\D*(\\d{4,10})\\D*$");

    public static String[] SEARCH_TYPE_NAME = new String[]{
            resources.getString(R.string.string_149),
            resources.getString(R.string.string_150),
            resources.getString(R.string.string_151),
            resources.getString(R.string.string_152),
            resources.getString(R.string.string_153),
            resources.getString(R.string.string_341)
    };


    public static int getSuggestSearchType(String content){
        if(TextUtils.isEmpty(content)){
            return defaultSearchType;
        }

        if(WEB_URL_PATTERN.matcher(content).matches()){
            return 5;
        }

        Matcher matcher = NUMBERIC_PATTERN.matcher(content);
        if(matcher.matches()){
            int number = Integer.parseInt(matcher.group(1));
            if(number > 10000000){
                return 1;
            }else{
                return 3;
            }
        }

        return defaultSearchType;
    }
}
