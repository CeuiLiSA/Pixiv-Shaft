/*
 * Copyright (c) 2017 LingoChamp Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ceui.lisa.download;

import android.util.SparseArray;

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

import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.DownloadTaskAdapter;
import ceui.lisa.database.IllustTask;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;

public class QueueListener extends DownloadListener1 {

    private static final String TAG = "QueueListener";
    private SparseArray<DownloadTaskAdapter.TagHolder> holderMap = new SparseArray<>();


    public QueueListener() {
        Common.showLog("QueueListener 生成了一个实例 " + System.currentTimeMillis());
    }

    public void bind(DownloadTaskAdapter.TagHolder holder, DownloadTask task) {
        // replace.
        final int size = holderMap.size();
        for (int i = 0; i < size; i++) {
            if (holderMap.valueAt(i) == holder) {
                holderMap.removeAt(i);
                break;
            }
        }
        holderMap.put(task.getId(), holder);
        final String taskName = task.getFilename();
        holder.title.setText(taskName);
    }

    @Override
    public void taskStart(@NonNull DownloadTask task, @NonNull Listener1Assist.Listener1Model model) {

    }

    @Override
    public void retry(@NonNull DownloadTask task, @NonNull ResumeFailedCause cause) {

    }

    @Override
    public void connected(@NonNull DownloadTask task, int blockCount, long currentOffset, long totalLength) {

        final DownloadTaskAdapter.TagHolder holder = holderMap.get(task.getId());
        if (holder != null) {
            holder.mProgressBar.setMax((int) totalLength);
            holder.fullSize.setText(" / " + FileSizeUtil.formatFileSize(totalLength));
            holder.mProgressBar.setProgress((int) currentOffset);
        }
    }

    @Override
    public void progress(@NonNull DownloadTask task, long currentOffset, long totalLength) {

        final DownloadTaskAdapter.TagHolder holder = holderMap.get(task.getId());
        if (holder != null) {
            Common.showLog("totalLength : " + totalLength + " currentOffset " + currentOffset);
            holder.mProgressBar.setMax((int) totalLength);
            holder.mProgressBar.setProgress((int) currentOffset);
            holder.currentSize.setText(FileSizeUtil.formatFileSize(currentOffset));
        }
    }

    @Override
    public void taskEnd(@NonNull DownloadTask task, @NonNull EndCause cause, @Nullable Exception realCause, @NonNull Listener1Assist.Listener1Model model) {
        IllustTask illustTask = new IllustTask();
        illustTask.setDownloadTask(task);

        Common.showLog("taskEnd " + task.getFilename());

        try {
            Common.showLog(task.getFile().getPath());
            if (task.getFilename().contains(".zip")) {
                //ZipUtil.unpack(task.getFile(), new File(FileCreator.FILE_GIF_CHILD_PATH + task.getFilename().substring(0, task.getFilename().length() - 4)));


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


        TaskQueue.get().removeTask(illustTask);


    }
}