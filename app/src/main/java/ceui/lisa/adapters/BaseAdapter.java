package ceui.lisa.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.databinding.ViewDataBinding;
import androidx.recyclerview.widget.RecyclerView;


import java.util.ArrayList;
import java.util.List;

import ceui.lisa.interfaces.Binding;
import ceui.lisa.interfaces.OnItemClickListener;

public abstract class BaseAdapter<Item, BindView extends ViewDataBinding> extends
        RecyclerView.Adapter<RecyclerView.ViewHolder> implements Binding<BindView> {

    protected List<Item> allIllust;
    protected Context mContext;
    protected int mLayoutID = -1;
    protected OnItemClickListener mOnItemClickListener;

    public BaseAdapter(List<Item> targetList, Context context) {
        this.allIllust = targetList;
        this.mContext = context;
        initLayout();
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        bindData(allIllust.get(position), (ViewHolder<BindView>)holder, position);
    }

    @Override
    public int getItemCount() {
        return allIllust.size();
    }

    public abstract void initLayout();

    public abstract void bindData(Item target, ViewHolder<BindView> bindView, int position);

    @Override
    public BindView getBind(LayoutInflater inflater, ViewGroup container) {
        return DataBindingUtil.inflate(inflater, mLayoutID,
                container, false);
    }

    @NonNull
    @Override
    public ViewHolder<BindView> onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder<>(getBind(LayoutInflater.from(mContext), parent).getRoot());
    }

    public void setOnItemClickListener(OnItemClickListener onItemClickListener) {
        mOnItemClickListener = onItemClickListener;
    }

    public void clear(){
        int size = allIllust.size();
        allIllust.clear();
        notifyItemRangeRemoved(0, size);
    }
}
