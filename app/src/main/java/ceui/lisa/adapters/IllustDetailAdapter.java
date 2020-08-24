package ceui.lisa.adapters;

import android.app.Activity;
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
import ceui.lisa.activities.Shaft;
import ceui.lisa.database.IllustTask;
import ceui.lisa.download.FileCreator;
import ceui.lisa.download.GifListener;
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

    private FragmentActivity mContext;
    private OnItemClickListener mOnItemClickListener;
    private IllustsBean allIllust;
    private int imageSize = 0;
    private TagHolder gifHolder;
    private AnimationDrawable animationDrawable;

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

            Common.showLog("height " + params.height + "width " + params.width);

            //如果是GIF
            if (!TextUtils.isEmpty(allIllust.getType()) && allIllust.getType().equals("ugoira")) {
                gifHolder = (TagHolder) holder;
                //判断是否存在已合成的GIF文件
                File gifFile = FileCreator.createGifFile(allIllust);
                if (gifFile != null && gifFile.exists() && gifFile.length() > 1000) {
                    currentOne.playGif.setVisibility(View.INVISIBLE);
                    Glide.with(mContext).load(gifFile).into(currentOne.illust);
                } else {
                    //如果不存在已合成的GIF文件，想播放gif必须先调用v1/ugoira/metadata 接口获取delay
                    //（就算你已经有了gif图片列表，不掉接口也不知道delay）

                    GifListener.OnGifPrepared onGifPrepared = new GifListener.OnGifPrepared() {
                        @Override
                        public void play(int delay) {
                            currentOne.mProgressBar.setVisibility(View.INVISIBLE);
                            tryPlayGif(delay);
                        }
                    };

                    //检查是否正在下载
                    if (GifQueue.get().getTasks() != null &&
                            GifQueue.get().getTasks().size() != 0) {

                        boolean isDownloading = false;

                        for (IllustTask task : GifQueue.get().getTasks()) {
                            if (task.getIllustsBean().getId() == allIllust.getId()) {
                                isDownloading = true;
                                //如果正在下载，更新listener
                                currentOne.mProgressBar.setVisibility(View.VISIBLE);
                                GifListener gifListener = (GifListener) task.getDownloadTask().getListener();
                                gifListener.bindProgress(currentOne.mProgressBar);
                                gifListener.bindListener(onGifPrepared);
                                break;
                            }
                        }
                        if (isDownloading) {
                            currentOne.playGif.setVisibility(View.INVISIBLE);
                        } else {
                            currentOne.playGif.setVisibility(View.VISIBLE);
                        }
                    } else {
                        currentOne.playGif.setVisibility(View.VISIBLE);
                    }

                    currentOne.playGif.setOnClickListener(v -> {
                        currentOne.playGif.setVisibility(View.INVISIBLE);
                        PixivOperate.getGifInfo(allIllust, new ErrorCtrl<GifResponse>() {
                            @Override
                            public void onNext(GifResponse gifResponse) {
                                //判断是否存在已解压的GIF原图文件夹
                                File parentFile = FileCreator.createGifParentFile(allIllust);
                                if (parentFile.exists() && parentFile.length() > 1000) {
                                    //存在直接播放
                                    tryPlayGif(gifResponse.getDelay());
                                } else {
                                    //不存在就去下载
                                    currentOne.mProgressBar.setVisibility(View.VISIBLE);
                                    GifListener gifListener = new GifListener(allIllust, gifResponse.getDelay());
                                    gifListener.bindProgress(currentOne.mProgressBar);
                                    gifListener.bindListener(onGifPrepared);
                                    IllustDownload.downloadGif(gifResponse, allIllust, gifListener);
                                }
                            }
                        });
                    });
                }
            } else {
                currentOne.playGif.setVisibility(View.INVISIBLE);
            }
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
                currentOne.illust.setTransitionName("big_image_" + position);
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

    public void nowPlayGif() {
        if (animationDrawable != null && gifHolder != null) {
            gifHolder.illust.setImageDrawable(animationDrawable);
            animationDrawable.start();
        }
    }

    public void nowStopGif() {
        Common.showLog(allIllust.getTitle() + "IllustDetailAdapter 停止播放gif图");
        if (animationDrawable != null) {
            animationDrawable.stop();
        }
    }

    public void tryPlayGif(int delay) {
        //获取所有的gif帧
        final File[] listfile = FileCreator.createGifParentFile(allIllust).listFiles();
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
                        emitter.onNext("万事俱备");
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
                        if ("万事俱备".equals(s)) {
                            nowPlayGif();
                        }
                    }
                });
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
