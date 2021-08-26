package ceui.lisa.adapters;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.databinding.DataBindingUtil;

import com.bumptech.glide.Glide;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.core.DownloadItem;
import ceui.lisa.core.Manager;
import ceui.lisa.databinding.RecyDownloadTaskBinding;
import ceui.lisa.download.DownloadHolder;
import ceui.lisa.download.FileSizeUtil;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.utils.GlideUtil;
import rxhttp.wrapper.entity.Progress;

//正在下载
public class DownloadingAdapter extends BaseAdapter<DownloadItem, RecyDownloadTaskBinding> {

    @Override
    public ViewHolder<RecyDownloadTaskBinding> getNormalItem(ViewGroup parent) {
        return new DownloadHolder(
                DataBindingUtil.inflate(
                        LayoutInflater.from(mContext),
                        mLayoutID,
                        parent,
                        false
                )
        );
    }

    public DownloadingAdapter(List<DownloadItem> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_download_task;
    }

    @Override
    public void bindData(DownloadItem target, ViewHolder<RecyDownloadTaskBinding> bindView, int position) {
        bindView.baseBind.taskName.setText(target.getName());
        bindView.baseBind.progress.setTag(target.getUuid());
        if (!TextUtils.isEmpty(target.getShowUrl())) {
            Glide.with(mContext)
                    .load(GlideUtil.getUrl(target.getShowUrl()))
                    .into(bindView.baseBind.illustImage);
        }

        final Manager manager = Manager.get();
        // 回调
        manager.setCallback(target.getUuid(), new Callback<Progress>() {
            @Override
            public void doSomething(Progress t) {
                if (manager.getUuid().equals(target.getUuid())) {
                    bindView.baseBind.progress.setProgress(t.getProgress());
                    bindView.baseBind.currentSize.setText(String.format("%s / %s",
                            FileSizeUtil.formatFileSize(t.getCurrentSize()),
                            FileSizeUtil.formatFileSize(t.getTotalSize())));
                    bindView.baseBind.state.setText("正在下载");
                }
            }
        });

        setDefaultView(target, bindView, position);

        bindView.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (target.isPaused()) {
                    manager.startOne(mContext, target.getUuid());
                    bindView.baseBind.state.setText("未开始");
                } else {
                    manager.stopOne(target.getUuid());
                    bindView.baseBind.state.setText("已暂停");
                }
            }
        });

        bindView.baseBind.deleteItem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.clearOne(target.getUuid());
                allItems.remove(target);
                notifyItemRemoved(position);
                notifyItemRangeChanged(position, allItems.size() - position);
            }
        });
    }

    private void setDefaultView(DownloadItem target, ViewHolder<RecyDownloadTaskBinding> bindView, int position) {
        bindView.baseBind.progress.setProgress(target.getNonius());
        bindView.baseBind.currentSize.setText(mContext.getString(R.string.string_115));

        switch (target.getState()){
            case DownloadItem.DownloadState.INIT:
                bindView.baseBind.state.setText("未开始");
                break;
            case DownloadItem.DownloadState.DOWNLOADING:
                bindView.baseBind.state.setText("正在下载");
                break;
            case DownloadItem.DownloadState.PAUSED:
                bindView.baseBind.state.setText("已暂停");
                break;
            case DownloadItem.DownloadState.FAILED:
                bindView.baseBind.state.setText("已失败");
                break;
            case DownloadItem.DownloadState.SUCCESS:
                bindView.baseBind.state.setText("已完成");
                break;
            default:
                bindView.baseBind.state.setText("");
        }
    }
}
