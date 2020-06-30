package ceui.lisa.base.list;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.databinding.ViewDataBinding;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;


public abstract class BaseAdapter<T, Layout extends ViewDataBinding> extends
        RecyclerView.Adapter<BaseViewHolder<Layout>> {

    protected List<T> allItems;
    protected Context mContext;
    protected int mLayoutID;

    public BaseAdapter(Context context, List<T> items){
        mContext = context;
        allItems = items;
        initLayout();
    }

    @NonNull
    @Override
    public BaseViewHolder<Layout> onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(mContext).inflate(mLayoutID, parent, false);
        return new BaseViewHolder<>(view);
    }

    @Override
    public void onBindViewHolder(@NonNull BaseViewHolder<Layout> holder, int position) {
        bindData(allItems.get(position), holder, position);
    }

    protected abstract void bindData(T item, BaseViewHolder<Layout> holder, int position);

    @Override
    public int getItemCount() {
        return allItems == null ? 0 : allItems.size();
    }

    public void clear() {
        final int size = allItems.size();
        allItems.clear();
        notifyItemRangeRemoved(0, size);
    }

    protected abstract void initLayout();
}
