package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.bumptech.glide.Glide;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.core.RecmdHeader;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.interfaces.MultiDownload;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DataChannel;
import ceui.lisa.utils.GlideUtil;

public class IAdapterWithHead extends BaseAdapter<IllustsBean, RecyIllustStaggerBinding> implements MultiDownload {

    private static final int MIN_HEIGHT = 350;
    private static final int MAX_HEIGHT = 600;
    private int imageSize;
    private boolean isSquare = false;

    private RecmdHeader mRecmdHeader = null;

    public IAdapterWithHead(List<IllustsBean> targetList, Context context) {
        super(targetList, context);
        initImageSize();
        handleClick();
    }

    private void initImageSize() {
        if (isSquare) {
            imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                    mContext.getResources().getDimensionPixelSize(R.dimen.tweenty_four_dp)) / 2;
        } else {
            imageSize = (mContext.getResources().getDisplayMetrics().widthPixels) / 2;
        }
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_illust_stagger;
    }

    @Override
    public void bindData(IllustsBean target, ViewHolder<RecyIllustStaggerBinding> bindView, int position) {
        ViewGroup.LayoutParams params = bindView.baseBind.illustImage.getLayoutParams();

        params.width = imageSize;

        if (isSquare) {
            params.height = imageSize;
        } else {
            params.height = target.getHeight() * imageSize / target.getWidth();

            if (params.height < MIN_HEIGHT) {
                params.height = MIN_HEIGHT;
            } else if (params.height > MAX_HEIGHT) {
                params.height = MAX_HEIGHT;
            }
        }

        bindView.baseBind.illustImage.setLayoutParams(params);
        Glide.with(mContext)
                .load(GlideUtil.getMediumImg(target))
                .placeholder(R.color.second_light_bg)
                .into(bindView.baseBind.illustImage);

        if (target.getPage_count() == 1) {
            bindView.baseBind.pSize.setVisibility(View.GONE);
        } else {
            bindView.baseBind.pSize.setVisibility(View.VISIBLE);
            bindView.baseBind.pSize.setText(target.getPage_count() + "P");
        }
        bindView.baseBind.pGif.setVisibility(target.isGif() ? View.VISIBLE : View.GONE);
        bindView.itemView.setOnClickListener(view -> {
            if (mOnItemClickListener != null) {
                mOnItemClickListener.onItemClick(view, position, 0);
            }
        });
        bindView.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                startDownload();
                return true;
            }
        });
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public List<IllustsBean> getIllustList() {
        return allIllust;
    }

    private void handleClick() {
        setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                DataChannel.get().setIllustList(allIllust);
                Intent intent = new Intent(mContext, ViewPagerActivity.class);
                intent.putExtra("position", position);
                mContext.startActivity(intent);
            }
        });
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
    public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
        ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
        if(lp instanceof StaggeredGridLayoutManager.LayoutParams
                && holder.getLayoutPosition() == 0) {
            StaggeredGridLayoutManager.LayoutParams p = (StaggeredGridLayoutManager.LayoutParams) lp;
            p.setFullSpan(true);
        }
    }
}
