package ceui.lisa.adapters;

import android.content.Context;
import android.os.Handler;
import android.view.View;
import android.widget.CompoundButton;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.databinding.DataBindingUtil;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyKissBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.WorkThread;
import ceui.lisa.utils.Common;

public class KissAdapter extends BaseAdapter<KissAdapter.Item, RecyKissBinding> {

    public KissAdapter(List<Item> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_kiss;
    }

    @Override
    public void bindData(Item target, ViewHolder<RecyKissBinding> bindView, int position) {
        bindView.baseBind.position.setText(allIllust.get(position).name);
        bindView.baseBind.checkbox.setChecked(allIllust.get(position).checkState);
        bindView.baseBind.checkbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setSelectedIndex(position, isChecked);
            }
        });
        bindView.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSelectedIndex(position, !allIllust.get(position).checkState);
            }
        });
    }

    public void setSelectedIndex(int position, boolean state){
        for (int i = 0; i < allIllust.size(); i++) {
            allIllust.get(i).setCheckState(false);
        }

        allIllust.get(position).setCheckState(state);
        notifyDataSetChanged();
    }

    public static class Item{
        private String name;
        private boolean checkState;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isCheckState() {
            return checkState;
        }

        public void setCheckState(boolean checkState) {
            this.checkState = checkState;
        }
    }
}
