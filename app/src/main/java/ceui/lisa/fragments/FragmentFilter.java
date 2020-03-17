package ceui.lisa.fragments;

import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;

import androidx.appcompat.widget.AppCompatSpinner;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.FragmentFilterBinding;
import ceui.lisa.utils.Local;


public class FragmentFilter extends BaseBindFragment<FragmentFilterBinding> {

    public static final String[] TAG_MATCH = new String[]{"标签 部分匹配(建议)", "标签 完全匹配", "标题/简介 匹配"};
    public static final String[] TAG_MATCH_VALUE = new String[]{"partial_match_for_tags",
            "exact_match_for_tags", "title_and_caption"};


    public static final String[] ALL_SIZE = new String[]{" 无限制", " 500人收藏", " 1000人收藏", " 2000人收藏",
            " 5000人收藏(建议)", " 7500人收藏", " 10000人收藏", " 20000人收藏", " 50000人收藏"};
    public static final String[] ALL_SIZE_VALUE = new String[]{"", "500users入り", "1000users入り", "2000users入り",
            "5000users入り", "7500users入り", "10000users入り", "20000users入り", "50000users入り"};

    //, "Русский язык"
    public static final String[] ALL_LANGUAGE = new String[]{"简体中文", "日本語", "English", "繁體中文"};

    public static final String[] THEME_NAME = new String[]{
            "默认模式（跟随系统）"
            ,
            "白天模式（浅色）",
            "黑暗模式（深色）"
    };


    public static final String[] DATE_SORT = new String[]{"最新作品(建议)", "由旧到新"};
    public static final String[] SEARCH_TYPE = new String[]{"标签搜作品", "ID搜作品", "关键字搜画师", "ID搜画师"};
    public static final String[] DATE_SORT_VALUE = new String[]{"date_desc", "date_asc"};

    public SearchFilter mSearchFilter;

    public static FragmentFilter newInstance(SearchFilter filter) {
        FragmentFilter fragmentFilter = new FragmentFilter();
        fragmentFilter.mSearchFilter = filter;
        return fragmentFilter;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_filter;
    }

    @Override
    public void initView(View view) {
        baseBind.submit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mSearchFilter.closeDrawer();
                mSearchFilter.startSearch();
            }
        });
        ArrayAdapter<String> tagAdapter = new ArrayAdapter<>(mContext,
                R.layout.spinner_item, TAG_MATCH);
        baseBind.tagSpinner.setAdapter(tagAdapter);
        baseBind.tagSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mSearchFilter.onTagMatchChanged(TAG_MATCH_VALUE[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });


        ArrayAdapter<String> starAdapter = new ArrayAdapter<>(mContext,
                R.layout.spinner_item, ALL_SIZE);
        baseBind.starSizeSpinner.setAdapter(starAdapter);
        for (int i = 0; i < ALL_SIZE_VALUE.length; i++) {
            if (ALL_SIZE_VALUE[i].equals(Shaft.sSettings.getSearchFilter())) {
                baseBind.starSizeSpinner.setSelection(i);
                break;
            }
        }
        baseBind.starSizeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Shaft.sSettings.setSearchFilter(ALL_SIZE_VALUE[position]);
                Local.setSettings(Shaft.sSettings);
                mSearchFilter.onStarSizeChanged(ALL_SIZE_VALUE[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });


        ArrayAdapter<String> dateAdapter = new ArrayAdapter<>(mContext,
                R.layout.spinner_item, DATE_SORT);
        baseBind.dateSpinner.setAdapter(dateAdapter);
        baseBind.dateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mSearchFilter.onDateSortChanged(DATE_SORT_VALUE[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });


        baseBind.popSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mSearchFilter.onPopularChanged(isChecked);
            }
        });
    }

    static abstract class SearchFilter {

        abstract void onTagMatchChanged(String tagMatch);

        abstract void onDateSortChanged(String dateSort);

        abstract void onStarSizeChanged(String starSize);

        abstract void onPopularChanged(boolean isPopular);

        abstract void startSearch();

        abstract void closeDrawer();
    }
}
