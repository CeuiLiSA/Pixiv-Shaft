package ceui.lisa.adapters;

import android.content.Context;
import android.view.View;
import android.widget.CompoundButton;

import androidx.annotation.Nullable;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyFileNameBinding;
import ceui.lisa.download.FileCreator;
import ceui.lisa.model.CustomFileNameCell;

public class FileNameAdapter extends BaseAdapter<CustomFileNameCell, RecyFileNameBinding> {

    public FileNameAdapter(@Nullable List<CustomFileNameCell> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_file_name;
    }

    @Override
    public void bindData(CustomFileNameCell target, ViewHolder<RecyFileNameBinding> bindView, int position) {
        bindView.baseBind.title.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                target.setChecked(isChecked);
                if (mOnItemClickListener != null) {
                    mOnItemClickListener.onItemClick(bindView.itemView, position, 0);
                }
            }
        });

        bindView.baseBind.title.setChecked(target.isChecked());
        bindView.baseBind.title.setText(target.getTitle());
        bindView.baseBind.description.setText(target.getDesc());
        bindView.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bindView.baseBind.title.performClick();
            }
        });

        boolean enabled = target.getCode() != FileCreator.ILLUST_ID && target.getCode() != FileCreator.P_SIZE;
        bindView.baseBind.title.setEnabled(enabled);
        bindView.itemView.setEnabled(enabled);
    }

    public void unCheckAll() {
        for (CustomFileNameCell customFileNameCell : allItems) {
            customFileNameCell.setChecked(false);
        }
        notifyDataSetChanged();
    }
}
