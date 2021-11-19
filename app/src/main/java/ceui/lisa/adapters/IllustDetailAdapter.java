package ceui.lisa.adapters;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;

import static com.bumptech.glide.load.resource.bitmap.BitmapTransitionOptions.withCrossFade;


/**
 * 作品详情页竖向多P列表
 */
public class IllustDetailAdapter extends AbstractIllustAdapter<RecyclerView.ViewHolder> {

    private Fragment mFragment;

    public IllustDetailAdapter(IllustsBean list, Context context, boolean isForceOriginal) {
        mContext = context;
        allIllust = list;
        this.isForceOriginal = isForceOriginal;
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                2 * mContext.getResources().getDimensionPixelSize(R.dimen.twelve_dp));
    }

    public IllustDetailAdapter(IllustsBean list, Context context) {
        this(list, context, false);
    }

    public IllustDetailAdapter(Fragment fragment, IllustsBean list) {
        this(fragment, list, false);
    }

    public IllustDetailAdapter(Fragment fragment, IllustsBean list, boolean isForceOriginal) {
        mFragment = fragment;
        mContext = fragment.requireContext();
        allIllust = list;
        this.isForceOriginal = isForceOriginal;
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                2 * mContext.getResources().getDimensionPixelSize(R.dimen.twelve_dp));
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
        super.onBindViewHolder(holder, position);
        final TagHolder currentOne = (TagHolder) holder;
        Common.showLog("IllustDetailAdapter onBindViewHolder 000");
        boolean isLoadOriginalImage = Shaft.sSettings.isShowOriginalPreviewImage() || isForceOriginal;
        final GlideUrl imageUrl = isLoadOriginalImage ? GlideUtil.getOriginalImage(allIllust, position) :
                GlideUtil.getLargeImage(allIllust, position);
        RequestManager requestManager = this.mFragment != null ? Glide.with(this.mFragment) : Glide.with(mContext);
        if (position == 0) {
            ViewGroup.LayoutParams params = currentOne.illust.getLayoutParams();
            params.height = imageSize * allIllust.getHeight() / allIllust.getWidth();
            params.width = imageSize;
            currentOne.illust.setLayoutParams(params);
            requestManager
                    .asDrawable()
                    .load(imageUrl)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(new SimpleTarget<Drawable>() {
                        @Override
                        public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                            currentOne.illust.setImageDrawable(resource);
                            if(isLoadOriginalImage){
                                Shaft.getMMKV().encode(imageUrl.toStringUrl(), true);
                            }
                        }
                    });
        } else {
            requestManager
                    .asBitmap()
                    .load(imageUrl)
                    .transition(withCrossFade())
                    .into(new SimpleTarget<Bitmap>() {
                        @Override
                        public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                            ViewGroup.LayoutParams params = currentOne.illust.getLayoutParams();
                            params.width = imageSize;
                            params.height = imageSize * resource.getHeight() / resource.getWidth();
                            currentOne.illust.setLayoutParams(params);
                            currentOne.illust.setImageBitmap(resource);
                            if(isLoadOriginalImage){
                                Shaft.getMMKV().encode(imageUrl.toStringUrl(), true);
                            }
                        }
                    });
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
