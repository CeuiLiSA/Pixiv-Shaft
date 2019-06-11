package ceui.lisa.download;

import android.content.Intent;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.liulishuo.okdownload.DownloadTask;
import com.liulishuo.okdownload.core.cause.EndCause;
import com.liulishuo.okdownload.core.listener.DownloadListener2;

import java.io.File;

import ceui.lisa.activities.Shaft;
import ceui.lisa.response.IllustsBean;

public class IllustDownload {

    private static final String MAP_KEY = "Referer";
    private static final String IMAGE_REFERER = "https://app-api.pixiv.net/";

    public static void downloadIllust(IllustsBean illustsBean){
        if(illustsBean == null){
            return;
        }

        if(illustsBean.getPage_count() != 1){
            return;
        }


        File file = FileCreator.createIllustFile(illustsBean);
        DownloadTask.Builder builder = new DownloadTask.Builder(illustsBean.getMeta_single_page().getOriginal_image_url(),
                file.getParentFile())
                .setFilename(file.getName())
                // the minimal interval millisecond for callback progress
                .setMinIntervalMillisCallbackProcess(30)
                // do re-download even if the task has already been completed in the past.
                .setPassIfAlreadyCompleted(false);
        builder.addHeader(MAP_KEY, IMAGE_REFERER);
        DownloadTask task = builder.build();

        task.enqueue(new DownloadListener2() {
            @Override
            public void taskStart(@NonNull DownloadTask downloadTask) {
                TaskQueue.get().addTask(task);
            }

            @Override
            public void taskEnd(@NonNull DownloadTask downloadTask, @NonNull EndCause cause, @Nullable Exception realCause) {
                Shaft.getContext().sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)));
                TaskQueue.get().removeTask(task);
            }
        });
    }


    public static void downloadAllIllust(IllustsBean illustsBean){
        if(illustsBean == null){
            return;
        }

        if(illustsBean.getPage_count() <= 1){
            downloadIllust(illustsBean);
            return;
        }


        DownloadTask[] tasks = new DownloadTask[illustsBean.getPage_count()];

        for (int i = 0; i < illustsBean.getPage_count(); i++) {
            File file = FileCreator.createIllustFile(illustsBean, i);
            DownloadTask.Builder builder = new DownloadTask.Builder(illustsBean.getMeta_pages().get(i).getImage_urls().getOriginal(),
                    file.getParentFile())
                    .setFilename(file.getName())
                    // the minimal interval millisecond for callback progress
                    .setMinIntervalMillisCallbackProcess(30)
                    // do re-download even if the task has already been completed in the past.
                    .setPassIfAlreadyCompleted(false);
            builder.addHeader(MAP_KEY, IMAGE_REFERER);
            tasks[i] = builder.build();
            TaskQueue.get().addTask(tasks[i]);
        }

        DownloadTask.enqueue(tasks, new DownloadListener2() {
            @Override
            public void taskStart(@NonNull DownloadTask task) {

            }

            @Override
            public void taskEnd(@NonNull DownloadTask task, @NonNull EndCause cause, @Nullable Exception realCause) {
                TaskQueue.get().removeTask(task);
                new SingleMediaScanner(Shaft.getContext(), task.getFile(), () -> {
                });
            }
        });
    }
}
