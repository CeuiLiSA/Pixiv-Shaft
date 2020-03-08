package ceui.lisa.core;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ceui.lisa.adapters.ViewHolder;
import ceui.lisa.databinding.RecyRecmdHeaderBinding;
import ceui.lisa.models.IllustsBean;

public class RecmdHeader extends ViewHolder<RecyRecmdHeaderBinding> {

    public RecmdHeader(@NonNull View itemView) {
        super(itemView);
    }

    public void setData(List<IllustsBean> illustsBeans) {
    }
}
