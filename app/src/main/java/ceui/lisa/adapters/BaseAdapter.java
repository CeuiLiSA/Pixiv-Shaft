package ceui.lisa.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.databinding.ViewDataBinding;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.utils.Common;

public abstract class BaseAdapter<Item, BindView extends ViewDataBinding> extends
        RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int ITEM_HEAD = 1023;
    public static final int ITEM_NORMAL = 1024;
    protected List<Item> allIllust;
    protected Context mContext;
    protected int mLayoutID = -1;
    protected OnItemClickListener mOnItemClickListener;

    public BaseAdapter(@Nullable List<Item> targetList, Context context) {
        Common.showLog(getClass().getSimpleName() + " newInstance");
        this.allIllust = targetList;
        this.mContext = context;
        initLayout();
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        int viewType = getItemViewType(position);
        if (viewType == ITEM_NORMAL) {
            int index = position - headerSize();
            bindData(allIllust.get(index), (ViewHolder<BindView>) holder, index);
        } else if (viewType == ITEM_HEAD) {

        }
    }

    @Override
    public int getItemCount() {
        return allIllust.size() + headerSize();
    }

    public abstract void initLayout();

    public abstract void bindData(Item target, ViewHolder<BindView> bindView, int position);

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == ITEM_NORMAL) {
            return getNormalItem(parent);
        } else {
            return getHeader(parent);
        }
    }

    public BaseAdapter<Item, BindView> setOnItemClickListener(OnItemClickListener onItemClickListener) {
        mOnItemClickListener = onItemClickListener;
        return this;
    }

    public void clear() {
        int size = allIllust.size();
        allIllust.clear();
        notifyItemRangeRemoved(0, size);
    }

    @Override
    public int getItemViewType(int position) {
        if (position < headerSize()) {
            return ITEM_HEAD;
        }
        return ITEM_NORMAL;
    }

    public int headerSize() {
        return 0;
    }

    public ViewHolder getHeader(ViewGroup parent) {
        return null;
    }

    public ViewHolder<BindView> getNormalItem(ViewGroup parent) {
        return new ViewHolder<>(
                DataBindingUtil.inflate(
                        LayoutInflater.from(mContext),
                        mLayoutID,
                        parent,
                        false
                )
        );
    }
}
