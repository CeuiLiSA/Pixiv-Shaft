package ceui.lisa.activities;

import android.os.Bundle;
import android.os.Handler;
import android.view.View;

import ceui.lisa.R;
import ceui.lisa.cache.Cache;
import ceui.lisa.databinding.ActivityNovelBinding;
import ceui.lisa.models.NovelDetail;
import ceui.lisa.page.PageLoader;
import ceui.lisa.page.PageView;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;

public class NovelActivity extends BaseActivity<ActivityNovelBinding> {

    private NovelDetail mNovelDetail;
    private PageLoader mPageLoader;

    @Override
    protected void initBundle(Bundle bundle) {
        mNovelDetail = (NovelDetail) bundle.getSerializable(Params.NOVEL_DETAIL);
    }

    @Override
    protected int initLayout() {
        return R.layout.activity_novel;
    }

    @Override
    protected void initView() {
//        mNovelDetail = Cache.get().getModel("text_novel_page", NovelDetail.class);
        baseBind.pageView.setTouchListener(new PageView.TouchListener() {
            @Override
            public boolean onTouch() {
                return true;
            }

            @Override
            public void center() {
//                showOrHideOperation();
            }

            @Override
            public boolean allowPrePage() {
                return true;
            }

            @Override
            public void prePage() {
            }

            @Override
            public boolean allowNextPage() {
                return true;
            }

            @Override
            public void nextPage() {

            }

            @Override
            public void cancel() {
            }
        });
        mPageLoader = baseBind.pageView.getPageLoader(mNovelDetail);
        mPageLoader.init();
        mPageLoader.dataInitSuccess();
        Common.showLog("drawContent initData ");

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                baseBind.pageView.drawCurPage(false);
            }
        }, 500L);
    }

    @Override
    protected void initData() {

    }

    /**
     * 展示或隐藏操作菜单
     */
    private void showOrHideOperation() {
        if (baseBind.operateLl.getVisibility() == View.VISIBLE) {
            // 已展开menu，先关闭menu
            baseBind.operateLl.setVisibility(View.GONE);
        } else {
            baseBind.operateLl.setVisibility(View.VISIBLE);
        }
    }
}
