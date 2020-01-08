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
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.database.IllustTask;
import ceui.lisa.download.FileCreator;
import ceui.lisa.download.GifListener;
import ceui.lisa.download.GifQueue;
import ceui.lisa.gif.GifManager;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
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
    private AnimationDrawable animationDrawable;

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

            if (!TextUtils.isEmpty(allIllust.getType()) && allIllust.getType().equals("ugoira")) {
                gifHolder = currentOne;
                currentOne.playGif.setVisibility(View.VISIBLE);

                if(GifQueue.get().getTasks() != null &&
                        GifQueue.get().getTasks().size() != 0) {

                    boolean isDownloading = false;

                    for (IllustTask task : GifQueue.get().getTasks()) {
                        if (task.getIllustsBean().getId() == allIllust.getId()) {
                            isDownloading = true;
                            ((GifListener) task.getDownloadTask().getListener()).bind(currentOne.mProgressBar);
                            break;
                        }
                    }
                    if(isDownloading){
                        currentOne.mProgressBar.setVisibility(View.VISIBLE);
                        currentOne.playGif.setVisibility(View.INVISIBLE);
                    } else {
                        currentOne.playGif.setVisibility(View.VISIBLE);
                    }
                } else {
                    currentOne.playGif.setVisibility(View.VISIBLE);
                }

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
                currentOne.playGif.setVisibility(View.INVISIBLE);
                new GifManager().getZipUrl(allIllust, currentOne.mProgressBar);
                //mOnItemClickListener.onItemClick(v, position, 1);
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

    public void setPlayGif(boolean playGif) {
        Common.showLog(allIllust.getTitle() + "IllustDetailAdapter 停止播放gif图");
        if(animationDrawable != null){
            animationDrawable.stop();
        }
        this.playGif = playGif;
    }

    public void startGif(int delay) {
        Common.showLog(allIllust.getTitle() + "IllustDetailAdapter 开始播放gif图 delay " + delay);
        playGif = true;
        File parentFile = FileCreator.createGifParentFile(allIllust);
        if (parentFile.exists()) {
            gifHolder.mProgressBar.setVisibility(View.GONE);
            gifHolder.playGif.setVisibility(View.GONE);
            final File[] listfile = parentFile.listFiles();
            Observable.create(new ObservableOnSubscribe<String>() {
                @Override
                public void subscribe(ObservableEmitter<String> emitter) {

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

                    animationDrawable = new AnimationDrawable();
                    for (int i = 0; i < allFiles.size(); i++) {
                        try {
                            Drawable drawable = Glide.with(mContext)
                                    .load(allFiles.get(i))
                                    .skipMemoryCache(true)
                                    .submit().get();
                            animationDrawable.addFrame(drawable, delay);
                            emitter.onNext("5");
                        } catch (ExecutionException | InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }).subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<String>() {
                        @Override
                        public void onNext(String s) {
                            gifGo();
                        }
                    });
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

    public void gifGo() {
        if(animationDrawable != null) {
            gifHolder.illust.setImageDrawable(animationDrawable);
            animationDrawable.start();
        }
    }
}
