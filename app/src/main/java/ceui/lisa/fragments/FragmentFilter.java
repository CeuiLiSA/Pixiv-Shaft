package ceui.lisa.fragments;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;

import java.util.Arrays;

import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.FragmentFilterBinding;
import ceui.lisa.viewmodel.SearchModel;

import ceui.lisa.utils.PixivSearchParamUtil;

public class FragmentFilter extends BaseFragment<FragmentFilterBinding> {

    private SearchModel searchModel;

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        searchModel = new ViewModelProvider(requireActivity()).get(SearchModel.class);
        searchModel.getIsNovel().observe(getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                initTagSpinner(aBoolean);
            }
        });
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

        initTagSpinner(false);

        ArrayAdapter<String> starAdapter = new ArrayAdapter<>(mContext,
                R.layout.spinner_item, PixivSearchParamUtil.ALL_SIZE_NAME);
        baseBind.starSizeSpinner.setAdapter(starAdapter);
        baseBind.starSizeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
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

    private void initTagSpinner(boolean isNovel) {
        String[] titles = isNovel ? PixivSearchParamUtil.TAG_MATCH_NAME_NOVEL : PixivSearchParamUtil.TAG_MATCH_NAME;
        String[] values = isNovel ? PixivSearchParamUtil.TAG_MATCH_VALUE_NOVEL : PixivSearchParamUtil.TAG_MATCH_VALUE;
        ArrayAdapter<String> tagAdapter = new ArrayAdapter<>(mContext,
                R.layout.spinner_item, titles);
        baseBind.tagSpinner.setAdapter(tagAdapter);
        baseBind.tagSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                searchModel.getSearchType().setValue(values[position]);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        if(searchModel != null){
            int index = Arrays.asList(values).indexOf(searchModel.getSearchType().getValue());
            baseBind.tagSpinner.setSelection(Math.max(index, 0));
        }
    }
}
