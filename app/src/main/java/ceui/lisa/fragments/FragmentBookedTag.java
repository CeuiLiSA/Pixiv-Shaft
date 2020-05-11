package ceui.lisa.fragments;

import android.os.Bundle;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;

import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.BookedTagAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyBookTagBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.ListTag;
import ceui.lisa.models.TagsBean;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.view.LinearItemDecoration;
import io.reactivex.Observable;

public class FragmentBookedTag extends NetListFragment<FragmentBaseListBinding,
        ListTag, TagsBean> {

    private String bookType = "";

    public static FragmentBookedTag newInstance(String bookType) {
        Bundle args = new Bundle();
        args.putString(Params.DATA_TYPE, bookType);
        FragmentBookedTag fragment = new FragmentBookedTag();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        bookType = bundle.getString(Params.DATA_TYPE);
    }

    @Override
    public RemoteRepo<ListTag> repository() {
        return new RemoteRepo<ListTag>() {
            @Override
            public Observable<ListTag> initApi() {
                return Retro.getAppApi().getBookmarkTags(Shaft.sUserModel.getResponse().getAccess_token(),
                        Shaft.sUserModel.getResponse().getUser().getId(), bookType);
            }

            @Override
            public Observable<ListTag> initNextApi() {
                return Retro.getAppApi().getNextTags(
                        Shaft.sUserModel.getResponse().getAccess_token(), mModel.getNextUrl());
            }
        };
    }

    @Override
    public BaseAdapter<TagsBean, RecyBookTagBinding> adapter() {
        return new BookedTagAdapter(allItems, mContext, false).setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                Channel channel = new Channel();
                channel.setReceiver(bookType);
                channel.setObject(allItems.get(position).getName());
                EventBus.getDefault().post(channel);

                mActivity.finish();
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
    public void onFirstLoaded(List<TagsBean> tagsBeans) {
        //全部
        TagsBean all = new TagsBean();
        all.setCount(-1);
        all.setName("");
        allItems.add(0, all);

        //未分类
        TagsBean unSeparated = new TagsBean();
        unSeparated.setCount(-1);
        unSeparated.setName("未分類");
        allItems.add(0, unSeparated);
    }
}
