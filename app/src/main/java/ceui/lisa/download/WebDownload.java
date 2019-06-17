package ceui.lisa.download;

import com.liulishuo.okdownload.DownloadTask;

import java.io.File;

import ceui.lisa.utils.Common;

public class WebDownload {

    public static void download(String url){
        File file = FileCreator.createWebFile(new File(url).getName());
        DownloadTask task = new DownloadTask.Builder(url, file.getParent(), file.getName())
                .setMinIntervalMillisCallbackProcess(30)
                .setPassIfAlreadyCompleted(false)
                .build();
        task.enqueue(new WebDownloadListener());
        Common.showLog(String.format("downloading %s",url));
    }
}
