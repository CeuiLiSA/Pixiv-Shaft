package ceui.lisa.adapters;

import android.content.Context;
import android.view.View;

import java.util.List;

import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.models.IllustsBean;

public class IAdapterWithStar extends IAdapter {

    private boolean showStarIcon;

    public IAdapterWithStar(List<IllustsBean> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void bindData(IllustsBean target, ViewHolder<RecyIllustStaggerBinding> bindView, int position) {
        super.bindData(target, bindView, position);
        bindView.baseBind.likeButton.setVisibility(showStarIcon ? View.VISIBLE : View.GONE);
    }

    public IAdapterWithStar setShowStarIcon(boolean showStarIcon) {
        this.showStarIcon = showStarIcon;
        return this;
    }
}
