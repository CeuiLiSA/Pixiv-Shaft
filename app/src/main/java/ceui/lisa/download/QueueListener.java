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

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.SparseArray;

import com.liulishuo.okdownload.DownloadTask;
import com.liulishuo.okdownload.StatusUtil;
import com.liulishuo.okdownload.core.breakpoint.BreakpointInfo;
import com.liulishuo.okdownload.core.cause.EndCause;
import com.liulishuo.okdownload.core.cause.ResumeFailedCause;
import com.liulishuo.okdownload.core.listener.DownloadListener1;
import com.liulishuo.okdownload.core.listener.assist.Listener1Assist;

import ceui.lisa.adapters.DownloadTaskAdapter;
import ceui.lisa.utils.Common;

class QueueListener extends DownloadListener1 {
    private static final String TAG = "QueueListener";

    private SparseArray<DownloadTaskAdapter.TagHolder> holderMap = new SparseArray<>();

    void bind(DownloadTask task, DownloadTaskAdapter.TagHolder holder) {
        Log.i(TAG, "bind " + task.getId() + " with " + holder);
        // replace.
        final int size = holderMap.size();
        for (int i = 0; i < size; i++) {
            if (holderMap.valueAt(i) == holder) {
                holderMap.removeAt(i);
                break;
            }
        }
        holderMap.put(task.getId(), holder);
    }

    void resetInfo(DownloadTask task, DownloadTaskAdapter.TagHolder holder) {

        holder.title.setText(task.getFilename());
    }

    public void clearBoundHolder() {
        holderMap.clear();
    }

    @Override
    public void taskStart(@NonNull DownloadTask task,
                          @NonNull Listener1Assist.Listener1Model model) {

    }

    @Override public void retry(@NonNull DownloadTask task, @NonNull ResumeFailedCause cause) {
    }

    @Override
    public void connected(@NonNull DownloadTask task, int blockCount, long currentOffset,
                          long totalLength) {
        final DownloadTaskAdapter.TagHolder holder = holderMap.get(task.getId());
        if (holder == null) return;


        holder.mProgressBar.setMax((int) totalLength);
        holder.mProgressBar.setProgress((int) currentOffset);
        Common.showLog(TAG + " connected " + currentOffset + "/" + totalLength);
    }

    @Override
    public void progress(@NonNull DownloadTask task, long currentOffset, long totalLength) {

        final DownloadTaskAdapter.TagHolder holder = holderMap.get(task.getId());
        if (holder == null) return;



//        Log.i(TAG, "progress " + task.getId() + " with " + holder);
//        ProgressUtil.updateProgressToViewWithMark(holder.mProgressBar, currentOffset, false);
        holder.mProgressBar.setProgress((int) (currentOffset));
        Common.showLog(TAG + " progress " + currentOffset + "/" + totalLength);
    }

    @Override
    public void taskEnd(@NonNull DownloadTask task, @NonNull EndCause cause,
                        @Nullable Exception realCause,
                        @NonNull Listener1Assist.Listener1Model model) {
        final String status = cause.toString();
        final DownloadTaskAdapter.TagHolder holder = holderMap.get(task.getId());
        if (holder == null) return;

        if (cause == EndCause.COMPLETED) {
            holder.mProgressBar.setProgress(holder.mProgressBar.getMax());
        }
    }
}