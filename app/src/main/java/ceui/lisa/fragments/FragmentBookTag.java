package ceui.lisa.fragments;

import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;

import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BAdapter;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyBookTagBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.core.NetControl;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.BookmarkTags;
import ceui.lisa.model.BookmarkTagsBean;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.LinearItemDecoration;
import io.reactivex.Observable;

public class FragmentBookTag extends NetListFragment<FragmentBaseListBinding,
        BookmarkTags, BookmarkTagsBean, RecyBookTagBinding> {

    private String bookType = "";

    public static FragmentBookTag newInstance(String bookType) {
        FragmentBookTag fragment = new FragmentBookTag();
        fragment.bookType = bookType;
        return fragment;
    }

    @Override
    public NetControl<BookmarkTags> present() {
        return new NetControl<BookmarkTags>() {
            @Override
            public Observable<BookmarkTags> initApi() {
                return Retro.getAppApi().getBookmarkTags(Shaft.sUserModel.getResponse().getAccess_token(),
                        Shaft.sUserModel.getResponse().getUser().getId(), bookType);
            }

            @Override
            public Observable<BookmarkTags> initNextApi() {
                return Retro.getAppApi().getNextTags(Shaft.sUserModel.getResponse().getAccess_token(), nextUrl);
            }
        };
    }

    @Override
    public BaseAdapter<BookmarkTagsBean, RecyBookTagBinding> adapter() {
        return new BAdapter(allItems, mContext).setOnItemClickListener(new OnItemClickListener() {
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

    @Override
    public String getToolbarTitle() {
        return "按标签筛选";
    }

    @Override
    public void initRecyclerView() {
        baseBind.recyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        baseBind.recyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(16.0f)));
    }

    @Override
    public void firstSuccess() {
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
    }
}
