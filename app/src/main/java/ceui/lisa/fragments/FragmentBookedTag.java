package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;

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
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.view.LinearItemDecoration;
import io.reactivex.Observable;

public class FragmentBookedTag extends NetListFragment<FragmentBaseListBinding,
        ListTag, TagsBean> {

    private String starType = "";

    /**
     * @param starType public/private 公开收藏或者私人收藏
     * @return FragmentBookedTag
     */
    public static FragmentBookedTag newInstance(String starType) {
        Bundle args = new Bundle();
        args.putString(Params.STAR_TYPE, starType);
        FragmentBookedTag fragment = new FragmentBookedTag();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        starType = bundle.getString(Params.STAR_TYPE);
    }

    @Override
    public RemoteRepo<ListTag> repository() {
        return new RemoteRepo<ListTag>() {
            @Override
            public Observable<ListTag> initApi() {
                return Retro.getAppApi().getBookmarkTags(Shaft.sUserModel.getResponse().getAccess_token(),
                        Shaft.sUserModel.getResponse().getUser().getId(), starType);
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
                Intent intent = new Intent(Params.FILTER_ILLUST);
                intent.putExtra(Params.CONTENT, allItems.get(position).getName());
                intent.putExtra(Params.STAR_TYPE, starType);
                LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
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
