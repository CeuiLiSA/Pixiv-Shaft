package ceui.lisa.adapters;

import android.content.Context;

import com.liulishuo.okdownload.StatusUtil;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.database.IllustTask;
import ceui.lisa.databinding.RecyDownloadTaskBinding;
import ceui.lisa.download.DListener;

//正在下载
public class DownloadingAdapter extends BaseAdapter<IllustTask, RecyDownloadTaskBinding> {

    public DownloadingAdapter(List<IllustTask> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_download_task;
    }

    @Override
    public void bindData(IllustTask target, ViewHolder<RecyDownloadTaskBinding> bindView, int position) {
        bindView.baseBind.taskName.setText(target.getDownloadTask().getFilename());

        DListener listener = (DListener) allIllust.get(position).getDownloadTask().getListener();
        StatusUtil.Status status = StatusUtil.getStatus(allIllust.get(position).getDownloadTask());
        if (status == StatusUtil.Status.COMPLETED) {
            bindView.baseBind.state.setText(mContext.getString(R.string.has_download));
        } else if (status == StatusUtil.Status.IDLE) {
            bindView.baseBind.state.setText("闲置中");
        } else if (status == StatusUtil.Status.PENDING) {
            bindView.baseBind.state.setText("等待下载");
        } else if (status == StatusUtil.Status.RUNNING) {
            bindView.baseBind.state.setText("下载中");
        } else if (status == StatusUtil.Status.UNKNOWN) {
            bindView.baseBind.state.setText("未知状态");
        } else {
            bindView.baseBind.state.setText("最坏的情况");
        }


//        if (listener.getNowID() == target.getDownloadTask().getId()) {
//            bindView.baseBind.progress.setTag("update");
//            bindView.baseBind.currentSize.setTag("update");
//            bindView.baseBind.progress.setProgress(listener.getNowOffset());
//            bindView.baseBind.progress.setMax(listener.getMax());
//            listener.bind(bindView.baseBind.progress, bindView.baseBind.currentSize);
//        } else {
//            bindView.baseBind.progress.setTag("wtf");
//            bindView.baseBind.currentSize.setTag("wtf");
//            bindView.baseBind.progress.setProgress(0);
//            bindView.baseBind.currentSize.setText("0.00KB / 未知大小");
//        }
        listener.bind(target.getDownloadTask().getId(), bindView);
    }
}
