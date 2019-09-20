package ceui.lisa.adapters;

import android.content.Context;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringSystem;
import com.google.gson.Gson;

import java.text.SimpleDateFormat;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.database.IllustHistoryEntity;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.utils.GlideUtil;


/**
 *
 */
public class ViewHistoryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private Context mContext;
    private LayoutInflater mLayoutInflater;
    private OnItemClickListener mOnItemClickListener;
    private List<IllustHistoryEntity> allIllust;
    private Gson mGson = new Gson();
    private int imageSize = 0;
    private Handler mHandler = new Handler();
    private SimpleDateFormat mTime = new SimpleDateFormat("MM月dd日 HH: mm");


    public ViewHistoryAdapter(List<IllustHistoryEntity> list, Context context) {
        mContext = context;
        mLayoutInflater = LayoutInflater.from(mContext);
        allIllust = list;
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                mContext.getResources().getDimensionPixelSize(R.dimen.four_dp)) / 2;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mLayoutInflater.inflate(R.layout.recy_view_history, parent, false);
        return new TagHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        final TagHolder currentOne = (TagHolder) holder;
        ViewGroup.LayoutParams params = currentOne.illust.getLayoutParams();
        params.height = imageSize;
        params.width = imageSize;
        IllustsBean currentIllust = mGson.fromJson(allIllust.get(position).getIllustJson(), IllustsBean.class);
        currentOne.illust.setLayoutParams(params);
        Glide.with(mContext)
                .load(GlideUtil.getMediumImg(currentIllust))
                .placeholder(R.color.light_bg)
                .into(currentOne.illust);
        currentOne.title.setText(currentIllust.getTitle());
        currentOne.author.setText("by: " + currentIllust.getUser().getName());
        if (currentIllust.getPage_count() == 1) {
            currentOne.pSize.setVisibility(View.GONE);
        } else {
            currentOne.pSize.setVisibility(View.VISIBLE);
            currentOne.pSize.setText(currentIllust.getPage_count() + "P");
        }
        currentOne.time.setText(mTime.format(allIllust.get(position).getTime()));


        //从-400 丝滑滑动到0
        currentOne.spring.setCurrentValue(-400);
        currentOne.spring.setEndValue(0);


        if (mOnItemClickListener != null) {

            holder.itemView.setOnClickListener(v -> {
                mOnItemClickListener.onItemClick(v, position, 0);
            });
        }
    }

    @Override
    public int getItemCount() {
        return allIllust.size();
    }

    public void setOnItemClickListener(OnItemClickListener itemClickListener) {
        mOnItemClickListener = itemClickListener;
    }

    public void clearAllData() {
        allIllust.clear();
        notifyDataSetChanged();
    }

    public static class TagHolder extends RecyclerView.ViewHolder {
        ImageView illust;
        TextView title, time, author, pSize;
        Spring spring;

        TagHolder(View itemView) {
            super(itemView);

            illust = itemView.findViewById(R.id.illust_image);
            title = itemView.findViewById(R.id.title);
            time = itemView.findViewById(R.id.time);
            author = itemView.findViewById(R.id.author);
            pSize = itemView.findViewById(R.id.p_size);
            SpringSystem springSystem = SpringSystem.create();

            // Add a spring to the system.
            spring = springSystem.createSpring();

            spring.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(40, 5));
            spring.addListener(new SimpleSpringListener() {

                @Override
                public void onSpringUpdate(Spring spring) {
                    // You can observe the updates in the spring
                    // state by asking its current value in onSpringUpdate.
                    itemView.setTranslationX((float) spring.getCurrentValue());
                    //Common.showLog(spring.getCurrentValue());
                }
            });

        }
    }
}
