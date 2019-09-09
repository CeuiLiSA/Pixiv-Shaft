package ceui.lisa.download;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liulishuo.okdownload.DownloadTask;
import com.liulishuo.okdownload.core.cause.EndCause;
import com.liulishuo.okdownload.core.cause.ResumeFailedCause;
import com.liulishuo.okdownload.core.listener.DownloadListener1;
import com.liulishuo.okdownload.core.listener.assist.Listener1Assist;

import ceui.lisa.utils.Common;

public class WebDownloadListener extends DownloadListener1 {
    @Override
    public void taskStart(@NonNull DownloadTask task, @NonNull Listener1Assist.Listener1Model model) {
        Common.showToast(String.format("%s started",task.getFilename()));
    }

    @Override
    public void retry(@NonNull DownloadTask task, @NonNull ResumeFailedCause cause) {

    }

    @Override
    public void connected(@NonNull DownloadTask task, int blockCount, long currentOffset, long totalLength) {

    }

    @Override
    public void progress(@NonNull DownloadTask task, long currentOffset, long totalLength) {
//        TODO Notification
    }

    @Override
    public void taskEnd(@NonNull DownloadTask task, @NonNull EndCause cause, @Nullable Exception realCause, @NonNull Listener1Assist.Listener1Model model) {
        Common.showToast(String.format("%s done",task.getFilename()));
    }
}
