package ceui.lisa.adapters;

import android.content.Context;
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
import ceui.lisa.database.DownloadEntity;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.models.IllustsBean;


/**
 *
 */
public class DownlistAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private Context mContext;
    private OnItemClickListener mOnItemClickListener;
    private List<DownloadEntity> allIllust;
    private Gson mGson = new Gson();
    private int imageSize = 0;
    private SimpleDateFormat mTime = new SimpleDateFormat("MM月dd日 HH: mm");


    public DownlistAdapter(List<DownloadEntity> list, Context context) {
        mContext = context;
        allIllust = list;
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                mContext.getResources().getDimensionPixelSize(R.dimen.four_dp)) / 2;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new TagHolder(
                LayoutInflater.from(mContext).inflate(
                        R.layout.recy_view_history, parent, false)
        );
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        final TagHolder currentOne = (TagHolder) holder;
        ViewGroup.LayoutParams params = currentOne.illust.getLayoutParams();
        params.height = imageSize;
        params.width = imageSize;
        IllustsBean currentIllust = mGson.fromJson(allIllust.get(position).getIllustGson(), IllustsBean.class);
        currentOne.illust.setLayoutParams(params);
        Glide.with(mContext)
                .load(allIllust.get(position).getFilePath())
                .placeholder(R.color.light_bg)
                .into(currentOne.illust);
        currentOne.title.setText(allIllust.get(position).getFileName());
        currentOne.author.setText("by: " + currentIllust.getUser().getName());
        if (currentIllust.getPage_count() == 1) {
            currentOne.pSize.setVisibility(View.GONE);
        } else {
            currentOne.pSize.setVisibility(View.VISIBLE);
            currentOne.pSize.setText(currentIllust.getPage_count() + "P");
        }
        currentOne.time.setText(mTime.format(allIllust.get(position).getDownloadTime()));


        //从-400 丝滑滑动到0
        currentOne.spring.setCurrentValue(-400);
        currentOne.spring.setEndValue(0);


        if (mOnItemClickListener != null) {
            currentOne.itemView.setOnClickListener(v -> {
                mOnItemClickListener.onItemClick(v, position, 0);
            });
            currentOne.author.setOnClickListener(v -> {
                mOnItemClickListener.onItemClick(v, position, 1);
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
            spring = springSystem.createSpring();
            spring.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(40, 5));
            spring.addListener(new SimpleSpringListener() {

                @Override
                public void onSpringUpdate(Spring spring) {
                    itemView.setTranslationX((float) spring.getCurrentValue());
                }
            });
        }
    }
}
