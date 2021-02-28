package ceui.lisa.adapters;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.View;

import androidx.annotation.Nullable;

import com.blankj.utilcode.util.Utils;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.MainActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.RecyColorBinding;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.ColorItem;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.Local;

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
            bindView.baseBind.name.setText(String.format("%s（正在使用）", target.getName()));
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
                Common.showToast("设置成功", 2);
            }
        });
    }
}
