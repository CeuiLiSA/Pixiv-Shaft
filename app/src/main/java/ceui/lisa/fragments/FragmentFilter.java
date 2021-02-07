package ceui.lisa.fragments;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.FragmentFilterBinding;
import ceui.lisa.utils.Local;
import ceui.lisa.viewmodel.SearchModel;


public class FragmentFilter extends BaseFragment<FragmentFilterBinding> {

    public static final String[] TAG_MATCH_VALUE = new String[]{"partial_match_for_tags",
            "exact_match_for_tags", "title_and_caption"};
    public static final String[] ALL_SIZE_VALUE = new String[]{"", "500users入り", "1000users入り", "2000users入り",
            "5000users入り", "7500users入り", "10000users入り", "20000users入り", "50000users入り"};
    public static final String[] DATE_SORT_VALUE = new String[]{"date_desc", "date_asc"};

    private SearchModel searchModel;

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        searchModel = new ViewModelProvider(requireActivity()).get(SearchModel.class);
        super.onActivityCreated(savedInstanceState);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_filter;
    }

    @Override
    public void initView() {
        baseBind.submit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                searchModel.getNowGo().setValue("search_now");
            }
        });
        String[] TAG_MATCH = new String[]{
                getString(R.string.string_284),
                getString(R.string.string_285),
                getString(R.string.string_286)
        };
        ArrayAdapter<String> tagAdapter = new ArrayAdapter<>(mContext,
                R.layout.spinner_item, TAG_MATCH);
        baseBind.tagSpinner.setAdapter(tagAdapter);
        baseBind.tagSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                searchModel.getSearchType().setValue(TAG_MATCH_VALUE[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        String[] ALL_SIZE = new String[]{
                getString(R.string.string_289),
                getString(R.string.string_290),
                getString(R.string.string_291),
                getString(R.string.string_292),
                getString(R.string.string_293),
                getString(R.string.string_294),
                getString(R.string.string_295),
                getString(R.string.string_296),
                getString(R.string.string_297)
        };
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
                // Shaft.sSettings.setSearchFilter(ALL_SIZE_VALUE[position]);
                Local.setSettings(Shaft.sSettings);
                searchModel.getStarSize().setValue(ALL_SIZE_VALUE[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        String[] DATE_SORT = new String[]{
                getString(R.string.string_287),
                getString(R.string.string_288)
        };
        ArrayAdapter<String> dateAdapter = new ArrayAdapter<>(mContext,
                R.layout.spinner_item, DATE_SORT);
        baseBind.dateSpinner.setAdapter(dateAdapter);
        baseBind.dateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                searchModel.getSortType().setValue(DATE_SORT_VALUE[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });


        if (Shaft.sUserModel.getResponse().getUser().isIs_premium()) {
            baseBind.popSwitch.setEnabled(true);
        } else {
            baseBind.popSwitch.setEnabled(false);
        }
        baseBind.popSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    searchModel.getSortType().setValue("popular_desc");
                } else {
                    String lastSortType = searchModel.getLastSortType().getValue();
                    if (!TextUtils.isEmpty(lastSortType)) {
                        searchModel.getSortType().setValue(lastSortType);
                    }
                }
            }
        });
    }
}
