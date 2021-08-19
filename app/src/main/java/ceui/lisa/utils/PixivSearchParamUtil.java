package ceui.lisa.utils;

import android.content.res.Resources;

import java.util.Arrays;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;

public class PixivSearchParamUtil {

    private static Resources resources = Shaft.getContext().getResources();

    public static final String POPULAR_SORT_VALUE = "popular_desc";
    public static final String[] TAG_MATCH_VALUE = new String[]{"partial_match_for_tags",
            "exact_match_for_tags", "title_and_caption"};
    public static final String[] TAG_MATCH_VALUE_NOVEL = new String[]{"partial_match_for_tags",
            "exact_match_for_tags", "text", "keyword"};
    public static final String[] ALL_SIZE_VALUE = new String[]{"", "500users入り", "1000users入り", "2000users入り",
            "5000users入り", "7500users入り", "10000users入り", "20000users入り", "50000users入り", "100000users入り"};
    public static final String[] SORT_TYPE_VALUE = new String[]{"date_desc", "date_asc", POPULAR_SORT_VALUE};
    public static final String[] R18_RESTRICTION_VALUE = new String[]{"", "-R-18", "R-18"};

    public static String[] TAG_MATCH_NAME = new String[]{
            resources.getString(R.string.string_284),
            resources.getString(R.string.string_285),
            resources.getString(R.string.string_286)
    };
    public static String[] TAG_MATCH_NAME_NOVEL = new String[]{
            resources.getString(R.string.string_284),
            resources.getString(R.string.string_285),
            resources.getString(R.string.string_394),
            resources.getString(R.string.string_395)
    };

    public static String[] ALL_SIZE_NAME = new String[]{
            resources.getString(R.string.string_289),
            resources.getString(R.string.string_290),
            resources.getString(R.string.string_291),
            resources.getString(R.string.string_292),
            resources.getString(R.string.string_293),
            resources.getString(R.string.string_294),
            resources.getString(R.string.string_295),
            resources.getString(R.string.string_296),
            resources.getString(R.string.string_297),
            resources.getString(R.string.string_375)
    };

    public static String[] SORT_TYPE_NAME = new String[]{
            resources.getString(R.string.string_287),
            resources.getString(R.string.string_288),
            resources.getString(R.string.string_64_1)
    };

    public static final String[] R18_RESTRICTION_NAME = new String[]{
            resources.getString(R.string.string_289),
            resources.getString(R.string.string_440),
            resources.getString(R.string.string_441)
    };

    public static int getSizeIndex(String sizeFilterValue){
        int index = Arrays.asList(ALL_SIZE_VALUE).indexOf(sizeFilterValue);
        return Math.max(index, 0);
    }

    public static String getSizeName(String sizeFilterValue) {
        return ALL_SIZE_NAME[getSizeIndex(sizeFilterValue)];
    }

    public static int getSortTypeIndex(String sortTypeValue){
        int index = Arrays.asList(SORT_TYPE_VALUE).indexOf(sortTypeValue);
        return index < 0 ? 2 : index;
    }

    public static String getSortTypeName(String sortTypeValue){
        return SORT_TYPE_NAME[getSortTypeIndex(sortTypeValue)];
    }
}
