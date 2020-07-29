package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.fragment.app.FragmentTransaction;

import com.scwang.smartrefresh.layout.footer.FalsifyFooter;
import com.scwang.smartrefresh.layout.header.FalsifyHeader;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.FragmentCenterBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListIllust;
import ceui.lisa.transformer.GalleryTransformer;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class FragmentCenter extends BaseFragment<FragmentCenterBinding> {

    private boolean isLoad = false;

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_center;
    }

    @Override
    public void initView(View view) {
        FalsifyHeader falsifyHeader = new FalsifyHeader(mContext);
        falsifyHeader.setPrimaryColors(getResources().getColor(R.color.colorPrimary));
        falsifyHeader.setBackgroundColor(getResources().getColor(R.color.colorPrimary));
        baseBind.refreshLayout.setRefreshHeader(falsifyHeader);
        baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
        baseBind.manga.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "推荐漫画");
                startActivity(intent);
            }
        });
        baseBind.novel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "推荐小说");
                startActivity(intent);
            }
        });

        baseBind.viewPager.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                ViewGroup.LayoutParams params = baseBind.wtfHead.getLayoutParams();
                params.height = baseBind.viewPager.getTop() +
                        (baseBind.viewPager.getBottom() - baseBind.viewPager.getTop()) / 2;
                baseBind.wtfHead.setLayoutParams(params);
                baseBind.viewPager.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            }
        });
    }

    @Override
    void initData() {
        if (Dev.isDev) {

        } else {
            Retro.getAppApi().getLoginBg(Shaft.sUserModel.getResponse().getAccess_token())
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new NullCtrl<ListIllust>() {
                        @Override
                        public void success(ListIllust listIllust) {
                            baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager()) {
                                @NonNull
                                @Override
                                public Fragment getItem(int position) {
                                    int index = position % listIllust.getList().size();
                                    return FragmentImage.newInstance(listIllust.getIllusts()
                                            .get(index));
                                }

                                @Override
                                public int getCount() {
                                    return Integer.MAX_VALUE;
                                }
                            });
                            baseBind.viewPager.setPageTransformer(true, new GalleryTransformer());
                            baseBind.viewPager.setOffscreenPageLimit(3);
                            baseBind.viewPager.setCurrentItem(listIllust.getList().size());
                        }
                    });
        }
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);

        if (isVisibleToUser) {
            if (!isLoad) {
                FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
                transaction.add(R.id.fragment_pivision, new FragmentPivisionHorizontal());
                transaction.commit();
                isLoad = true;
            }
        } else {
        }
    }
}
