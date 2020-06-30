package ceui.lisa.base.list;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.databinding.ViewDataBinding;
import androidx.recyclerview.widget.RecyclerView;

public class BaseViewHolder<Layout extends ViewDataBinding> extends RecyclerView.ViewHolder {

    protected Layout bind;

    public BaseViewHolder(@NonNull View itemView) {
        super(itemView);
        bind = DataBindingUtil.bind(itemView);
    }
}
