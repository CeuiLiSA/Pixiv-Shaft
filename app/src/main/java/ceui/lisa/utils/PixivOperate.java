package ceui.lisa.utils;


import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;

import androidx.documentfile.provider.DocumentFile;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.blankj.utilcode.util.FileUtils;
import com.blankj.utilcode.util.UriUtils;
import com.blankj.utilcode.util.ZipUtils;
import com.waynejo.androidndkgif.GifEncoder;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.io.inputstream.ZipInputStream;
import net.lingala.zip4j.model.LocalFileHeader;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.VActivity;
import ceui.lisa.cache.Cache;
import ceui.lisa.core.Container;
import ceui.lisa.core.PageData;
import ceui.lisa.core.SAFile;
import ceui.lisa.core.TryCatchObserverImpl;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustHistoryEntity;
import ceui.lisa.database.SearchEntity;
import ceui.lisa.database.TagMuteEntity;
import ceui.lisa.download.FileCreator;
import ceui.lisa.fragments.FragmentLogin;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.FeedBack;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.GifResponse;
import ceui.lisa.models.IllustSearchResponse;
import ceui.lisa.models.NovelBean;
import ceui.lisa.models.NovelSearchResponse;
import ceui.lisa.models.NullResponse;
import ceui.lisa.models.TagsBean;
import ceui.lisa.models.UserModel;
import ceui.lisa.models.IllustsBean;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import retrofit2.Call;
import retrofit2.Callback;

import static ceui.lisa.activities.Shaft.sUserModel;
import static com.blankj.utilcode.util.StringUtils.getString;


public class PixivOperate {

    public static void refreshUserData(UserModel userModel, Callback<UserModel> callback) {
        Call<UserModel> call = Retro.getAccountApi().refreshToken(
                FragmentLogin.CLIENT_ID,
                FragmentLogin.CLIENT_SECRET,
                FragmentLogin.REFRESH_TOKEN,
                userModel.getResponse().getRefresh_token(),
                userModel.getResponse().getDevice_token(),
                Boolean.TRUE,
                Boolean.TRUE);
        call.enqueue(callback);
    }

    public static void postFollowUser(int userID, String followType) {
        Retro.getAppApi().postFollow(
                sUserModel.getResponse().getAccess_token(), userID, followType)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ErrorCtrl<NullResponse>() {

                    @Override
                    public void next(NullResponse nullResponse) {
                        Intent intent = new Intent(Params.LIKED_USER);
                        intent.putExtra(Params.ID, userID);
                        intent.putExtra(Params.IS_LIKED, true);
                        LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);

                        if (followType.equals(Params.TYPE_PUBLUC)) {
                            Common.showToast(getString(R.string.like_success_public));
                        } else {
                            Common.showToast(getString(R.string.like_success_private));
                        }
                    }
                });
    }

    public static void postUnFollowUser(int userID) {
        Retro.getAppApi().postUnFollow(
                sUserModel.getResponse().getAccess_token(), userID)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ErrorCtrl<NullResponse>() {
                    @Override
                    public void next(NullResponse nullResponse) {
                        Intent intent = new Intent(Params.LIKED_USER);
                        intent.putExtra(Params.ID, userID);
                        intent.putExtra(Params.IS_LIKED, false);
                        LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);

                        Common.showToast(getString(R.string.cancel_like));
                    }
                });
    }

    public static void postLike(IllustsBean illustsBean, String starType) {
        if (illustsBean == null) {
            return;
        }

        if (illustsBean.isIs_bookmarked()) { //已收藏
            illustsBean.setIs_bookmarked(false);
            Retro.getAppApi().postDislike(sUserModel.getResponse().getAccess_token(), illustsBean.getId())
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<NullResponse>() {
                        @Override
                        public void next(NullResponse nullResponse) {
                            Intent intent = new Intent(Params.LIKED_ILLUST);
                            intent.putExtra(Params.ID, illustsBean.getId());
                            intent.putExtra(Params.IS_LIKED, false);
                            LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);

                            Common.showToast(getString(R.string.cancel_like_illust));
                        }
                    });
        } else { //没有收藏
            illustsBean.setIs_bookmarked(true);
            Retro.getAppApi().postLike(sUserModel.getResponse().getAccess_token(), illustsBean.getId(), starType)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<NullResponse>() {
                        @Override
                        public void next(NullResponse nullResponse) {
                            Intent intent = new Intent(Params.LIKED_ILLUST);
                            intent.putExtra(Params.ID, illustsBean.getId());
                            intent.putExtra(Params.IS_LIKED, true);
                            LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);

                            if (Params.TYPE_PUBLUC.equals(starType)) {
                                Common.showToast(getString(R.string.like_novel_success_public));
                            } else {
                                Common.showToast(getString(R.string.like_novel_success_private));
                            }
                        }
                    });
        }
        PixivOperate.insertIllustViewHistory(illustsBean);
    }

    public static void postLikeNovel(NovelBean novelBean, UserModel userModel, String starType, View view) {
        if (novelBean == null) {
            return;
        }

        if (novelBean.isIs_bookmarked()) { //已收藏
            novelBean.setIs_bookmarked(false);
            Retro.getAppApi().postDislikeNovel(userModel.getResponse().getAccess_token(), novelBean.getId())
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<NullResponse>() {

                        @Override
                        public void next(NullResponse nullResponse) {
                            Intent intent = new Intent(Params.LIKED_NOVEL);
                            intent.putExtra(Params.ID, novelBean.getId());
                            intent.putExtra(Params.IS_LIKED, false);
                            LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);

                            if(view instanceof Button){
                                ((Button) view).setText("收藏");
                            }
                            Common.showToast(getString(R.string.cancel_like_illust));
                        }
                    });
        } else { //没有收藏
            novelBean.setIs_bookmarked(true);
            Retro.getAppApi().postLikeNovel(userModel.getResponse().getAccess_token(), novelBean.getId(), starType)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<NullResponse>() {
                        @Override
                        public void next(NullResponse nullResponse) {
                            Intent intent = new Intent(Params.LIKED_NOVEL);
                            intent.putExtra(Params.ID, novelBean.getId());
                            intent.putExtra(Params.IS_LIKED, true);
                            LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);

                            if(view instanceof Button){
                                ((Button) view).setText("取消收藏");
                            }
                            if (Params.TYPE_PUBLUC.equals(starType)) {
                                Common.showToast(getString(R.string.like_novel_success_public));
                            } else {
                                Common.showToast(getString(R.string.like_novel_success_private));
                            }
                        }
                    });
        }
    }

    public static void getIllustByID(UserModel userModel, int illustID, Context context) {
        Retro.getAppApi().getIllustByID(userModel.getResponse().getAccess_token(), illustID)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<IllustSearchResponse>() {
                    @Override
                    public void success(IllustSearchResponse illustSearchResponse) {
                        if (illustSearchResponse.getIllust() != null) {
                            final PageData pageData = new PageData(
                                    Collections.singletonList(illustSearchResponse.getIllust()));
                            Container.get().addPageToMap(pageData);

                            Intent intent = new Intent(context, VActivity.class);
                            intent.putExtra(Params.POSITION, 0);
                            intent.putExtra(Params.PAGE_UUID, pageData.getUUID());
                            context.startActivity(intent);
                        }
                    }
                });
    }

    public static void getIllustByID(UserModel userModel, int illustID, Context context,
                                     ceui.lisa.interfaces.Callback<Void> callback) {
        Retro.getAppApi().getIllustByID(userModel.getResponse().getAccess_token(), illustID)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<IllustSearchResponse>() {
                    @Override
                    public void success(IllustSearchResponse illustSearchResponse) {
                        if (illustSearchResponse.getIllust() != null) {
                            final PageData pageData = new PageData(
                                    Collections.singletonList(illustSearchResponse.getIllust()));
                            Container.get().addPageToMap(pageData);

                            Intent intent = new Intent(context, VActivity.class);
                            intent.putExtra(Params.POSITION, 0);
                            intent.putExtra(Params.PAGE_UUID, pageData.getUUID());
                            context.startActivity(intent);

                            if (callback != null) {
                                callback.doSomething(null);
                            }
                        }
                    }
                });
    }

    public static void getNovelByID(UserModel userModel, int novel, Context context,
                                     ceui.lisa.interfaces.Callback<Void> callback) {
        Retro.getAppApi().getNovelByID(userModel.getResponse().getAccess_token(), novel)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<NovelSearchResponse>() {
                    @Override
                    public void success(NovelSearchResponse novelSearchResponse) {
                        if (novelSearchResponse.getNovel() != null) {
                            Intent intent = new Intent(context, TemplateActivity.class);
                            intent.putExtra(Params.CONTENT, novelSearchResponse.getNovel());
                            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情");
                            intent.putExtra("hideStatusBar", true);
                            context.startActivity(intent);

                            if (callback != null) {
                                callback.doSomething(null);
                            }
                        } else {
                            Common.showToast("NovelSearchResponse 为空");
                        }
                    }
                });
    }

    public static void getGifInfo(IllustsBean illust, ErrorCtrl<GifResponse> errorCtrl) {
        Retro.getAppApi().getGifPackage(sUserModel.getResponse().getAccess_token(), illust.getId())
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(errorCtrl);
    }

    public static void muteTag(TagsBean tagsBean) {
        TagMuteEntity tagMuteEntity = new TagMuteEntity();
        String tagName = tagsBean.getName();
        tagMuteEntity.setId(tagName.hashCode());
        tagMuteEntity.setTagJson(Shaft.sGson.toJson(tagsBean));
        tagMuteEntity.setSearchTime(System.currentTimeMillis());
        AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().insertMuteTag(tagMuteEntity);
    }

    public static void muteTags(List<TagsBean> tagsBeans) {
        if (tagsBeans == null || tagsBeans.size() == 0) {
            return;
        }

        for (TagsBean tagsBean : tagsBeans) {
            muteTag(tagsBean);
        }
    }

    public static void unMuteTag(TagsBean tagsBean) {
        TagMuteEntity tagMuteEntity = new TagMuteEntity();
        String tagName = tagsBean.getName();
        tagMuteEntity.setId(tagName.hashCode());
        tagMuteEntity.setTagJson(Shaft.sGson.toJson(tagsBean));
        tagMuteEntity.setSearchTime(System.currentTimeMillis());
        AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().unMuteTag(tagMuteEntity);
        Common.showToast(Shaft.getContext().getString(R.string.string_135));
    }

    public static void insertIllustViewHistory(IllustsBean illust) {
        if (illust == null) {
            return;
        }

        if (illust.getId() > 0) {
            IllustHistoryEntity illustHistoryEntity = new IllustHistoryEntity();
            illustHistoryEntity.setType(0);
            illustHistoryEntity.setIllustID(illust.getId());
            illustHistoryEntity.setIllustJson(Shaft.sGson.toJson(illust));
            illustHistoryEntity.setTime(System.currentTimeMillis());
            Common.showLog("插入了 " + illustHistoryEntity.getIllustID() + " time " + illustHistoryEntity.getTime());
            AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insert(illustHistoryEntity);
        }
    }

    public static void insertNovelViewHistory(NovelBean novelBean) {
        if (novelBean == null) {
            return;
        }

        if (novelBean.getId() > 0) {
            IllustHistoryEntity illustHistoryEntity = new IllustHistoryEntity();
            illustHistoryEntity.setIllustID(novelBean.getId());
            illustHistoryEntity.setType(1);
            illustHistoryEntity.setIllustJson(Shaft.sGson.toJson(novelBean));
            illustHistoryEntity.setTime(System.currentTimeMillis());
            Common.showLog("插入了 " + illustHistoryEntity.getIllustID() + " time " + illustHistoryEntity.getTime());
            AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insert(illustHistoryEntity);
        }
    }

    public static void insertSearchHistory(String key, int searchType) {
        SearchEntity searchEntity = new SearchEntity();
        searchEntity.setKeyword(key);
        searchEntity.setSearchType(searchType);
        searchEntity.setSearchTime(System.currentTimeMillis());
        searchEntity.setId(searchEntity.getKeyword().hashCode() + searchEntity.getSearchType());
        Common.showLog("insertSearchHistory " + searchType + " " + searchEntity.getId());
        AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().insert(searchEntity);
    }

    //筛选作品，只留下未收藏的作品
    public static List<IllustsBean> getListWithoutBooked(ListIllust response) {
        List<IllustsBean> result = new ArrayList<>();
        if (response == null) {
            return result;
        }

        if (response.getList() == null || response.getList().size() == 0) {
            return result;
        }

        for (IllustsBean illustsBean : response.getList()) {
            if (!illustsBean.isIs_bookmarked()) {
                result.add(illustsBean);
            }
        }

        return result;
    }

    public static void justUnzipFile(File fromZipFile, File toFolder) {
        try {
            ZipUtils.unzipFile(fromZipFile, toFolder);
            Common.showLog("justUnzipFile 解压成功");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void encodeGif(Context context, File parentFile, IllustsBean illustsBean) {
        try {
            Common.showLog("encodeGif 开始");
            Observable.create(new ObservableOnSubscribe<String>() {
                @Override
                public void subscribe(ObservableEmitter<String> emitter) throws Exception {
                    Common.showLog("encodeGif 开始生成gif图");
                    final File[] listfile = parentFile.listFiles();

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

                    File gifFile = SAFile.createZipResultFile(context, FileCreator.createGifFile(illustsBean).getName());
                    Common.showLog("gifFile " + gifFile.getPath());

                    GifEncoder gifEncoder = new GifEncoder();
                    gifEncoder.init(illustsBean.getWidth(), illustsBean.getHeight(), gifFile.getPath(),
                            GifEncoder.EncodingType.ENCODING_TYPE_NORMAL_LOW_MEMORY);

                    GifResponse gifResponse = Cache.get().getModel(Params.ILLUST_ID + "_" + illustsBean.getId(), GifResponse.class);
                    int delayMs = 60;
                    if (gifResponse != null) {
                        delayMs = gifResponse.getDelay();
                        Common.showLog("GifResponse " + gifResponse.toString());
                    } else {
                        Common.showLog("GifResponse null ");
                    }

                    Common.showLog("allFiles size " + allFiles.size());


                    for (File singleImage : allFiles) {
                        Common.showLog("编码中 00 " + allFiles.size());
                        gifEncoder.encodeFrame(BitmapFactory.decodeFile(singleImage.getPath()), delayMs);
                    }

                    gifEncoder.close();

                    Common.showLog("gifFile gifFile " + FileUtils.getSize(gifFile));

                    Intent intent = new Intent(Params.PLAY_GIF);
                    intent.putExtra(Params.ID, illustsBean.getId());
                    LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);
                }
            }).subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new TryCatchObserverImpl<>());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void unzipAndePlay(Context context, IllustsBean illustsBean) {
        try {
            File fromZip = SAFile.createZipFile(context,
                    FileCreator.createGifZipFile(illustsBean).getName());
            File toFolder = SAFile.createCacheUnzipFolder(context, illustsBean);
            justUnzipFile(fromZip, toFolder);
            encodeGif(context, toFolder, illustsBean);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
