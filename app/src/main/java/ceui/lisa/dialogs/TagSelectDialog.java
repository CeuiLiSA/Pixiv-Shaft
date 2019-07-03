package ceui.lisa.dialogs;

import android.support.annotation.NonNull;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.View;
import android.widget.ProgressBar;

import com.scwang.smartrefresh.layout.api.RefreshLayout;
import com.scwang.smartrefresh.layout.listener.OnLoadMoreListener;
import com.scwang.smartrefresh.layout.listener.OnRefreshListener;
import com.scwang.smartrefresh.layout.util.DensityUtil;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.SelectTagAdapter;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.BookmarkTags;
import ceui.lisa.view.LinearItemDecoration;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class TagSelectDialog extends BaseDialog{

    private ProgressBar mProgressBar;
    private RecyclerView mRecyclerView;
    private RefreshLayout mRefreshLayout;
    private List<BookmarkTags.BookmarkTagsBean> allItems = new ArrayList<>();
    private SelectTagAdapter mAdapter;
    private String nextUrl = "";
    private String bookType = "";

    public static TagSelectDialog newInstance(String bookType){
        TagSelectDialog dialog = new TagSelectDialog();
        dialog.bookType = bookType;
        return dialog;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.dialog_select_tag;
    }

    @Override
    View initView(View v) {
        mProgressBar = v.findViewById(R.id.progress);
        mRecyclerView = v.findViewById(R.id.recyclerView);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        mRecyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(16.0f)));
        mRefreshLayout = v.findViewById(R.id.refreshLayout);
        mRefreshLayout.setEnableRefresh(true);
        mRefreshLayout.setEnableLoadMore(false);
        mRefreshLayout.setOnRefreshListener(new OnRefreshListener() {
            @Override
            public void onRefresh(@NonNull RefreshLayout refreshLayout) {
                getFirstData();
            }
        });
        mRefreshLayout.setOnLoadMoreListener(new OnLoadMoreListener() {
            @Override
            public void onLoadMore(@NonNull RefreshLayout refreshLayout) {
                getNextData();
            }
        });
        return v;
    }

    @Override
    void initData() {
        getFirstData();
    }


    private void getFirstData(){
        allItems.clear();
        Retro.getAppApi().getBookmarkTags(mUserModel.getResponse().getAccess_token(),
                mUserModel.getResponse().getUser().getId(), bookType)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ErrorCtrl<BookmarkTags>() {
                    @Override
                    public void onNext(BookmarkTags bookmarkTags) {
                        if(bookmarkTags != null){

                            if (!TextUtils.isEmpty(bookmarkTags.getNext_url())) {
                                nextUrl = bookmarkTags.getNext_url();
                                mRefreshLayout.setEnableLoadMore(true);
                            }else {
                                mRefreshLayout.setEnableLoadMore(false);
                            }

                            allItems.addAll(bookmarkTags.getBookmark_tags());
                            mAdapter = new SelectTagAdapter(allItems, mContext);
                            mRecyclerView.setAdapter(mAdapter);
                            mRefreshLayout.finishRefresh(true);
                        }else {
                            mRefreshLayout.finishRefresh(false);
                        }
                        mProgressBar.setVisibility(View.INVISIBLE);
                    }

                    @Override
                    public void onError(Throwable e) {
                        super.onError(e);
                        mRefreshLayout.finishRefresh(false);
                        mProgressBar.setVisibility(View.INVISIBLE);
                    }
                });
    }

    private void getNextData(){
        Retro.getAppApi().getNextTags(mUserModel.getResponse().getAccess_token(), nextUrl)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ErrorCtrl<BookmarkTags>() {
                    @Override
                    public void onNext(BookmarkTags bookmarkTags) {
                        if(bookmarkTags != null){

                            if (!TextUtils.isEmpty(bookmarkTags.getNext_url())) {
                                nextUrl = bookmarkTags.getNext_url();
                                mRefreshLayout.setEnableLoadMore(true);
                            }else {
                                mRefreshLayout.setEnableLoadMore(false);
                            }

                            allItems.addAll(bookmarkTags.getBookmark_tags());
                            mAdapter.notifyDataSetChanged();
                            mRefreshLayout.finishLoadMore(true);
                        }else {
                            mRefreshLayout.finishLoadMore(false);
                        }
                        mProgressBar.setVisibility(View.INVISIBLE);
                    }

                    @Override
                    public void onError(Throwable e) {
                        super.onError(e);
                        mRefreshLayout.finishLoadMore(false);
                        mProgressBar.setVisibility(View.INVISIBLE);
                    }
                });
    }
}
