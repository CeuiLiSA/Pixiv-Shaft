package ceui.lisa.adapters;

import android.content.Context;
import android.graphics.Point;
import android.support.annotation.NonNull;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringSystem;
import com.facebook.rebound.SpringUtil;
import com.scwang.smartrefresh.layout.util.DensityUtil;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.interfs.OnItemClickListener;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;


/**
 *
 */
public class IllustStagAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private Context mContext;
    private LayoutInflater mLayoutInflater;
    private OnItemClickListener mOnItemClickListener;
    private List<IllustsBean> allIllust;
    private int imageSize = 0;

    public IllustStagAdapter(List<IllustsBean> list, Context context) {
        mContext = context;
        mLayoutInflater = LayoutInflater.from(mContext);
        allIllust = list;
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels)/2;


    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mLayoutInflater.inflate(R.layout.recy_illust_stagger, parent, false);
        return new TagHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        final TagHolder currentOne = (TagHolder) holder;
        ViewGroup.LayoutParams params = currentOne.illust.getLayoutParams();

        params.width = imageSize;
        params.height = allIllust.get(position).getHeight() * imageSize / allIllust.get(position).getWidth();

        if(params.height < 300){
            params.height = 300;
        }else if(params.height > 600){
            params.height = 600;
        }
        currentOne.illust.setLayoutParams(params);
        currentOne.title.setText(allIllust.get(position).getTitle());
        Glide.with(mContext)
                .load(GlideUtil.getMediumImg(allIllust.get(position)))
                .placeholder(R.color.light_bg)
                .into(currentOne.illust);

        if(allIllust.get(position).getPage_count() == 1){
            currentOne.pSize.setVisibility(View.GONE);
        }else {
            currentOne.pSize.setVisibility(View.VISIBLE);
            currentOne.pSize.setText(allIllust.get(position).getPage_count() + "P");
        }
        if(mOnItemClickListener != null){
            holder.itemView.setOnClickListener(v -> {
                mOnItemClickListener.onItemClick(currentOne.illust, position, 0);
            });

        }

        currentOne.spring.setCurrentValue(50);
        currentOne.spring.setEndValue(0);
    }

    @Override
    public int getItemCount() {
        return allIllust.size();
    }

    public void setOnItemClickListener(OnItemClickListener itemClickListener) {
        mOnItemClickListener = itemClickListener;
    }

    public static class TagHolder extends RecyclerView.ViewHolder {
        ImageView illust;
        TextView title, pSize;
        private Spring spring;

        TagHolder(View itemView) {
            super(itemView);
            illust = itemView.findViewById(R.id.illust_image);
            title = itemView.findViewById(R.id.title);
            pSize = itemView.findViewById(R.id.p_size);

            SpringSystem springSystem = SpringSystem.create();

            // Add a spring to the system.
            spring = springSystem.createSpring();

            spring.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(40,4));
            spring.addListener(new SimpleSpringListener() {

                @Override
                public void onSpringUpdate(Spring spring) {
                    // You can observe the updates in the spring
                    // state by asking its current value in onSpringUpdate.
                    itemView.setRotation((float) spring.getCurrentValue());
                    //itemView.setScaleY((float) (0.5 + spring.getCurrentValue() / 2));
                    //Common.showLog(spring.getCurrentValue());
                }
            });
        }
    }
}
