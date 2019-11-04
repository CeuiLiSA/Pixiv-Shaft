package ceui.lisa.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.liulishuo.okdownload.StatusUtil;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.database.IllustTask;
import ceui.lisa.download.QueueListener;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.IllustsBean;


public class DownloadTaskAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private Context mContext;
    private LayoutInflater mLayoutInflater;
    private OnItemClickListener mOnItemClickListener;
    private List<IllustTask> allIllust;

    public DownloadTaskAdapter(List<IllustTask> list, Context context) {
        mContext = context;
        mLayoutInflater = LayoutInflater.from(mContext);
        allIllust = list;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mLayoutInflater.inflate(R.layout.recy_download_task, parent, false);
        return new TagHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        final TagHolder currentOne = (TagHolder) holder;


        ((QueueListener) allIllust.get(position).getDownloadTask().getListener()).bind(currentOne,
                allIllust.get(position).getDownloadTask());
        StatusUtil.Status status = StatusUtil.getStatus(allIllust.get(position).getDownloadTask());
        if (status == StatusUtil.Status.COMPLETED) {
            currentOne.state.setText(mContext.getString(R.string.has_download));
        } else if (status == StatusUtil.Status.IDLE) {
            currentOne.state.setText("闲置中");
        } else if (status == StatusUtil.Status.PENDING) {
            currentOne.state.setText("等待下载");
        } else if (status == StatusUtil.Status.RUNNING) {
            currentOne.state.setText("下载中");
        } else if (status == StatusUtil.Status.UNKNOWN) {
            currentOne.state.setText("未知状态");
        } else {
            currentOne.state.setText("最坏的情况");
        }
        if (mOnItemClickListener != null) {
            holder.itemView.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 0));
        }
    }

    @Override
    public int getItemCount() {
        return allIllust.size();
    }

    public void setOnItemClickListener(OnItemClickListener itemClickListener) {
        mOnItemClickListener = itemClickListener;
    }

    public static class TagHolder extends RecyclerView.ViewHolder {
        public ProgressBar mProgressBar;
        public TextView title, currentSize, fullSize, state;

        TagHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.task_name);
            mProgressBar = itemView.findViewById(R.id.progress);
            currentSize = itemView.findViewById(R.id.current_size);
            fullSize = itemView.findViewById(R.id.full_size);
            state = itemView.findViewById(R.id.state);
        }
    }
}
