package ceui.lisa.download;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.cache.Cache;
import ceui.lisa.core.DownloadItem;
import ceui.lisa.core.Manager;
import ceui.lisa.feature.HostManager;
import ceui.lisa.file.LegacyFile;
import ceui.lisa.file.OutPut;
import ceui.lisa.file.SAFile;
import ceui.lisa.helper.SAFactory;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.interfaces.FeedBack;
import ceui.lisa.models.GifResponse;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.ImageUrlsBean;
import ceui.lisa.models.NovelBean;
import ceui.lisa.models.NovelDetail;
import ceui.lisa.models.NovelSeriesItem;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;

import static android.provider.DocumentsContract.EXTRA_INITIAL_URI;

public class IllustDownload {

    public static void downloadIllust(IllustsBean illust, BaseActivity<?> activity) {
        check(activity, () -> {
            if (illust.getPage_count() == 1) {
                DownloadItem item = new DownloadItem(illust, 0);
                item.setUrl(getUrl(illust, 0));
                item.setShowUrl(getShowUrl(illust, 0));
                Common.showToast(1 + "个任务已经加入下载队列");
                Manager.get().addTask(item, activity);
            }
        });
    }

    public static void downloadIllust(IllustsBean illust, String imageResolution, BaseActivity<?> activity) {
        check(activity, () -> {
            if (illust.getPage_count() == 1) {
                DownloadItem item = new DownloadItem(illust, 0);
                item.setUrl(getUrl(illust, 0, imageResolution));
                item.setShowUrl(getShowUrl(illust, 0));
                Common.showToast(1 + "个任务已经加入下载队列");
                Manager.get().addTask(item, activity);
            }
        });
    }

    public static void downloadIllust(IllustsBean illust, Context context) {
        if (illust.getPage_count() == 1) {
            DownloadItem item = new DownloadItem(illust, 0);
            item.setUrl(getUrl(illust, 0));
            item.setShowUrl(getShowUrl(illust, 0));
            Common.showToast(1 + "个任务已经加入下载队列");
            Manager.get().addTask(item, context);
        }
    }

    public static void downloadIllust(IllustsBean illust, int index, BaseActivity<?> activity) {
        check(activity, () -> {
            if (illust.getPage_count() == 1) {
                downloadIllust(illust, activity);
            } else {
                DownloadItem item = new DownloadItem(illust, index);
                item.setUrl(getUrl(illust, index));
                item.setShowUrl(getShowUrl(illust, index));
                Common.showToast(1 + "个任务已经加入下载队列");
                Manager.get().addTask(item, activity);
            }
        });
    }


    public static void downloadAllIllust(IllustsBean illust, BaseActivity<?> activity) {
        check(activity, () -> {
            if (illust.getPage_count() == 1) {
                downloadIllust(illust, activity);
            } else {
                List<DownloadItem> tempList = new ArrayList<>();
                for (int i = 0; i < illust.getPage_count(); i++) {
                    DownloadItem item = new DownloadItem(illust, i);
                    item.setUrl(getUrl(illust, i));
                    item.setShowUrl(getShowUrl(illust, i));
                    tempList.add(item);
                }
                Common.showToast(tempList.size() + "个任务已经加入下载队列");
                Manager.get().addTasks(tempList, activity);
            }
        });
    }

    public static void downloadAllIllust(IllustsBean illust, String imageResolution, BaseActivity<?> activity) {
        check(activity, () -> {
            if (illust.getPage_count() == 1) {
                downloadIllust(illust, activity);
            } else {
                List<DownloadItem> tempList = new ArrayList<>();
                for (int i = 0; i < illust.getPage_count(); i++) {
                    DownloadItem item = new DownloadItem(illust, i);
                    item.setUrl(getUrl(illust, i, imageResolution));
                    item.setShowUrl(getShowUrl(illust, i));
                    tempList.add(item);
                }
                Common.showToast(tempList.size() + "个任务已经加入下载队列");
                Manager.get().addTasks(tempList, activity);
            }
        });
    }

    public static void downloadAllIllust(IllustsBean illust, Context context) {
        if (illust.getPage_count() == 1) {
            downloadIllust(illust, context);
        } else {
            List<DownloadItem> tempList = new ArrayList<>();
            for (int i = 0; i < illust.getPage_count(); i++) {
                DownloadItem item = new DownloadItem(illust, i);
                item.setUrl(getUrl(illust, i));
                item.setShowUrl(getShowUrl(illust, i));
                tempList.add(item);
            }
            Common.showToast(tempList.size() + "个任务已经加入下载队列");
            Manager.get().addTasks(tempList, context);
        }
    }


    public static void downloadAllIllust(List<IllustsBean> beans, BaseActivity<?> activity) {
        check(activity, () -> {
            List<DownloadItem> tempList = new ArrayList<>();
            int taskCount = 0;
            for (int i = 0; i < beans.size(); i++) {
                if (beans.get(i).isChecked()) {
                    final IllustsBean illust = beans.get(i);

                    if(illust.isGif()){
                        downloadGif(illust, activity);
                        taskCount++;
                    } else if (illust.getPage_count() == 1) {
                        DownloadItem item = new DownloadItem(illust, 0);
                        item.setUrl(getUrl(illust, 0));
                        item.setShowUrl(getShowUrl(illust, 0));
                        tempList.add(item);
                        taskCount++;
                    } else {
                        for (int j = 0; j < illust.getPage_count(); j++) {
                            DownloadItem item = new DownloadItem(illust, j);
                            item.setUrl(getUrl(illust, j));
                            item.setShowUrl(getShowUrl(illust, j));
                            tempList.add(item);
                            taskCount++;
                        }
                    }
                }
            }
            Common.showToast(taskCount + "个任务已经加入下载队列");
            Manager.get().addTasks(tempList, activity);
        });
    }

    public static void downloadGif(GifResponse response, IllustsBean illust, BaseActivity<?> activity) {
        downloadGif(response, illust, false, activity);
    }

    public static void downloadGif(GifResponse response, IllustsBean illust, boolean autoSave, BaseActivity<?> activity) {
        DownloadItem item = new DownloadItem(illust, 0);
        item.setAutoSave(autoSave);
        item.setUrl(HostManager.get().replaceUrl(response.getUgoira_metadata().getZip_urls().getMedium()));
        item.setShowUrl(HostManager.get().replaceUrl(illust.getImage_urls().getMedium()));
        Manager.get().addTask(item, activity);
    }

    public static void downloadGif(IllustsBean illustsBean, BaseActivity<?> activity){
        if(!illustsBean.isGif()){
            return;
        }
        PixivOperate.getGifInfo(illustsBean, new ErrorCtrl<GifResponse>() {
            @Override
            public void next(GifResponse gifResponse) {
                Cache.get().saveModel(Params.ILLUST_ID + "_" + illustsBean.getId(), gifResponse);
                downloadGif(gifResponse, illustsBean, true, (BaseActivity<?>) activity);
            }
        });
    }

    public static void downloadNovel(BaseActivity<?> activity, NovelSeriesItem novelSeriesItem, String content, Callback<Uri> targetCallback) {
        String displayName = FileCreator.deleteSpecialWords("NovelSeries_" + novelSeriesItem.getId() + "_Chapter_1~" + novelSeriesItem.getContent_count() + "_" + novelSeriesItem.getTitle() + ".txt");
        downloadNovel(activity, displayName, content, targetCallback);
    }

    public static void downloadNovel(BaseActivity<?> activity, NovelBean novelBean, NovelDetail novelDetail, Callback<Uri> targetCallback) {
        String displayName = FileCreator.deleteSpecialWords("Novel_" + novelBean.getId() + "_" + novelBean.getTitle() + ".txt");
        String content = novelDetail.getNovel_text();
        downloadNovel(activity, displayName, content, targetCallback);
    }

    public static void downloadFile(BaseActivity<?> activity, String displayName, String content, Callback<Uri> targetCallback) {
        check(activity, new FeedBack() {
            @Override
            public void doSomething() {
                File textFile = new LegacyFile().textFile(activity, displayName);
                try {
                    OutputStream outStream = new FileOutputStream(textFile);
                    outStream.write(content.getBytes());
                    outStream.close();
                    Common.showLog("downloadFile displayName " + displayName);
                    OutPut.outPutFile(activity, textFile, displayName);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                Uri fileURI = FileProvider.getUriForFile(activity,
                        activity.getApplicationContext().getPackageName() + ".provider", textFile);
                if (targetCallback != null) {
                    targetCallback.doSomething(fileURI);
                }
            }
        });
    }

    public static void downloadNovel(BaseActivity<?> activity, String displayName, String content, Callback<Uri> targetCallback) {
        check(activity, new FeedBack() {
            @Override
            public void doSomething() {
                File textFile = new LegacyFile().textFile(activity, displayName);
                try {
                    OutputStream outStream = new FileOutputStream(textFile);
                    outStream.write(content.getBytes());
                    outStream.close();
                    Common.showLog("downloadNovel displayName " + displayName);
                    OutPut.outPutNovel(activity, textFile, displayName);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                Uri fileURI = FileProvider.getUriForFile(activity,
                        activity.getApplicationContext().getPackageName() + ".provider", textFile);
                if (targetCallback != null) {
                    targetCallback.doSomething(fileURI);
                }
            }
        });
    }

    public static void downloadBackupFile(BaseActivity<?> activity, String displayName, String content, Callback<Uri> targetCallback){
        check(activity, new FeedBack() {
            @Override
            public void doSomething() {
                File textFile = new LegacyFile().textFile(activity, displayName);
                try {
                    OutputStream outStream = new FileOutputStream(textFile);
                    outStream.write(content.getBytes());
                    outStream.close();
                    Common.showLog("downloadBackupFile displayName " + displayName);
                    OutPut.outPutBackupFile(activity, textFile, displayName);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                Uri fileURI = FileProvider.getUriForFile(activity,
                        activity.getApplicationContext().getPackageName() + ".provider", textFile);
                if (targetCallback != null) {
                    targetCallback.doSomething(fileURI);
                }
            }
        });
    }

    public static String getUrl(IllustsBean illust, int index) {
        return getUrl(illust, index, Params.IMAGE_RESOLUTION_ORIGINAL);
    }

    public static String getUrl(IllustsBean illust, int index, String imageResolution) {
        return HostManager.get().replaceUrl(getImageUrlByResolution(illust, index, imageResolution));
    }

    private static String getImageUrlByResolution(IllustsBean illust, int index, String imageResolution) {
        ImageUrlsBean imageUrlsBean = getImageUrlsBean(illust, index, imageResolution);
        switch (imageResolution) {
            case Params.IMAGE_RESOLUTION_ORIGINAL:
                return imageUrlsBean.getOriginal();
            case Params.IMAGE_RESOLUTION_LARGE:
                return imageUrlsBean.getLarge();
            case Params.IMAGE_RESOLUTION_MEDIUM:
                return imageUrlsBean.getMedium();
            case Params.IMAGE_RESOLUTION_SQUARE_MEDIUM:
                return imageUrlsBean.getSquare_medium();
            default:
                return imageUrlsBean.getMaxImage();
        }
    }

    private static ImageUrlsBean getImageUrlsBean(IllustsBean illust, int index, String imageResolution) {
        if (illust.getPage_count() == 1) {
            return imageResolution.equals(Params.IMAGE_RESOLUTION_ORIGINAL) ? illust.getMeta_single_page() : illust.getImage_urls();
        } else {
            return illust.getMeta_pages().get(index).getImage_urls();
        }
    }

    public static String getShowUrl(IllustsBean illust, int index) {
        if (illust.getPage_count() == 1) {
            return illust.getImage_urls().getMedium();
        } else {
            return illust.getMeta_pages().get(index).getImage_urls().getMedium();
        }
    }

    public static void check(BaseActivity<?> activity, FeedBack feedBack) {
        if (Shaft.sSettings.getDownloadWay() == 1) {
            if (TextUtils.isEmpty(Shaft.sSettings.getRootPathUri())) {
                activity.setFeedBack(feedBack);
                new QMUIDialog.MessageDialogBuilder(activity)
                        .setTitle(activity.getResources().getString(R.string.string_143))
                        .setMessage(activity.getResources().getString(R.string.string_313))
                        .setSkinManager(QMUISkinManager.defaultInstance(activity))
                        .addAction(0, activity.getResources().getString(R.string.string_142),
                                QMUIDialogAction.ACTION_PROP_NEGATIVE,
                                (dialog, index) -> dialog.dismiss())
                        .addAction(0, activity.getResources().getString(R.string.string_312),
                                (dialog, index) -> {
                                    try {
                                        new Thread(new Runnable() {
                                            @Override
                                            public void run() {
                                                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                                                if (!TextUtils.isEmpty(Shaft.sSettings.getRootPathUri()) &&
                                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                    Uri start = Uri.parse(Shaft.sSettings.getRootPathUri());
                                                    intent.putExtra(EXTRA_INITIAL_URI, start);
                                                }
                                                activity.startActivityForResult(intent, BaseActivity.ASK_URI);
                                            }
                                        }).start();
                                    } catch (Exception e) {
                                        Common.showToast(e.toString());
                                        e.printStackTrace();
                                    }
                                    dialog.dismiss();
                                })
                        .show();
            } else {
                DocumentFile root = SAFile.rootFolder(activity);
                if (root == null || !root.exists() || !root.isDirectory()) {
                    activity.setFeedBack(feedBack);
                    new QMUIDialog.MessageDialogBuilder(activity)
                            .setTitle(activity.getResources().getString(R.string.string_143))
                            .setMessage(activity.getResources().getString(R.string.string_365))
                            .setSkinManager(QMUISkinManager.defaultInstance(activity))
                            .addAction(0, activity.getResources().getString(R.string.string_142),
                                    QMUIDialogAction.ACTION_PROP_NEGATIVE,
                                    (dialog, index) -> dialog.dismiss())
                            .addAction(0, activity.getResources().getString(R.string.string_366),
                                    (dialog, index) -> {
                                        try {
                                            new Thread(new Runnable() {
                                                @Override
                                                public void run() {
                                                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                                                    if (!TextUtils.isEmpty(Shaft.sSettings.getRootPathUri()) &&
                                                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                        Uri start = Uri.parse(Shaft.sSettings.getRootPathUri());
                                                        intent.putExtra(EXTRA_INITIAL_URI, start);
                                                    }
                                                    activity.startActivityForResult(intent, BaseActivity.ASK_URI);
                                                }
                                            }).start();
                                        } catch (Exception e) {
                                            Common.showToast(e.toString());
                                            e.printStackTrace();
                                        }
                                        dialog.dismiss();
                                    })
                            .show();
                } else {
                    if (feedBack != null) {
                        try {
                            feedBack.doSomething();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        } else {
            if (feedBack != null) {
                try {
                    feedBack.doSomething();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
