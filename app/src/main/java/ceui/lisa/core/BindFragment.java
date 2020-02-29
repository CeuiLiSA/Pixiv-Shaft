package ceui.lisa.core;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.databinding.ViewDataBinding;

public abstract class BindFragment<Layout extends ViewDataBinding> extends BaseFragment {

    protected Layout bind;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        bind = DataBindingUtil.inflate(inflater, mLayoutID, container, false);
        return bind.getRoot();
    }
}
