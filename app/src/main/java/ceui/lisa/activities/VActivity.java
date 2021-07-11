package ceui.lisa.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.viewpager.widget.ViewPager;

import ceui.lisa.R;
import ceui.lisa.core.Container;
import ceui.lisa.core.Mapper;
import ceui.lisa.core.PageData;
import ceui.lisa.databinding.ActivityViewPagerBinding;
import ceui.lisa.fragments.FragmentIllust;
import ceui.lisa.fragments.FragmentSingleIllust;
import ceui.lisa.fragments.FragmentSingleUgora;
import ceui.lisa.helper.DeduplicateArrayList;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListIllust;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class VActivity extends BaseActivity<ActivityViewPagerBinding> {

    private String pageUUID = "";
    private int index = 0;

    @Override
    protected void initBundle(Bundle bundle) {
        pageUUID = bundle.getString(Params.PAGE_UUID);
        index = bundle.getInt(Params.POSITION);
    }

    @Override
    protected int initLayout() {
        return R.layout.activity_view_pager;
    }

    @Override
    protected void initView() {
        PageData pageData = Container.get().getPage(pageUUID);
        if (pageData != null) {
            baseBind.viewPager.setAdapter(new FragmentStatePagerAdapter(getSupportFragmentManager(), 0) {
                @NonNull
                @Override
                public Fragment getItem(int position) {
                    if (pageData.getList().get(position).isGif()) {
                        return FragmentSingleUgora.newInstance(pageData.getList().get(position));
                    } else {
                        if (Shaft.sSettings.isUseFragmentIllust()) {
                            return FragmentIllust.newInstance(pageData.getList().get(position));
                        } else {
                            return FragmentSingleIllust.newInstance(pageData.getList().get(position));
                        }
                    }
                }

                @Override
                public int getCount() {
                    return pageData.getList().size();
                }
            });
            baseBind.viewPager.setOffscreenPageLimit(2);

            ViewPager.OnPageChangeListener listener = new ViewPager.OnPageChangeListener() {
                @Override
                public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

                }

                @Override
                public void onPageSelected(int position) {
                    Common.showLog("Container onPageSelected " + position);
                    if (Common.isEmpty(pageData.getList())) {
                        return;
                    }

                    if (position >= pageData.getList().size()) {
                        return;
                    }

                    if (Shaft.sSettings.isSaveViewHistory()) {
                        PixivOperate.insertIllustViewHistory(pageData.getList().get(position));
                    }

                    if (position == (pageData.getList().size() - 1) || position == (pageData.getList().size() - 2)) {
                        String nextUrl = pageData.getNextUrl();
                        if (!TextUtils.isEmpty(nextUrl)) {
                            if (!Container.get().isNetworking()) {
                                Common.showLog("Container 去请求下一页 " + nextUrl);
                                Retro.getAppApi().getNextIllust(Shaft.sUserModel.getAccess_token(), nextUrl)
                                        .subscribeOn(Schedulers.newThread())
                                        .observeOn(AndroidSchedulers.mainThread())
                                        .subscribe(new NullCtrl<ListIllust>() {
                                            @Override
                                            public void success(ListIllust listIllust) {
                                                Mapper mapper = new Mapper<ListIllust>();
                                                listIllust = (ListIllust) mapper.apply(listIllust);
                                                Common.showLog("Container 下一页请求成功 ");
                                                Intent intent = new Intent(Params.FRAGMENT_ADD_DATA);
                                                intent.putExtra(Params.PAGE_UUID, pageUUID);
                                                intent.putExtra(Params.CONTENT, listIllust);
                                                LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);

                                                // pageData.getList().addAll(listIllust.getList());
                                                DeduplicateArrayList.addAllWithNoRepeat(pageData.getList(), listIllust.getList());
                                                pageData.setNextUrl(listIllust.getNextUrl());
                                                if (baseBind.viewPager.getAdapter() != null) {
                                                    baseBind.viewPager.getAdapter().notifyDataSetChanged();
                                                }
                                            }

                                            @Override
                                            public void must() {
                                                super.must();
                                                Container.get().setNetworking(false);
                                            }

                                            @Override
                                            public void subscribe(Disposable d) {
                                                super.subscribe(d);
                                                Container.get().setNetworking(true);
                                            }
                                        });
                            } else {
                                Common.showLog("Container 不去请求下一页 00");
                            }
                        } else {
                            Common.showLog("Container 不去请求下一页 11");
                        }
                    }
                }

                @Override
                public void onPageScrollStateChanged(int state) {

                }
            };
            baseBind.viewPager.addOnPageChangeListener(listener);

            if(index < pageData.getList().size()){
                baseBind.viewPager.setCurrentItem(index);
            }

            if(index == 0){
                baseBind.viewPager.post(() -> listener.onPageSelected(baseBind.viewPager.getCurrentItem()));
            }
        } else {
            finish();
        }
    }

    @Override
    protected void initData() {

    }

    @Override
    protected void onDestroy() {
        PixivOperate.clearBack();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        //通知外界列表，滚动到正确的位置
        Intent intent = new Intent(Params.FRAGMENT_SCROLL_TO_POSITION);
        intent.putExtra(Params.INDEX, baseBind.viewPager.getCurrentItem());
        intent.putExtra(Params.PAGE_UUID, pageUUID);
        LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);
        super.onPause();
    }

    @Override
    public boolean hideStatusBar() {
        return true;
    }
}
