package ceui.lisa.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import ceui.lisa.fragments.FragmentImageDetail;
import ceui.pixiv.ui.detail.ArtworkV3Fragment;
import ceui.lisa.helper.DeduplicateArrayList;
import ceui.lisa.http.LegacyApiCalls;
import ceui.lisa.model.ListIllust;
import ceui.loxia.Illust;
import java.util.Collections;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.loxia.ObjectPool;

public class VActivity extends BaseActivity<ActivityViewPagerBinding> {

    private String pageUUID = "";
    private int index = 0;
    private Illust widgetIllust = null;

    @Override
    protected void initBundle(Bundle bundle) {
        pageUUID = bundle.getString(Params.PAGE_UUID);
        index = bundle.getInt(Params.POSITION);
        // widget 点击携带的单张作品：进程被杀后 Container 已空时的兜底数据源
        widgetIllust = (Illust) bundle.getSerializable(Params.WIDGET_ILLUST);
    }

    @Override
    protected int initLayout() {
        return R.layout.activity_view_pager;
    }

    @Override
    protected void initView() {
        PageData found = Container.get().getPage(pageUUID);
        // 进程被杀后 Container（内存级 HashMap）已空 → widget 点击会丢数据，之前直接 finish() 闪退回桌面
        //（视频复现：杀掉 app 后点 widget 没反应，刷新一次重新拉活进程才能点开）。
        // widget 的 intent 自带 Illust（Serializable），用它重建单图 PageData，
        // 这样 app 未运行时点击 widget 也能正常打开详情。
        if (found == null && widgetIllust != null) {
            found = new PageData(pageUUID, null, Collections.singletonList(widgetIllust));
            Container.get().addPageToMap(found);
        }
        final PageData pageData = found;
        if (pageData != null) {
            // ArtworkV3 的 feed 有意自动准备前后缓存页（池中已有完整详情时是纯内存组装）；
            // 但只有当前页需要进入 RESUMED。这样相邻页可秒滑，同时下载状态 DB 探测、300ms
            // 进度轮询和 ugoira 播放仍只在当前页运行。
            baseBind.viewPager.setAdapter(new FragmentStatePagerAdapter(
                    getSupportFragmentManager(),
                    FragmentStatePagerAdapter.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
                @NonNull
                @Override
                public Fragment getItem(int position) {
                    Illust illustsBean = pageData.getList().get(position);
                    if (illustsBean.getId() == 0 || !Boolean.TRUE.equals(illustsBean.getVisible())) {
                        return FragmentImageDetail.newInstance(illustsBean.getImage_urls() != null
                                ? illustsBean.getImage_urls().findMaxSizeUrl() : null);
                    } else {
                        // ugoira(动图)不再甩去独立老页 FragmentSingleUgora,和普通插画一样走
                        // V3 / FragmentIllust,由页面内联的 UgoiraPlayerAdapter 自动播放。
                        // 旧的 FragmentSingleIllust 兜底页已删,非 V3 一律走 FragmentIllust。
                        Illust exist = ObjectPool.INSTANCE.getIllust(illustsBean.getId()).getValue();
                        if (exist == null) {
                            ObjectPool.INSTANCE.updateIllust(illustsBean);
                        }
                        if (Shaft.sSettings.isUseArtworkV3()) {
                            return ArtworkV3Fragment.newInstance(illustsBean.getId());
                        } else {
                            return FragmentIllust.newInstance((int) illustsBean.getId());
                        }
                    }
                }

                @Override
                public int getCount() {
                    return pageData.getList().size();
                }

                @Nullable
                @org.jetbrains.annotations.Nullable
                @Override
                public Parcelable saveState() {
                    Bundle bundle = (Bundle) super.saveState();
                    if (bundle != null) {
                        bundle.putParcelableArray("states", null);
                    }
                    return bundle;
                }
            });
            // 前后页保留 View 和 feed 数据以保证横滑手感，但 lifecycle 限制在 STARTED。
            baseBind.viewPager.setOffscreenPageLimit(1);

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
                            if (pageData.tryStartNextPageLoad()) {
                                Common.showLog("Container 去请求下一页 " + nextUrl);
                                // onFinally 对应旧 doFinally：成功/失败都把 in-flight 标记放掉；
                                // 页面销毁取消时不回调，此时 pageData 也随 Activity 一起走了。
                                LegacyApiCalls.getNextIllust(VActivity.this, nextUrl, raw -> {
                                    Mapper<ListIllust> mapper = new Mapper<>();
                                    ListIllust listIllust = mapper.apply(raw);
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
                                }, pageData::finishNextPageLoad);
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

            if (index < pageData.getList().size()) {
                baseBind.viewPager.setCurrentItem(index);
            }

            if (index == 0) {
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
        if (isFinishing()) {
            Container.get().removePage(pageUUID);
        }
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        //通知外界列表，滚动到正确的位置
        Intent intent = new Intent(Params.FRAGMENT_SCROLL_TO_POSITION);
        int current = baseBind.viewPager.getCurrentItem();
        intent.putExtra(Params.INDEX, current);
        // feeds 版列表按作品 id 锚定回滚位置：INDEX 是 pager 快照内的下标，列表带
        // header（推荐页排行榜头）或收藏后相关作品插到中段时会漂移；
        // legacy 接收侧（NetListFragment 等）只读 INDEX，不受影响
        PageData currentPage = Container.get().getPage(pageUUID);
        if (currentPage != null && current >= 0 && current < currentPage.getList().size()) {
            intent.putExtra(Params.ID, (int) currentPage.getList().get(current).getId());
        }
        intent.putExtra(Params.PAGE_UUID, pageUUID);
        LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);
        super.onPause();
    }

    @Override
    public boolean hideStatusBar() {
        return true;
    }
}
