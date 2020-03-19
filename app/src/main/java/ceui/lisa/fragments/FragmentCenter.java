package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;

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
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class FragmentCenter extends BaseBindFragment<FragmentCenterBinding> {

    private boolean isLoad = false;

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_center;
    }

    @Override
    public void initView(View view) {
        baseBind.refreshLayout.setRefreshHeader(new FalsifyHeader(mContext));
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
                                    Common.showLog(className + index);
                                    return FragmentImage.newInstance(listIllust.getIllusts()
                                            .get(index));
                                }

                                @Override
                                public int getCount() {
                                    return Integer.MAX_VALUE;
                                }
                            });
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
                FragmentPivisionHorizontal fragmentPivision = new FragmentPivisionHorizontal();
                FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
                transaction.add(R.id.fragment_pivision, fragmentPivision).commit();
                isLoad = true;
            }
        } else {
        }
    }
}
