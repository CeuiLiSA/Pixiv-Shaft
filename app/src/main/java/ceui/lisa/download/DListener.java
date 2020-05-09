package ceui.lisa.download;

import android.text.TextUtils;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liulishuo.okdownload.DownloadTask;
import com.liulishuo.okdownload.core.cause.EndCause;
import com.liulishuo.okdownload.core.cause.ResumeFailedCause;
import com.liulishuo.okdownload.core.listener.DownloadListener1;
import com.liulishuo.okdownload.core.listener.assist.Listener1Assist;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;

import org.greenrobot.eventbus.EventBus;

import java.io.File;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.IllustTask;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;

public class DListener extends DownloadListener1 {

    private ProgressBar mProgressBar;
    private TextView currentSize;
    private int nowID = 0, max = 0, nowOffset = 0;

    public void bind(ProgressBar progressBar, TextView textView) {
        mProgressBar = progressBar;
        currentSize = textView;
    }

    @Override
    public void taskStart(@NonNull DownloadTask task, @NonNull Listener1Assist.Listener1Model model) {
        nowID = task.getId();
    }

    @Override
    public void retry(@NonNull DownloadTask task, @NonNull ResumeFailedCause cause) {

    }

    @Override
    public void connected(@NonNull DownloadTask task, int blockCount, long currentOffset, long totalLength) {
    }

    @Override
    public void progress(@NonNull DownloadTask task, long currentOffset, long totalLength) {
        Common.showLog("progress " + task.getFilename() + " " + currentOffset + "/" + totalLength);
        if (mProgressBar != null){
            if ("update".equals(mProgressBar.getTag())) {
                mProgressBar.setMax((int) totalLength);
                mProgressBar.setProgress((int) currentOffset);
            } else {
                mProgressBar.setProgress(0);
            }
        }


        if (currentSize != null) {
            if ("update".equals(currentSize.getTag())) {
                currentSize.setText(String.format("%s / %s",
                        FileSizeUtil.formatFileSize(currentOffset),
                        FileSizeUtil.formatFileSize(totalLength)));
            } else {
                currentSize.setText("0.00KB / 未知大小");
            }
        }

        max = (int) totalLength;
        nowOffset = (int) currentOffset;
    }

    @Override
    public void taskEnd(@NonNull DownloadTask task, @NonNull EndCause cause, @Nullable Exception realCause, @NonNull Listener1Assist.Listener1Model model) {

        IllustTask illustTask = new IllustTask();
        illustTask.setDownloadTask(task);
        File endFile = task.getFile();

        if(realCause != null) {
            realCause.printStackTrace();
        }

        switch (cause) {
            case COMPLETED:
                Common.showLog("taskEnd COMPLETED");
                try {
                    if (!TextUtils.isEmpty(task.getFilename()) && task.getFilename().contains(".zip")) {
                        try {
                            ZipFile zipFile = new ZipFile(task.getFile().getPath());
                            zipFile.extractAll(Shaft.sSettings.getGifUnzipPath() +
                                    task.getFilename().substring(0, task.getFilename().length() - 4));
                            Common.showToast("图组ZIP解压完成");

                            //通知FragmentSingleIllust 开始播放gif
                            Channel channel = new Channel();
                            channel.setReceiver("FragmentSingleIllust");
                            EventBus.getDefault().post(channel);
                        } catch (ZipException e) {
                            e.printStackTrace();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                TaskQueue.get().removeTask(illustTask, true);
                break;
            case ERROR:
                Common.showLog("taskEnd ERROR");
                Common.showToast("TASK ERROR " + endFile.getName());
                endFile.delete();
                TaskQueue.get().removeTask(illustTask, false);
                break;
            case CANCELED:
                Common.showLog("taskEnd CANCELED");
                Common.showToast(endFile.getName() + "下载取消");
                endFile.delete();
                TaskQueue.get().removeTask(illustTask, false);
                break;
            case FILE_BUSY:
                Common.showLog("taskEnd FILE_BUSY");
                Common.showToast("TASK FILE_BUSY " + endFile.getName());
                endFile.delete();
                TaskQueue.get().removeTask(illustTask, false);
                break;
            case SAME_TASK_BUSY:
                Common.showLog("taskEnd SAME_TASK_BUSY");
                Common.showToast("TASK SAME_TASK_BUSY " + endFile.getName());
                endFile.delete();
                TaskQueue.get().removeTask(illustTask, false);
                break;
            case PRE_ALLOCATE_FAILED:
                Common.showLog("taskEnd PRE_ALLOCATE_FAILED");
                Common.showToast("TASK PRE_ALLOCATE_FAILED " + endFile.getName());
                endFile.delete();
                TaskQueue.get().removeTask(illustTask, false);
                break;
            default:
                Common.showLog("taskEnd default");
                Common.showToast("TASK PRE_ALLOCATE_FAILED " + endFile.getName());
                endFile.delete();
                TaskQueue.get().removeTask(illustTask, false);
                break;
        }
    }

    public int getNowID() {
        return nowID;
    }

    public int getMax() {
        return max;
    }

    public int getNowOffset() {
        return nowOffset;
    }

}
