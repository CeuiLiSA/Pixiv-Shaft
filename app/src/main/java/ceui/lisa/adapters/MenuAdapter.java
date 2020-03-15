package ceui.lisa.adapters;

import android.content.Context;
import android.view.ViewGroup;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyMenuBinding;
import ceui.lisa.model.MenuItem;
import ceui.lisa.utils.DensityUtil;

public class MenuAdapter extends BaseAdapter<MenuItem, RecyMenuBinding> {

    private int imageSize;

    public MenuAdapter(List<MenuItem> targetList, Context context) {
        super(targetList, context);
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                DensityUtil.dp2px(48.0f)) / 2;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_menu;
    }

    @Override
    public void bindData(MenuItem target, ViewHolder<RecyMenuBinding> bindView, int position) {
        ViewGroup.LayoutParams params = bindView.baseBind.rootCard.getLayoutParams();
        params.width = imageSize;
        params.height = imageSize;
        bindView.baseBind.rootCard.setLayoutParams(params);

        bindView.baseBind.itemName.setText(target.getName());
    }
}
