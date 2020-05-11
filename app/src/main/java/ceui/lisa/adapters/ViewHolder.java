package ceui.lisa.adapters;


import androidx.databinding.ViewDataBinding;
import androidx.recyclerview.widget.RecyclerView;

public class ViewHolder<BindView extends ViewDataBinding> extends RecyclerView.ViewHolder {

    public BindView baseBind;

    public ViewHolder(BindView pBaseBind) {
        super(pBaseBind.getRoot());
        baseBind = pBaseBind;
    }
}
