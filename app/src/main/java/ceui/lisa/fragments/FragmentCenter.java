package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.fragment.app.FragmentTransaction;
import androidx.viewpager.widget.ViewPager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.scwang.smartrefresh.layout.api.RefreshLayout;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;
import com.scwang.smartrefresh.layout.header.FalsifyHeader;

import java.util.List;
import java.util.concurrent.TimeUnit;

import ceui.lisa.R;
import ceui.lisa.activities.RankActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.IllustChannel;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

public class FragmentCenter extends BaseFragment {

    private boolean isLoad = false, isActive;
    private ViewPager mViewPager;

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_center;
    }

    @Override
    View initView(View v) {
        mViewPager = v.findViewById(R.id.viewPager);
        RefreshLayout refreshLayout = v.findViewById(R.id.refreshLayout);
        refreshLayout.setRefreshHeader(new FalsifyHeader(mContext));
        refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
        TextView textView = v.findViewById(R.id.see_more);
        textView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, RankActivity.class);
                startActivity(intent);
            }
        });

        FragmentRankHorizontal fragmentRankHorizontal = new FragmentRankHorizontal();
        FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
        transaction.add(R.id.fragment_container, fragmentRankHorizontal).commit();
        return v;
    }

    @Override
    void initData() {
        Retro.getAppApi().getLoginBg(Shaft.sUserModel.getResponse().getAccess_token())
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<ListIllustResponse>() {
                    @Override
                    public void success(ListIllustResponse listIllustResponse) {
                        mViewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager()) {
                            @NonNull
                            @Override
                            public Fragment getItem(int position) {
                                int index = position % listIllustResponse.getList().size();
                                Common.showLog(className + index);
                                return FragmentImage.newInstance(listIllustResponse.getIllusts()
                                        .get(index));
                            }

                            @Override
                            public int getCount() {
                                return Integer.MAX_VALUE;
                            }
                        });
                        mViewPager.setCurrentItem(listIllustResponse.getList().size());

                    }
                });
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);

        if (isVisibleToUser) {
            if (!isLoad) {
                FragmentPivisionHorizontal fragmentPivision = new FragmentPivisionHorizontal();
                FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
                transaction.add(R.id.fragment_pivision, fragmentPivision).commit();
                isLoad = true;
            }
            isActive = true;
        }else {
            isActive = false;
        }
    }
}
