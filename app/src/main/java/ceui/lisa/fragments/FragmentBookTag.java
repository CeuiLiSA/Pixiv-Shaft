package ceui.lisa.fragments;

import android.support.v7.widget.LinearLayoutManager;
import android.view.View;


import org.greenrobot.eventbus.EventBus;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BookTagAdapter;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.BookmarkTags;
import ceui.lisa.model.BookmarkTagsBean;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.LinearItemDecoration;
import io.reactivex.Observable;

public class FragmentBookTag extends BaseListFragment<BookmarkTags, BookTagAdapter, BookmarkTagsBean> {

    private String bookType = "";

    public static FragmentBookTag newInstance(String bookType) {
        FragmentBookTag fragment = new FragmentBookTag();
        fragment.bookType = bookType;
        return fragment;
    }

    @Override
    String getToolbarTitle() {
        return "按标签筛选";
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_illust_list;
    }

    @Override
    void initRecyclerView() {
        mRecyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        mRecyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(16.0f)));
    }

    @Override
    Observable<BookmarkTags> initApi() {
        return Retro.getAppApi().getBookmarkTags(Shaft.sUserModel.getResponse().getAccess_token(),
                Shaft.sUserModel.getResponse().getUser().getId(), bookType);
    }

    @Override
    Observable<BookmarkTags> initNextApi() {
        return Retro.getAppApi().getNextTags(Shaft.sUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    void initAdapter() {
        //全部
        BookmarkTagsBean all = new BookmarkTagsBean();
        all.setCount(-1);
        all.setName("");
        allItems.add(0, all);

        //未分类
        BookmarkTagsBean unSeparated = new BookmarkTagsBean();
        unSeparated.setCount(-1);
        unSeparated.setName("未分類");
        allItems.add(0, unSeparated);

        mAdapter = new BookTagAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                Channel channel = new Channel();
                channel.setReceiver(bookType);
                channel.setObject(allItems.get(position).getName());
                EventBus.getDefault().post(channel);

                getActivity().finish();
            }
        });
    }
}
