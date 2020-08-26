package ceui.lisa.download;

import android.text.TextUtils;
import android.view.View;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liulishuo.okdownload.DownloadTask;
import com.liulishuo.okdownload.core.cause.EndCause;
import com.liulishuo.okdownload.core.cause.ResumeFailedCause;
import com.liulishuo.okdownload.core.listener.DownloadListener1;
import com.liulishuo.okdownload.core.listener.assist.Listener1Assist;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.IllustTask;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;

public class GifListener extends DownloadListener1 {

    private ProgressBar mProgressBar;
    private IllustsBean mIllustsBean;
    private OnGifPrepared mOnGifPrepared;
    private int maxValue;
    private int delay;

    public GifListener(IllustsBean illustsBean, int paramDelay) {
        mIllustsBean = illustsBean;
        delay = paramDelay;
    }

    public void bindProgress(ProgressBar progressBar) {
        mProgressBar = progressBar;
        mProgressBar.setMax(maxValue);
    }

    public void bindListener(OnGifPrepared onGifPrepared) {
        mOnGifPrepared = onGifPrepared;
    }

    @Override
    public void taskStart(@NonNull DownloadTask task, @NonNull Listener1Assist.Listener1Model model) {
        mProgressBar.setProgress(0);
    }

    @Override
    public void retry(@NonNull DownloadTask task, @NonNull ResumeFailedCause cause) {

    }

    @Override
    public void connected(@NonNull DownloadTask task, int blockCount, long currentOffset, long totalLength) {
        if(mProgressBar != null && mProgressBar.getVisibility() == View.VISIBLE){
            mProgressBar.setMax((int) totalLength);
            mProgressBar.setProgress((int) currentOffset);
            maxValue = (int) totalLength;
        }
    }

    @Override
    public void progress(@NonNull DownloadTask task, long currentOffset, long totalLength) {
        if(mProgressBar != null && mProgressBar.getVisibility() == View.VISIBLE){
            mProgressBar.setProgress((int) currentOffset);
        }
    }

    @Override
    public void taskEnd(@NonNull DownloadTask task, @NonNull EndCause cause, @Nullable Exception realCause, @NonNull Listener1Assist.Listener1Model model) {
        mProgressBar = null;

        IllustTask illustTask = new IllustTask();
        illustTask.setDownloadTask(task);
        illustTask.setIllustsBean(mIllustsBean);

        Common.showLog("taskEnd " + task.getFilename());

        try {
            Common.showLog(task.getFile().getPath());
            if (!TextUtils.isEmpty(task.getFilename()) && task.getFilename().contains(".zip")) {

                try {
                    ZipFile zipFile = new ZipFile(task.getFile().getPath());
                    zipFile.extractAll(Shaft.sSettings.getGifUnzipPath() +
                            task.getFilename().substring(0, task.getFilename().length() - 4));
                    Common.showToast("图组ZIP解压完成");

                    if(mOnGifPrepared != null){
                        mOnGifPrepared.play(delay);
                    }

                    task.getFile().delete();
                } catch (ZipException e) {
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        GifQueue.get().removeTask(illustTask);
    }

    public interface OnGifPrepared{
        void play(int delay);
    }
}
