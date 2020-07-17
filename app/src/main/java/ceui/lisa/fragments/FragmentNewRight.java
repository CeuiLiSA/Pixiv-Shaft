package ceui.lisa.fragments;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.LinearLayoutManager;


import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.EventAdapter;
import ceui.lisa.adapters.UserHAdapter;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustRecmdEntity;
import ceui.lisa.databinding.FragmentNewRightBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.LinearItemHorizontalDecoration;

public class FragmentNewRight extends BaseFragment<FragmentNewRightBinding> {

    public static FragmentNewRight newInstance() {
        return new FragmentNewRight();
    }

    @Override
    public void initView(View view) {
        ViewGroup.LayoutParams headParams = baseBind.head.getLayoutParams();
        headParams.height = Shaft.statusHeight;
        baseBind.head.setLayoutParams(headParams);

        baseBind.toolbar.inflateMenu(R.menu.fragment_left);

        baseBind.recyclerView.addItemDecoration(new LinearItemHorizontalDecoration(
                DensityUtil.dp2px(16.0f)));
        LinearLayoutManager manager = new LinearLayoutManager(mContext,
                LinearLayoutManager.HORIZONTAL, false);
        baseBind.recyclerView.setLayoutManager(manager);
        baseBind.recyclerView.setHasFixedSize(true);
        AppDatabase.getAppDatabase(mContext).recmdDao().getAll();


        List<IllustRecmdEntity> entities = AppDatabase.getAppDatabase(mContext).recmdDao().getAll();
        List<UserPreviewsBean> tempUser = new ArrayList<>();
        List<IllustsBean> tempIllust = new ArrayList<>();
        for (int i = 0; i < entities.size(); i++) {
            IllustsBean illustsBean = Shaft.sGson.fromJson(
                    entities.get(i).getIllustJson(), IllustsBean.class);
            UserPreviewsBean userPreviewsBean = new UserPreviewsBean();
            userPreviewsBean.setUser(illustsBean.getUser());
            tempUser.add(userPreviewsBean);
            tempIllust.add(illustsBean);
        }
        baseBind.recyclerView.setAdapter(new UserHAdapter(tempUser, mContext));

        baseBind.recyList.setLayoutManager(new LinearLayoutManager(mContext));
        baseBind.recyList.setHasFixedSize(true);
        baseBind.recyList.setAdapter(new EventAdapter(tempIllust, mContext));
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_new_right;
    }
}
