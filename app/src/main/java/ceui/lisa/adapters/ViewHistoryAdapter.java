package ceui.lisa.adapters;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Matrix;
import android.support.annotation.NonNull;
import android.support.v7.widget.RecyclerView;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.RotateAnimation;
import android.widget.ImageView;
import android.widget.TextView;

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
import ceui.lisa.interfs.OnItemClickListener;
import ceui.lisa.response.IllustsBean;
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
    private SimpleDateFormat mTime = new SimpleDateFormat("MM月dd日 HH: mm");



    public ViewHistoryAdapter(List<IllustHistoryEntity> list, Context context) {
        mContext = context;
        mLayoutInflater = LayoutInflater.from(mContext);
        allIllust = list;
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                mContext.getResources().getDimensionPixelSize(R.dimen.four_dp))/2;
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
        currentOne.time.setText(mTime.format(allIllust.get(position).getTime()));



        //从-400 丝滑滑动到0
        currentOne.spring.setCurrentValue(-400);
        currentOne.spring.setEndValue(0);






        if(mOnItemClickListener != null){

            holder.itemView.setOnClickListener(v -> {
//                ObjectAnimator animator = ObjectAnimator.ofFloat(currentOne.title,
//                        "translationX", 0, 300, -100, 200, -50, 0);
//                animator.setDuration(2000);
//                animator.start();

//                AnimatorSet animatorSet = new AnimatorSet();
//                ObjectAnimator animator = ObjectAnimator.ofFloat(currentOne.itemView,
//                        "rotationY", 0f, -90f, 0f);
//
//                ObjectAnimator animator22 = ObjectAnimator.ofFloat(currentOne.itemView,
//                        "scaleX", 1.0f, 0.2f, 0.6f, 1.0f);
//                //currentOne.itemView.setPivotX(-100);//设置指定旋转中心点X坐标
//
//                animatorSet.playTogether(animator, animator22);
//                animatorSet.setDuration(1000);
//                currentOne.itemView.setPivotX(0);
//                animatorSet.start();

                if(position % 2 == 0) {

                    currentOne.itemView.setPivotX(-100.0f);
                    //currentOne.itemView.setPivotY(-200.0f);
                    ObjectAnimator animator = ObjectAnimator.ofFloat(currentOne.itemView,
                            "rotationY", 0, -50.0f, 0.0f);
                    animator.setDuration(1000);
                    animator.start();
                }else {
                    currentOne.itemView.setPivotX(-100.0f);
                    ObjectAnimator animator = ObjectAnimator.ofFloat(currentOne.itemView,
                            "rotationY", 0, 30.0f, 0.0f);
                    animator.setDuration(1000);
                    animator.start();

                }
//                currentOne.itemView.setPivotX(0);
//                currentOne.itemView.setRotationY(-30.0f);
//                currentOne.itemView.setScaleX(0.5f);
                //mOnItemClickListener.onItemClick(v, position, 0);
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
        TextView title, time, author;
        Spring spring;
        TagHolder(View itemView) {
            super(itemView);

            illust = itemView.findViewById(R.id.illust_image);
            title = itemView.findViewById(R.id.title);
            time = itemView.findViewById(R.id.time);
            author = itemView.findViewById(R.id.author);
            SpringSystem springSystem = SpringSystem.create();

            // Add a spring to the system.
            spring = springSystem.createSpring();

            spring.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(40,5));
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
