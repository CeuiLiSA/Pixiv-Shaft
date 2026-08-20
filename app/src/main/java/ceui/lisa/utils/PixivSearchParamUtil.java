package ceui.lisa.utils;

import android.content.res.Resources;

import java.util.Arrays;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.pixiv.ui.search.SortType;

public class PixivSearchParamUtil {

    private static final Resources resources = Shaft.getContext().getResources();

    public static final String POPULAR_SORT_VALUE = "popular_desc";
    public static final String[] TAG_MATCH_VALUE = new String[]{"partial_match_for_tags",
            "exact_match_for_tags", "title_and_caption"};
    public static final String[] TAG_MATCH_VALUE_NOVEL = new String[]{"partial_match_for_tags",
            "exact_match_for_tags", "text", "keyword"};
    public static final String[] ALL_SIZE_VALUE = new String[]{"", "500users入り", "1000users入り", "2000users入り",
            "5000users入り", "7500users入り", "10000users入り", "20000users入り", "50000users入り", "100000users入り"};
    // 「机内自带热度排序」(trending_builtin) 已下线——它读的是打包进 APK 的内置榜 assets，
    // 那份数据已经搬到 pixshaft-api 只服务 Prime 标签页。老配置里存过这个值的用户由
    // getSortTypeIndex 的 index<0 分支落回「按热度」，运行时由 SortType.sanitize 归一。
    // 档位与顺序对齐 V3 搜索筛选器的排序 picker（SearchFilterV3BottomSheet.sortList），
    // 两边共享同一个 Settings.searchDefaultSortType 字段、互相联动。男/女性向是 illust
    // 专属档，novel 路径读取时经 SortType.novelSafe 归一成总热度。
    public static final String[] SORT_TYPE_VALUE = new String[]{
            SortType.POPULAR_PREVIEW,
            SortType.DATE_DESC,
            SortType.DATE_ASC,
            POPULAR_SORT_VALUE,
            SortType.POPULAR_MALE_DESC,
            SortType.POPULAR_FEMALE_DESC};
    // 注：R18 三档已改为客户端按 x_restrict 过滤（见 Mapper.setSearchR18Restriction），
    // 旧的 -R-18 / R-18 关键字 hack 表已删除——它匹配字面标签，会让全年龄和 R 混在一起。

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

    // 文案与 V3 筛选器 sortLabel 同源（search_filter_v3_sort_*），顺序与 SORT_TYPE_VALUE 一一对应
    public static String[] SORT_TYPE_NAME = new String[]{
            resources.getString(R.string.search_filter_v3_sort_popular_preview),
            resources.getString(R.string.search_filter_v3_sort_date_desc),
            resources.getString(R.string.search_filter_v3_sort_date_asc),
            resources.getString(R.string.search_filter_v3_sort_popular_desc),
            resources.getString(R.string.search_filter_v3_sort_popular_male_desc),
            resources.getString(R.string.search_filter_v3_sort_popular_female_desc)
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
        // 认不出的值（如已下线的 trending_builtin）落回「按热度」档
        return index < 0 ? Arrays.asList(SORT_TYPE_VALUE).indexOf(POPULAR_SORT_VALUE) : index;
    }

    public static String getSortTypeName(String sortTypeValue){
        return SORT_TYPE_NAME[getSortTypeIndex(sortTypeValue)];
    }
}
