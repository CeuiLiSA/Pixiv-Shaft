package ceui.lisa.download;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.widget.ProgressBar;

import androidx.fragment.app.FragmentActivity;

import com.liulishuo.okdownload.DownloadTask;
import com.liulishuo.okdownload.core.dispatcher.DownloadDispatcher;
import com.tbruyelle.rxpermissions2.RxPermissions;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.database.IllustTask;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.models.GifResponse;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import io.reactivex.disposables.Disposable;

public class IllustDownload {

    public static final String MAP_KEY = "Referer";
    public static final String IMAGE_REFERER = "https://app-api.pixiv.net/";

    public static void downloadIllust(Activity activity, IllustsBean illustsBean) {
        checkP(activity, new Callback() {
            @Override
            public void doSomething(Object t) {
                if (illustsBean == null) {
                    Common.showToast(Shaft.getContext().getString(R.string.cannot_download));
                    return;
                }

                if (illustsBean.getPage_count() != 1) {
                    Common.showToast(Shaft.getContext().getString(R.string.cannot_download));
                    return;
                }

                File file = FileCreator.createIllustFile(illustsBean);
                if (file.exists()) {
                    Common.showToast(Shaft.getContext().getString(R.string.image_alredy_exist));
                    return;
                }


                Common.showLog("Task url " + illustsBean.getMeta_single_page().getOriginal_image_url());
                DownloadTask.Builder builder = new DownloadTask.Builder(illustsBean.getMeta_single_page().getOriginal_image_url(),
                        file.getParentFile())
                        .setFilename(file.getName())
                        .setMinIntervalMillisCallbackProcess(30)
                        .setPassIfAlreadyCompleted(false);
                builder.addHeader(MAP_KEY, IMAGE_REFERER);
                DownloadTask task = builder.build();
                IllustTask illustTask = new IllustTask();
                illustTask.setIllustsBean(illustsBean);
                illustTask.setDownloadTask(task);
                TaskQueue.get().addTask(illustTask);
                task.enqueue(new QueueListener());
                Common.showToast(Shaft.getContext().getString(R.string.one_item_added));
            }
        });
    }


    public static void downloadIllust(Activity activity, IllustsBean illustsBean, int index) {
        checkP(activity, new Callback() {
            @Override
            public void doSomething(Object t) {
                if (illustsBean == null) {
                    Common.showToast(Shaft.getContext().getString(R.string.cannot_download));
                    return;
                }

                File file = FileCreator.createIllustFile(illustsBean, index);
                if (file.exists()) {
                    Common.showToast(Shaft.getContext().getString(R.string.image_alredy_exist));
                    return;
                }

                if (illustsBean.getPage_count() == 1) {
                    downloadIllust(activity, illustsBean);
                } else {
                    DownloadTask.Builder builder = new DownloadTask.Builder(
                            illustsBean.getMeta_pages().get(index).getImage_urls().getOriginal(),
                            file.getParentFile())
                            .setFilename(file.getName())
                            .setMinIntervalMillisCallbackProcess(30)
                            .setPassIfAlreadyCompleted(false);
                    builder.addHeader(MAP_KEY, IMAGE_REFERER);
                    DownloadTask task = builder.build();
                    IllustTask illustTask = new IllustTask();
                    illustTask.setIllustsBean(illustsBean);
                    illustTask.setDownloadTask(task);
                    TaskQueue.get().addTask(illustTask);
                    task.enqueue(new QueueListener());
                    Common.showToast(Shaft.getContext().getString(R.string.one_item_added));
                }
            }
        });
    }


    public static void downloadAllIllust(Activity activity, IllustsBean illustsBean) {
        checkP(activity, new Callback() {
            @Override
            public void doSomething(Object t) {
                if (illustsBean == null) {
                    Common.showToast(Shaft.getContext().getString(R.string.cannot_download));
                    return;
                }

                if (illustsBean.getPage_count() <= 1) {
                    downloadIllust(activity, illustsBean);
                    return;
                }
                DownloadDispatcher.setMaxParallelRunningCount(1);


                List<DownloadTask> tempList = new ArrayList<>();

                for (int i = 0; i < illustsBean.getPage_count(); i++) {
                    File file = FileCreator.createIllustFile(illustsBean, i);
                    if (!file.exists()) {
                        DownloadTask.Builder builder = new DownloadTask.Builder(illustsBean.getMeta_pages().get(i).getImage_urls().getOriginal(),
                                file.getParentFile())
                                .setFilename(file.getName())
                                .setMinIntervalMillisCallbackProcess(30)
                                .setPassIfAlreadyCompleted(false);
                        builder.addHeader(MAP_KEY, IMAGE_REFERER);
                        final DownloadTask task = builder.build();
                        tempList.add(task);

                        IllustTask illustTask = new IllustTask();
                        illustTask.setIllustsBean(illustsBean);
                        illustTask.setDownloadTask(task);
                        TaskQueue.get().addTask(illustTask);
                    }
                }

                DownloadTask[] taskArray = new DownloadTask[tempList.size()];
                DownloadTask.enqueue(tempList.toArray(taskArray), new QueueListener());
                Common.showToast(tempList.size() + Shaft.getContext().getString(R.string.has_been_added));
            }
        });
    }


    public static void downloadAllIllust(Activity activity, List<IllustsBean> beans) {
        checkP(activity, new Callback() {
            @Override
            public void doSomething(Object t) {
                if (beans == null) {
                    Common.showToast(Shaft.getContext().getString(R.string.cannot_download));
                    return;
                }

                if (beans.size() <= 0) {
                    Common.showToast(Shaft.getContext().getString(R.string.cannot_download));
                    return;
                }

                if (beans.size() == 1) {
                    downloadAllIllust(activity, beans.get(0));
                    return;
                }

                DownloadDispatcher.setMaxParallelRunningCount(1);


                List<DownloadTask> tempList = new ArrayList<>();

                for (int i = 0; i < beans.size(); i++) {
                    if (beans.get(i).isChecked()) {
                        final IllustsBean currentIllust = beans.get(i);

                        if (currentIllust.getPage_count() == 1) {

                            File file = FileCreator.createIllustFile(currentIllust);
                            if (!file.exists()) {
                                DownloadTask.Builder builder = new DownloadTask.Builder(
                                        currentIllust.getMeta_single_page().getOriginal_image_url(),
                                        file.getParentFile())
                                        .setFilename(file.getName())
                                        .setMinIntervalMillisCallbackProcess(30)
                                        .setPassIfAlreadyCompleted(false);
                                builder.addHeader(MAP_KEY, IMAGE_REFERER);
                                final DownloadTask task = builder.build();
                                tempList.add(task);

                                IllustTask illustTask = new IllustTask();
                                illustTask.setIllustsBean(currentIllust);
                                illustTask.setDownloadTask(task);
                                TaskQueue.get().addTask(illustTask);
                            }
                        } else {
                            for (int j = 0; j < currentIllust.getPage_count(); j++) {

                                File file = FileCreator.createIllustFile(currentIllust, j);
                                if (!file.exists()) {
                                    DownloadTask.Builder builder = new DownloadTask.Builder(
                                            currentIllust.getMeta_pages().get(j).getImage_urls().getOriginal(),
                                            file.getParentFile())
                                            .setFilename(file.getName())
                                            .setMinIntervalMillisCallbackProcess(30)
                                            .setPassIfAlreadyCompleted(false);
                                    builder.addHeader(MAP_KEY, IMAGE_REFERER);
                                    final DownloadTask task = builder.build();
                                    tempList.add(task);

                                    IllustTask illustTask = new IllustTask();
                                    illustTask.setIllustsBean(currentIllust);
                                    illustTask.setDownloadTask(task);
                                    TaskQueue.get().addTask(illustTask);
                                }
                            }
                        }
                    }
                }

                if (tempList.size() == 0) {
                    return;
                }

                DownloadTask[] taskArray = new DownloadTask[tempList.size()];
                DownloadTask.enqueue(tempList.toArray(taskArray), new QueueListener());
                Common.showToast(tempList.size() + Shaft.getContext().getString(R.string.has_been_added));
            }
        });
    }

    public static void downloadGif(Activity activity, GifResponse response, IllustsBean allIllust, GifListener gifListener) {
        checkP(activity, new Callback() {
            @Override
            public void doSomething(Object t) {
                File file = FileCreator.createGifZipFile(allIllust);
                DownloadTask.Builder builder = new DownloadTask.Builder(
                        response.getUgoira_metadata().getZip_urls().getMedium(),
                        file.getParentFile())
                        .setFilename(file.getName())
                        .setMinIntervalMillisCallbackProcess(30)
                        .setPassIfAlreadyCompleted(true);
                builder.addHeader(MAP_KEY, IMAGE_REFERER);
                DownloadTask task = builder.build();

                IllustTask illustTask = new IllustTask();
                illustTask.setDownloadTask(task);
                illustTask.setIllustsBean(allIllust);
                GifQueue.get().addTask(illustTask);
                task.enqueue(gifListener);
                Common.showToast("图组ZIP已加入下载队列");
            }
        });
    }

    private static void checkP(Activity mActivity, Callback callback) {
        final RxPermissions rxPermissions = new RxPermissions((FragmentActivity) mActivity);
        Disposable disposable = rxPermissions
                .requestEachCombined(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .subscribe(permission -> {
                    if (permission.granted) {
                        callback.doSomething(null);
                    } else {
                        Common.showToast(mActivity.getString(R.string.access_denied));
                    }
                });
    }
}
