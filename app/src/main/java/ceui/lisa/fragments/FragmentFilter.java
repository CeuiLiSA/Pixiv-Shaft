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
import ceui.lisa.utils.Local;


public class FragmentFilter extends BaseFragment {

    public static final String[] TAG_MATCH = new String[]{"标签 部分匹配(建议)", "标签 完全匹配", "标题/简介 匹配"};
    public static final String[] TAG_MATCH_VALUE = new String[]{"partial_match_for_tags",
            "exact_match_for_tags", "title_and_caption"};


    public static final String[] ALL_SIZE = new String[]{" 无限制", " 500人收藏", " 1000人收藏", " 2000人收藏",
            " 5000人收藏(建议)", " 7500人收藏", " 10000人收藏", " 20000人收藏", " 50000人收藏"};
    public static final String[] ALL_SIZE_VALUE = new String[]{"", "500users入り", "1000users入り", "2000users入り",
            "5000users入り", "7500users入り", "10000users入り", "20000users入り", "50000users入り"};

    //, "Русский язык"
    public static final String[] ALL_LANGUAGE = new String[]{"简体中文", "日本語", "English", "繁體中文"};
    public static final String[] FILE_NAME = new String[]{
            "title_123456789_p0.png",
            "title_123456789_p0.jpg",
            "123456789_title_p0.png",
            "123456789_title_p0.jpg"};


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
    View initView(View v) {
        Button submit = v.findViewById(R.id.submit);
        submit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mSearchFilter.closeDrawer();
                mSearchFilter.startSearch();
            }
        });
        AppCompatSpinner tagSpinner = v.findViewById(R.id.tag_spinner);
        ArrayAdapter<String> tagAdapter = new ArrayAdapter<>(mContext,
                R.layout.support_simple_spinner_dropdown_item, TAG_MATCH);
        tagSpinner.setAdapter(tagAdapter);
        tagSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mSearchFilter.onTagMatchChanged(TAG_MATCH_VALUE[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });


        AppCompatSpinner starSpinner = v.findViewById(R.id.star_size_spinner);
        ArrayAdapter<String> starAdapter = new ArrayAdapter<>(mContext,
                R.layout.support_simple_spinner_dropdown_item, ALL_SIZE);
        starSpinner.setAdapter(starAdapter);
        for (int i = 0; i < ALL_SIZE_VALUE.length; i++) {
            if (ALL_SIZE_VALUE[i].equals(Shaft.sSettings.getSearchFilter())) {
                starSpinner.setSelection(i);
                break;
            }
        }
        starSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
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


        AppCompatSpinner dateSpinner = v.findViewById(R.id.date_spinner);
        ArrayAdapter<String> dateAdapter = new ArrayAdapter<>(mContext,
                R.layout.support_simple_spinner_dropdown_item, DATE_SORT);
        dateSpinner.setAdapter(dateAdapter);
        dateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mSearchFilter.onDateSortChanged(DATE_SORT_VALUE[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });


        Switch popSwitch = v.findViewById(R.id.pop_switch);
        popSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mSearchFilter.onPopularChanged(isChecked);
            }
        });

        return v;
    }

    @Override
    void initData() {

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
