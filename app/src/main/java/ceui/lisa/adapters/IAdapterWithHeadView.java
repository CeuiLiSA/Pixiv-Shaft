package ceui.lisa.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.fragments.ListFragment;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import jp.wasabeef.recyclerview.animators.BaseItemAnimator;
import jp.wasabeef.recyclerview.animators.LandingAnimator;

import static ceui.lisa.activities.Shaft.sUserModel;
import static ceui.lisa.fragments.ListFragment.animateDuration;

public class IAdapterWithHeadView extends IAdapter {

    private RecmdHeader mRecmdHeader = null;
    private RecyclerView mRecyclerView = null;

    public IAdapterWithHeadView(List<IllustsBean> targetList, Context context, RecyclerView recyclerView) {
        super(targetList, context);
        mRecyclerView = recyclerView;
    }

    @Override
    public int headerSize() {
        return 1;
    }

    @Override
    public ViewHolder getHeader(ViewGroup parent) {
        mRecmdHeader = new RecmdHeader(DataBindingUtil.inflate(
                LayoutInflater.from(mContext), R.layout.recy_recmd_header,
                null, false).getRoot());
        mRecmdHeader.initView(mContext);
        return mRecmdHeader;
    }

    public void setHeadData(List<IllustsBean> illustsBeans) {
        if (mRecmdHeader != null) {
            mRecmdHeader.show(mContext, illustsBeans);
        }
    }

    @Override
    public void getRelated(IllustsBean illust, int position) {
        Retro.getAppApi().relatedIllust(sUserModel.getResponse().getAccess_token(), illust.getId())
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<ListIllust>() {
                    @Override
                    public void success(ListIllust listIllust) {
                        if (listIllust.getIllusts() != null) {
                            final int realP = position + 1;
                            if (listIllust.getIllusts().size() >= 6) {
                                List<IllustsBean> related = new ArrayList<>();
                                for (int i = 0; i < 6; i++) {
                                    related.add(listIllust.getIllusts().get(i));
                                }
                                allIllust.addAll(realP, related);
                                notifyItemRangeInserted(realP, related.size());
                                notifyItemRangeChanged(realP, allIllust.size() - realP);
                            }
                        }
                    }
                });
    }

    @Override
    public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
        ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
        if (lp instanceof StaggeredGridLayoutManager.LayoutParams
                && holder.getLayoutPosition() == 0) {
            StaggeredGridLayoutManager.LayoutParams p = (StaggeredGridLayoutManager.LayoutParams) lp;
            p.setFullSpan(true);
        }
    }
}
