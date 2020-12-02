package ceui.lisa.fragments;

import androidx.databinding.ViewDataBinding;


public abstract class BaseLazyFragment<T extends ViewDataBinding> extends BaseFragment<T> {

    protected boolean isLoaded;

    public void lazyData() {
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        shouldLoadData();
    }

    @Override
    protected void initData() {
        shouldLoadData();
    }

    public void shouldLoadData() {
        if (!isInit) {
            return;
        }

        if (getUserVisibleHint() && !isLoaded) {
            lazyData();
            isLoaded = true;
        }
    }
}
