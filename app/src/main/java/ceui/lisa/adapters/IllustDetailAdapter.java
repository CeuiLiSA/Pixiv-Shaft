package ceui.lisa.adapters;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.database.IllustTask;
import ceui.lisa.download.FileCreator;
import ceui.lisa.download.GifQueue;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.models.GifResponse;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.PixivOperate;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import static com.bumptech.glide.load.resource.bitmap.BitmapTransitionOptions.withCrossFade;


/**
 * 作品详情页竖向多P列表
 */
public class IllustDetailAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private Context mContext;
    private OnItemClickListener mOnItemClickListener;
    private IllustsBean allIllust;
    private int imageSize;

    public Map<Integer, Boolean> getHasLoad() {
        if (hasLoad == null) {
            hasLoad = new HashMap<>();
        }
        return hasLoad;
    }

    private Map<Integer, Boolean> hasLoad = new HashMap<>();

    public IllustDetailAdapter(IllustsBean list, FragmentActivity context) {
        mContext = context;
        allIllust = list;
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                2 * mContext.getResources().getDimensionPixelSize(R.dimen.twelve_dp));
        hasLoad.clear();
        for (int i = 0; i < list.getPage_count(); i++) {
            hasLoad.put(i, false);
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new TagHolder(
                LayoutInflater.from(mContext).inflate(
                        R.layout.recy_illust_grid, parent, false)
        );
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        final TagHolder currentOne = (TagHolder) holder;

        if (position == 0) {
            ViewGroup.LayoutParams params = currentOne.illust.getLayoutParams();
            params.height = imageSize * allIllust.getHeight() / allIllust.getWidth();
            params.width = imageSize;
            currentOne.illust.setLayoutParams(params);
            Glide.with(mContext)
                    .asDrawable()
                    .load(GlideUtil.getLargeImage(allIllust, position))
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(new SimpleTarget<Drawable>() {
                        @Override
                        public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                            currentOne.illust.setImageDrawable(resource);
                            hasLoad.put(0, true);
                        }
                    });
        } else {
            Glide.with(mContext)
                    .asBitmap()
                    .load(GlideUtil.getLargeImage(allIllust, position))
                    .transition(withCrossFade())
                    .into(new SimpleTarget<Bitmap>() {
                        @Override
                        public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                            ViewGroup.LayoutParams params = currentOne.illust.getLayoutParams();
                            params.width = imageSize;
                            params.height = imageSize * resource.getHeight() / resource.getWidth();
                            currentOne.illust.setLayoutParams(params);
                            currentOne.illust.setImageBitmap(resource);
                            hasLoad.put(position, true);
                        }
                    });
        }

        if (mOnItemClickListener != null) {
            currentOne.itemView.setOnClickListener(v -> {
                mOnItemClickListener.onItemClick(currentOne.illust, position, 0);
            });
        }
    }

    @Override
    public int getItemCount() {
        return allIllust.getPage_count();
    }

    public void setOnItemClickListener(OnItemClickListener itemClickListener) {
        mOnItemClickListener = itemClickListener;
    }

    public static class TagHolder extends RecyclerView.ViewHolder {
        ImageView illust;

        TagHolder(View itemView) {
            super(itemView);
            illust = itemView.findViewById(R.id.illust_image);
        }
    }
}
