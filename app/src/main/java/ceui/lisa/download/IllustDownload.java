package ceui.lisa.download;

import com.liulishuo.okdownload.DownloadTask;

import java.io.File;

import ceui.lisa.database.IllustTask;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.utils.Common;

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
                .setMinIntervalMillisCallbackProcess(30)
                .setPassIfAlreadyCompleted(false);
        builder.addHeader(MAP_KEY, IMAGE_REFERER);
        DownloadTask task = builder.build();
        IllustTask illustTask = new IllustTask();
        illustTask.setIllustsBean(illustsBean);
        illustTask.setDownloadTask(task);
        TaskQueue.get().addTask(illustTask);
        task.enqueue(new QueueListener());
        Common.showToast("已加入下载队列");
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
                    .setMinIntervalMillisCallbackProcess(30)
                    .setPassIfAlreadyCompleted(false);
            builder.addHeader(MAP_KEY, IMAGE_REFERER);
            tasks[i] = builder.build();
            IllustTask illustTask = new IllustTask();
            illustTask.setIllustsBean(illustsBean);
            illustTask.setDownloadTask(tasks[i]);
            TaskQueue.get().addTask(illustTask);
        }

        DownloadTask.enqueue(tasks, new QueueListener());
        Common.showToast("已加入下载队列");
    }
}
