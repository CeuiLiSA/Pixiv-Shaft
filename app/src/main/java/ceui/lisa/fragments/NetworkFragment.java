package ceui.lisa.fragments;

import android.view.View;

import io.reactivex.Observable;

public abstract class NetworkFragment<Response> extends BaseFragment {

    protected Observable<Response> api = null;

    @Override
    void initData() {
        initApi();
    }

    abstract void initApi();
}
