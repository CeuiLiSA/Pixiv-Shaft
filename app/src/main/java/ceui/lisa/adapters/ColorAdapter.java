package ceui.lisa.adapters;

import android.content.Context;
import android.graphics.Color;
import android.view.View;

import java.util.List;

import androidx.annotation.Nullable;
import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.RecyColorBinding;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.ColorItem;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;

import static com.blankj.utilcode.util.StringUtils.getString;

public class ColorAdapter extends BaseAdapter<ColorItem, RecyColorBinding> {

    public ColorAdapter(@Nullable List<ColorItem> targetList, Context context) {
        super(targetList, context);
        handleClick();
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_color;
    }

    @Override
    public void bindData(ColorItem target, ViewHolder<RecyColorBinding> bindView, int position) {
        bindView.baseBind.card.setCardBackgroundColor(Color.parseColor(target.getColor()));
        if (target.isSelect()) {
            bindView.baseBind.name.setText(String.format("%s" + getString(R.string.theme_nowUsing), target.getName()));
        } else {
            bindView.baseBind.name.setText(target.getName());
        }
        bindView.baseBind.value.setText(target.getColor());
        bindView.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mOnItemClickListener.onItemClick(v, position, 0);
            }
        });
    }

    private void handleClick() {
        setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                if (position == Shaft.sSettings.getThemeIndex()) {
                    return;
                }

                Shaft.sSettings.setThemeIndex(position);
                Local.setSettings(Shaft.sSettings);
                Common.restart();
                Common.showToast(getString(R.string.string_428), 2);
            }
        });
    }
}
