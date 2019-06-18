package ceui.lisa.fragments;

import android.view.View;

import io.reactivex.Observable;

public abstract class NetworkFragment<Response> extends BaseFragment {

    protected Observable<Response> api = null;

    @Override
    void initData() {
        api = initApi();
    }

    abstract Observable<Response> initApi();
}
