package ceui.lisa.adapters;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.liulishuo.okdownload.DownloadTask;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.download.TaskQueue;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.response.ArticalResponse;
import ceui.lisa.utils.GlideUtil;


public class DownloadTaskAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private Context mContext;
    private LayoutInflater mLayoutInflater;
    private OnItemClickListener mOnItemClickListener;
    private List<DownloadTask> allIllust;

    public DownloadTaskAdapter(List<DownloadTask> list, Context context) {
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


        TaskQueue.get().bind(currentOne, position);


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
        public TextView title;
        TagHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.task_name);
            mProgressBar = itemView.findViewById(R.id.progress);
        }
    }
}
