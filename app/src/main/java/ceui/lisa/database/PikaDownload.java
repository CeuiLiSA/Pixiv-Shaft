package ceui.lisa.database;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;

import com.liulishuo.okdownload.DownloadListener;
import com.liulishuo.okdownload.DownloadTask;
import com.liulishuo.okdownload.core.cause.EndCause;
import com.liulishuo.okdownload.core.cause.ResumeFailedCause;
import com.liulishuo.okdownload.core.listener.DownloadListener1;
import com.liulishuo.okdownload.core.listener.DownloadListener2;
import com.liulishuo.okdownload.core.listener.assist.Listener1Assist;
import com.tbruyelle.rxpermissions2.RxPermissions;

import java.io.File;

import ceui.lisa.activities.PikaActivity;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;

import static ceui.lisa.activities.PikaActivity.FILE_NAME;
import static ceui.lisa.activities.PikaActivity.FILE_PATH;

public class PikaDownload {

    private static final String MAP_KEY = "Referer";
    private static final String IMAGE_REFERER = "https://app-api.pixiv.net/";


    public static void downloadPikaImage(IllustsBean illustsBean, Context context){
        final RxPermissions rxPermissions = new RxPermissions((FragmentActivity) context);
        rxPermissions
                .requestEachCombined(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .subscribe(permission -> { // will emit 1 Permission object
                    if (permission.granted) {
                        String url = illustsBean.getMeta_single_page().getOriginal_image_url();
                        DownloadTask.Builder builder = new DownloadTask.Builder(url, new File(FILE_PATH))
                                .setFilename("pika_image_" + illustsBean.getId() + ".png")
                                .setMinIntervalMillisCallbackProcess(30)
                                .setPassIfAlreadyCompleted(true);
                        builder.addHeader(MAP_KEY, IMAGE_REFERER);
                        DownloadTask task = builder.build();
                        Common.showToast("开始下载");
                        task.enqueue(new DownloadListener1() {
                            @Override
                            public void taskStart(@NonNull DownloadTask task, @NonNull Listener1Assist.Listener1Model model) {

                            }

                            @Override
                            public void retry(@NonNull DownloadTask task, @NonNull ResumeFailedCause cause) {

                            }

                            @Override
                            public void connected(@NonNull DownloadTask task, int blockCount, long currentOffset, long totalLength) {

                            }

                            @Override
                            public void progress(@NonNull DownloadTask task, long currentOffset, long totalLength) {
                                Common.showLog("currentOffset " + currentOffset + " / " + totalLength);
                            }

                            @Override
                            public void taskEnd(@NonNull DownloadTask task, @NonNull EndCause cause, @Nullable Exception realCause, @NonNull Listener1Assist.Listener1Model model) {
                                Local.setPikaImageFile("pika_image_" + illustsBean.getId() + ".png");
                                Common.showToast("下载完成");
                            }
                        });
                    } else {
                        // At least one denied permission with ask never again
                        // Need to go to the settings
                        Common.showToast("请给与足够的权限");
                    }
                });
    }
}
