package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.BookedTagAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyBookTagBinding;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.ListTag;
import ceui.lisa.models.TagsBean;
import ceui.lisa.repo.BookedTagRepo;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.view.LinearItemDecoration;

public class FragmentBookedTag extends NetListFragment<FragmentBaseListBinding,
        ListTag, TagsBean> {

    private String starType = "";
    private int type = 0;

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

    /**
     * @param type 0 插画 1 小说
     * @param starType public/private 公开收藏或者私人收藏
     * @return FragmentBookedTag
     */
    public static FragmentBookedTag newInstance(int type, String starType) {
        Bundle args = new Bundle();
        args.putInt(Params.DATA_TYPE, type);
        args.putString(Params.STAR_TYPE, starType);
        FragmentBookedTag fragment = new FragmentBookedTag();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        starType = bundle.getString(Params.STAR_TYPE);
        type = bundle.getInt(Params.DATA_TYPE, 0);
    }

    @Override
    public RemoteRepo<ListTag> repository() {
        return new BookedTagRepo(type, starType);
    }

    @Override
    public BaseAdapter<TagsBean, RecyBookTagBinding> adapter() {
        return new BookedTagAdapter(allItems, mContext, false).setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                Intent intent = new Intent(type == 1 ? Params.FILTER_NOVEL : Params.FILTER_ILLUST);
                intent.putExtra(Params.CONTENT, allItems.get(position).getName());
                intent.putExtra(Params.STAR_TYPE, starType);
                LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
                mActivity.finish();
            }
        });
    }

    @Override
    public String getToolbarTitle() {
        return getString(R.string.string_244);
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
