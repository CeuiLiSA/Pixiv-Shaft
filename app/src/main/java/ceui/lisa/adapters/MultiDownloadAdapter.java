package ceui.lisa.adapters;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.recyclerview.widget.RecyclerView;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;

import com.bumptech.glide.Glide;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.interfaces.MultiDownload;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.utils.GlideUtil;

public class MultiDownloadAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> implements MultiDownload {

    private Context mContext;
    private LayoutInflater mLayoutInflater;
    private OnItemClickListener mOnItemClickListener;
    private List<IllustsBean> allIllust;
    private Callback mCallback;
    private int imageSize = 0;

    public MultiDownloadAdapter(List<IllustsBean> list, Context context) {
        mContext = context;
        mLayoutInflater = LayoutInflater.from(mContext);
        allIllust = list;
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                mContext.getResources().getDimensionPixelSize(R.dimen.two_dp))/3;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mLayoutInflater.inflate(R.layout.recy_multi_download, parent, false);
        return new TagHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        final TagHolder currentOne = (TagHolder) holder;

        ViewGroup.LayoutParams params = currentOne.illust.getLayoutParams();
        params.height = imageSize;
        params.width = imageSize;
        currentOne.illust.setLayoutParams(params);
        Glide.with(mContext)
                .load(GlideUtil.getMediumImg(allIllust.get(position)))
                .placeholder(R.color.light_bg)
                .into(currentOne.illust);

        currentOne.mCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if(isChecked){
                    allIllust.get(position).setChecked(true);
                }else {
                    allIllust.get(position).setChecked(false);
                }
                mCallback.doSomething(null);
            }
        });

        if(allIllust.get(position).isChecked()){
            currentOne.mCheckBox.setChecked(true);
        }else {
            currentOne.mCheckBox.setChecked(false);
        }

        holder.itemView.setOnClickListener(v -> {
            mOnItemClickListener.onItemClick(holder.itemView, position, 0);
        });
    }

    @Override
    public int getItemCount() {
        return allIllust.size();
    }

    public void setOnItemClickListener(OnItemClickListener itemClickListener) {
        mOnItemClickListener = itemClickListener;
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public List<IllustsBean> getIllustList() {
        return allIllust;
    }

    public static class TagHolder extends RecyclerView.ViewHolder {
        ImageView illust;
        AppCompatCheckBox mCheckBox;
        TagHolder(View itemView) {
            super(itemView);
            illust = itemView.findViewById(R.id.illust_image);
            mCheckBox = itemView.findViewById(R.id.checkbox);
        }
    }

    public Callback getCallback() {
        return mCallback;
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }
}
