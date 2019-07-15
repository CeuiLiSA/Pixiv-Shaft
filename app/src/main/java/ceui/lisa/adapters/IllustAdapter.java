package ceui.lisa.adapters;

import android.content.Context;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;


import com.bumptech.glide.Glide;
import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringSystem;
import com.scwang.smartrefresh.layout.api.RefreshLayout;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.interfaces.MultiDownload;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.view.GridScrollChangeManager;
import ceui.lisa.view.ScrollChangeManager;


/**
 * 网格Grid 列表适配器
 */
public class IllustAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> implements MultiDownload {

    private Context mContext;
    private LayoutInflater mLayoutInflater;
    private OnItemClickListener mOnItemClickListener;
    private List<IllustsBean> allIllust;
    private int imageSize = 0;
    private SpringSystem mSystem = SpringSystem.create();
    private Handler mHandler = new Handler();
    private GridScrollChangeManager mManager;
    private int state = 1; //1空闲，2正在动画中
    private RecyclerView mRecyclerView;
    private RefreshLayout mRefreshLayout;

    public IllustAdapter(List<IllustsBean> list, Context context,
                         RecyclerView recyclerView, RefreshLayout refreshLayout) {
        mContext = context;
        mLayoutInflater = LayoutInflater.from(mContext);
        allIllust = list;
        mRecyclerView = recyclerView;
        mRefreshLayout = refreshLayout;
        mManager = (GridScrollChangeManager) mRecyclerView.getLayoutManager();
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                mContext.getResources().getDimensionPixelSize(R.dimen.four_dp))/2;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mLayoutInflater.inflate(R.layout.recy_illust_grid, parent, false);
        return new TagHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        final TagHolder currentOne = (TagHolder) holder;
        ViewGroup.LayoutParams params = currentOne.illust.getLayoutParams();
        params.height = imageSize;
        params.width = imageSize;
        currentOne.illust.setLayoutParams(params);
        Glide.with(mContext)
                .load(GlideUtil.getMediumImg(allIllust.get(position)))
                .placeholder(R.color.light_bg)
                .into(currentOne.illust);
        if(mOnItemClickListener != null){
            holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    startDownload();
                    return true;
                }
            });

            holder.itemView.setOnClickListener(v -> {
                if(Shaft.sSettings.isGridAnime()){
                    if (state == 1) {
                        mRefreshLayout.setEnableLoadMore(false);
                        mManager.setCanScroll(false);
                        state = 2;
                        long delay = 80L;
                        for (int i = 0; i < mRecyclerView.getChildCount(); i++) {
                            View view = mRecyclerView.getChildAt(i);
                            if (view != null) {
                                if (view == currentOne.itemView) {
                                    //如果是被点击的view，先不做任何事

                                } else {
                                    //被点击的view不动，其他的view开始依次翻转
                                    int[] array = new int[2];

                                    view.getLocationOnScreen(array);

                                    if (array[0] > imageSize) {
                                        view.setPivotX(-750f);
                                    } else {
                                        view.setPivotX(-200f);
                                    }
                                    view.setCameraDistance(80000f);
                                    AnimeEndRunnable animeRunnable = new AnimeEndRunnable();
                                    animeRunnable.setRorateY(-180);
                                    animeRunnable.setView(view);
                                    mHandler.postDelayed(animeRunnable, delay);
                                    delay = delay + 90L;
                                }



                                //最后翻转被点击的view， 并设置动画结束的回调
                                if (i == mRecyclerView.getChildCount() - 1) {
                                    int[] array = new int[2];

                                    currentOne.itemView.getLocationOnScreen(array);

                                    if (array[0] > imageSize) {
                                        currentOne.itemView.setPivotX(-750f);
                                    } else {
                                        currentOne.itemView.setPivotX(-200f);
                                    }
                                    currentOne.itemView.setCameraDistance(80000f);
                                    AnimeEndRunnable animeRunnable = new AnimeEndRunnable();
                                    animeRunnable.setOnAnimeEnd(new OnAnimeEnd() {
                                        @Override
                                        public void onAnimeEndPerform() {
                                            mOnItemClickListener.onItemClick(currentOne.illust, position, 0);
                                        }
                                    });
                                    animeRunnable.setView(currentOne.itemView);
                                    animeRunnable.setRorateY(-180);
                                    mHandler.postDelayed(animeRunnable, delay + 50L);
                                }
                            }
                        }
                    }
                }else {
                    mOnItemClickListener.onItemClick(currentOne.illust, position, 0);
                }
            });
        }
    }

    @Override
    public List<IllustsBean> getIllustList() {
        return allIllust;
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    public class AnimeEndRunnable implements Runnable {

        private int rotateY;
        private Spring mSpring;
        private View mView;
        private OnAnimeEnd mOnAnimeEnd;

        @Override
        public void run() {
            mSpring = mSystem.createSpring();
            mSpring.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(20, 3));

            mSpring.addListener(new SimpleSpringListener() {
                @Override
                public void onSpringUpdate(Spring spring) {
                    float temp = (float) spring.getCurrentValue();
                    if (temp < -100f) {
                        mSpring.setAtRest();
                    } else {
                        mView.setRotationY(temp);
                    }
                }

                @Override
                public void onSpringAtRest(Spring spring) {
                    super.onSpringAtRest(spring);
                    if (mOnAnimeEnd != null) {
                        state = 1;
                        mManager.setCanScroll(true);
                        mRefreshLayout.setEnableLoadMore(true);
                        mOnAnimeEnd.onAnimeEndPerform();
                    }
                }
            });
            mSpring.setCurrentValue(0);
            mSpring.setEndValue(rotateY);
        }

        void setRorateY(int rotateY) {
            this.rotateY = rotateY;
        }

        void setView(View view) {
            mView = view;
        }

        void setOnAnimeEnd(OnAnimeEnd onAnimeEnd) {
            mOnAnimeEnd = onAnimeEnd;
        }
    }


    public interface OnAnimeEnd {
        void onAnimeEndPerform();
    }

    @Override
    public int getItemCount() {
        return allIllust.size();
    }

    public void setOnItemClickListener(OnItemClickListener itemClickListener) {
        mOnItemClickListener = itemClickListener;
    }

    //还原被翻转的卡片
    public void flipToOrigin() {
        for (int i = 0; i < mRecyclerView.getChildCount(); i++) {
            View view = mRecyclerView.getChildAt(i);
            if (view != null) {
                view.setRotationY(0f);
                view.setRotationY(0f);
            }
        }
    }

    public static class TagHolder extends RecyclerView.ViewHolder {
        ImageView illust;

        TagHolder(View itemView) {
            super(itemView);
            illust = itemView.findViewById(R.id.illust_image);
        }
    }
}
