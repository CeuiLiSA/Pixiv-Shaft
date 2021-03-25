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
import ceui.lisa.interfaces.OnItemLongClickListener;
import ceui.lisa.models.Starable;
import ceui.lisa.utils.Common;

public abstract class BaseAdapter<Item, BindView extends ViewDataBinding> extends
        RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int ITEM_HEAD = 1023;
    public static final int ITEM_NORMAL = 1024;
    protected List<Item> allIllust;
    protected Context mContext;
    protected int mLayoutID = -1;
    protected OnItemClickListener mOnItemClickListener;
    protected OnItemLongClickListener mOnItemLongClickListener;
    protected String nextUrl, uuid;

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
            tryCatchBindData(allIllust.get(index), (ViewHolder<BindView>) holder, index);
        } else if (viewType == ITEM_HEAD) {

        }
    }

    @Override
    public int getItemCount() {
        return allIllust.size() + headerSize();
    }

    public abstract void initLayout();

    public abstract void bindData(Item target, ViewHolder<BindView> bindView, int position);

    private void tryCatchBindData(Item target, ViewHolder<BindView> bindView, int position){
        try {
            bindData(target, bindView, position);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @NonNull
    @Override
    public ViewHolder<? extends ViewDataBinding> onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
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

    public BaseAdapter<Item, BindView> setOnItemLongClickListener(OnItemLongClickListener onItemLongClickListener) {
        mOnItemLongClickListener = onItemLongClickListener;
        return this;
    }

    public void clear() {
        final int size = allIllust.size();
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

    public ViewHolder<? extends ViewDataBinding> getHeader(ViewGroup parent) {
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

    public Item getItemAt(int index) {
        if (index < allIllust.size()) {
            return allIllust.get(index);
        }
        return null;
    }

    public void setLiked(int id, boolean isLike) {
        if (id == 0) {
            return;
        }

        if (allIllust == null || allIllust.size() == 0) {
            return;
        }

        for (int i = 0; i < allIllust.size(); i++) {
            if (allIllust.get(i) instanceof Starable) {
                if (((Starable) allIllust.get(i)).getItemID() == id) {
                    //设置这个作品为已收藏状态
                    ((Starable) allIllust.get(i)).setItemStared(isLike);
                    if (headerSize() != 0) {//如果有header
                        notifyItemChanged(i + headerSize());
                    } else { //没有header
                        notifyItemChanged(i);
                    }
                    break;
                }
            }
        }
    }

    public void setNextUrl(String nextUrl) {
        this.nextUrl = nextUrl;
    }

    /**
     * 赋值uuid
     *
     * @param uuid 宿主fragment 的 uuid
     */
    public void setUuid(String uuid) {
        this.uuid = uuid;
    }
}
