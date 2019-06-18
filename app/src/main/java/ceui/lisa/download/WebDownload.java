package ceui.lisa.download;

import com.liulishuo.okdownload.DownloadTask;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

import ceui.lisa.utils.Common;

public class WebDownload {

    public static void download(String url){
        File file = null;
        try {
            file = FileCreator.createWebFile(new File(URLDecoder.decode(url,"utf-8")).getName());
            DownloadTask task = new DownloadTask.Builder(url, file.getParent(), file.getName())
                    .setMinIntervalMillisCallbackProcess(30)
                    .setPassIfAlreadyCompleted(false)
                    .build();
            task.enqueue(new WebDownloadListener());
            Common.showLog(String.format("downloading %s",url));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }
}
