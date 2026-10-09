package ceui.lisa.adapters;


import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

/**
 * 泛型上界从 {@code ViewDataBinding} 放宽到 {@link ViewBinding}。
 *
 * DataBinding 生成的绑定类都实现了 ViewBinding，所以既有调用方一个都不用改；而 ViewBinding
 * 生成的绑定类（如改成 ViewBinding 的 {@code RecyIllustDetailBinding}）现在也能用。
 * 本类只用到 {@code getRoot()}，两个接口都有，放宽是纯语义扩大、不会破坏任何现有用法。
 */
public class ViewHolder<BindView extends ViewBinding> extends RecyclerView.ViewHolder {

    public BindView baseBind;

    public ViewHolder(BindView pBaseBind) {
        super(pBaseBind.getRoot());
        baseBind = pBaseBind;
    }
}
