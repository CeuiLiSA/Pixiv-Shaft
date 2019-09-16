package ceui.lisa.adapters;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.databinding.ViewDataBinding;
import androidx.recyclerview.widget.RecyclerView;

public class ViewHolder<BindView extends ViewDataBinding> extends RecyclerView.ViewHolder {
    BindView baseBind;
    public ViewHolder(@NonNull View itemView) {
        super(itemView);
        baseBind = DataBindingUtil.bind(itemView);
    }
}
