package ceui.lisa.fragments;

import androidx.recyclerview.widget.LinearLayoutManager;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.NAdapterWithHeadView;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyNovelBinding;
import ceui.lisa.model.ListNovel;
import ceui.lisa.models.NovelBean;
import ceui.lisa.repo.RecmdNovelRepo;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.LinearItemWithHeadDecoration;

public class FragmentRecmdNovel extends NetListFragment<FragmentBaseListBinding,
        ListNovel, NovelBean> {

    private final List<NovelBean> ranking = new ArrayList<>();

    @Override
    public RemoteRepo<ListNovel> repository() {
        return new RecmdNovelRepo();
    }

    @Override
    public BaseAdapter<NovelBean, RecyNovelBinding> adapter() {
        return new NAdapterWithHeadView(allItems, mContext);
    }

    @Override
    public boolean showToolbar() {
        return false;
    }

    @Override
    public void onFirstLoaded(List<NovelBean> novelBeans) {
        ranking.addAll(mResponse.getRanking_novels());
        ((NAdapterWithHeadView) mAdapter).setHeadData(ranking);
    }

    @Override
    public void initRecyclerView() {
        mRecyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        mRecyclerView.setHasFixedSize(true);
        mRecyclerView.addItemDecoration(new LinearItemWithHeadDecoration(DensityUtil.dp2px(12.0f)));
    }
}
