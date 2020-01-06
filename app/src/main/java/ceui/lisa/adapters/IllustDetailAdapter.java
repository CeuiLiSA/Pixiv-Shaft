package ceui.lisa.adapters;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.download.FileCreator;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;


/**
 * 作品详情页竖向多P列表
 */
public class IllustDetailAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private Context mContext;
    private OnItemClickListener mOnItemClickListener;
    private IllustsBean allIllust;
    private int imageSize = 0;
    private TagHolder gifHolder;
    private boolean playGif = false;

    public IllustDetailAdapter(IllustsBean list, Context context) {
        mContext = context;
        allIllust = list;
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
        final TagHolder currentOne = (TagHolder) holder;

        if (position == 0) {
            ViewGroup.LayoutParams params = currentOne.illust.getLayoutParams();
            params.height = imageSize * allIllust.getHeight() / allIllust.getWidth();
            params.width = imageSize;
            currentOne.illust.setLayoutParams(params);
            if (Shaft.sSettings.isFirstImageSize()) {
                Glide.with(mContext)
                        .load(GlideUtil.getOriginal(allIllust, position))
                        .into(currentOne.illust);
            } else {
                Glide.with(mContext)
                        .load(GlideUtil.getLargeImage(allIllust, position))
                        .into(currentOne.illust);
            }
            Common.showLog("height " + params.height + "width " + params.width);

            if (allIllust.getType().equals("ugoira")) {
                gifHolder = currentOne;
                startGif();
            } else {
                currentOne.playGif.setVisibility(View.GONE);
            }
        } else {
            Glide.with(mContext)
                    .asBitmap()
                    .load(Shaft.sSettings.isFirstImageSize() ? GlideUtil.getOriginal(allIllust, position) :
                            GlideUtil.getLargeImage(allIllust, position))
                    .into(new SimpleTarget<Bitmap>() {
                        @Override
                        public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                            ViewGroup.LayoutParams params = currentOne.illust.getLayoutParams();
                            params.width = imageSize;
                            params.height = imageSize * resource.getHeight() / resource.getWidth();
                            currentOne.illust.setLayoutParams(params);
                            currentOne.illust.setImageBitmap(resource);
                        }
                    });
        }

        if (mOnItemClickListener != null) {
            currentOne.itemView.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 0));
            currentOne.playGif.setOnClickListener(v -> {
                currentOne.mProgressBar.setVisibility(View.VISIBLE);
                currentOne.playGif.setVisibility(View.GONE);
                mOnItemClickListener.onItemClick(v, position, 1);
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

    public boolean isPlayGif() {
        return playGif;
    }

    public void setPlayGif(boolean playGif) {
        Common.showLog("IllustDetailAdapter 停止播放gif图");
        this.playGif = playGif;
    }

    public void startGif() {
        Common.showLog("IllustDetailAdapter 开始播放gif图");
        playGif = true;
        File parentFile = FileCreator.createGifParentFile(allIllust);
        if (parentFile.exists()) {
            gifHolder.mProgressBar.setVisibility(View.GONE);
            gifHolder.playGif.setVisibility(View.GONE);
            final File[] listfile = parentFile.listFiles();
            Observable.create(new ObservableOnSubscribe<File>() {
                @Override
                public void subscribe(ObservableEmitter<File> emitter) throws Exception {
                    List<File> allFiles = Arrays.asList(listfile);
                    Collections.sort(allFiles, new Comparator<File>() {
                        @Override
                        public int compare(File o1, File o2) {
                            if (Integer.valueOf(o1.getName().substring(0, o1.getName().length() - 4)) >
                                    Integer.valueOf(o2.getName().substring(0, o2.getName().length() - 4))) {
                                return 1;
                            } else {
                                return -1;
                            }
                        }
                    });
                    int xyz = 0;
                    int count = allFiles.size();
                    while (true) {
                        if (playGif) {
                            emitter.onNext(allFiles.get(xyz % count));
                            Thread.sleep(85);
                            xyz++;
                        } else {
                            break;
                        }
                    }
                }
            }).subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<File>() {
                        @Override
                        public void onNext(File s) {
                            Glide.with(mContext)
                                    .load(s)
                                    .placeholder(gifHolder.illust.getDrawable())
                                    .into(gifHolder.illust);
                        }
                    });
        } else {
            gifHolder.playGif.setVisibility(View.VISIBLE);
        }
    }

    public static class TagHolder extends RecyclerView.ViewHolder {
        ImageView illust, playGif;
        ProgressBar mProgressBar;

        TagHolder(View itemView) {
            super(itemView);
            illust = itemView.findViewById(R.id.illust_image);
            playGif = itemView.findViewById(R.id.play_gif);
            mProgressBar = itemView.findViewById(R.id.gif_progress);
        }
    }
}
