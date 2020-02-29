package ceui.lisa.core;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.databinding.ViewDataBinding;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

public abstract class ModelFragment<Layout extends ViewDataBinding,
        Model extends ViewModel> extends BindFragment<Layout> {

    protected Model model;

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        model = new ViewModelProvider(this).get(modelClass());
        super.onActivityCreated(savedInstanceState);
    }

    public abstract Class<Model> modelClass();
}
