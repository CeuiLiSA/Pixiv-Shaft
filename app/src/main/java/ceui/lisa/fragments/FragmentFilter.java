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

import ceui.lisa.utils.PixivSearchParamUtil;

public class FragmentFilter extends BaseFragment<FragmentFilterBinding> {

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

        ArrayAdapter<String> tagAdapter = new ArrayAdapter<>(mContext,
                R.layout.spinner_item, PixivSearchParamUtil.TAG_MATCH_NAME);
        baseBind.tagSpinner.setAdapter(tagAdapter);
        baseBind.tagSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                searchModel.getSearchType().setValue(PixivSearchParamUtil.TAG_MATCH_VALUE[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        ArrayAdapter<String> starAdapter = new ArrayAdapter<>(mContext,
                R.layout.spinner_item, PixivSearchParamUtil.ALL_SIZE_NAME);
        baseBind.starSizeSpinner.setAdapter(starAdapter);
        baseBind.starSizeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Shaft.sSettings.setSearchFilter(ALL_SIZE_VALUE[position]);
                Local.setSettings(Shaft.sSettings);
                searchModel.getStarSize().setValue(PixivSearchParamUtil.ALL_SIZE_VALUE[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });
        for (int i = 0; i < PixivSearchParamUtil.ALL_SIZE_VALUE.length; i++) {
            if (PixivSearchParamUtil.ALL_SIZE_VALUE[i].equals(Shaft.sSettings.getSearchFilter())) {
                baseBind.starSizeSpinner.setSelection(i);
                break;
            }
        }

        ArrayAdapter<String> dateAdapter = new ArrayAdapter<>(mContext,
                R.layout.spinner_item, PixivSearchParamUtil.DATE_SORT_NAME);
        baseBind.dateSpinner.setAdapter(dateAdapter);
        baseBind.dateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                searchModel.getSortType().setValue(PixivSearchParamUtil.DATE_SORT_VALUE[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });


        if (Shaft.sUserModel.getUser().isIs_premium()) {
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
