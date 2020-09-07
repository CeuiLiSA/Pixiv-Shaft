package ceui.lisa.ui.presenter;

import ceui.lisa.activities.Shaft;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.interfaces.ListShow;
import ceui.lisa.ui.IModel;
import ceui.lisa.ui.IPresent;
import ceui.lisa.ui.IView;
import io.reactivex.Observer;

public abstract class ListPresenter<T extends ListShow<?>> implements IPresent<T> {

    private IView<T> view;
    private IModel<T> model;
    private String nextUrl = "";

    public abstract IModel<T> model();

    public ListPresenter() {
        model = model();
    }

    @Override
    public void first() {
        view.clearData();
        model.fetchFirst(this);
    }

    @Override
    public void next() {
        model.fetchNext(this);
    }

    @Override
    public void attach(IView<T> v) {
        view = v;
    }


    @Override
    public void detach() {
        view = null;
    }

    @Override
    public Observer<T> processFirst() {
        return new NullCtrl<T>() {
            @Override
            public void success(T listUser) {
                nextUrl = listUser.getNextUrl();
                view.loadFirst(listUser);
            }
        };
    }

    @Override
    public Observer<T> processNext() {
        return new NullCtrl<T>() {
            @Override
            public void success(T listUser) {
                nextUrl = listUser.getNextUrl();
                view.loadNext(listUser);
            }
        };
    }


    @Override
    public String getNextUrl() {
        return nextUrl;
    }

    @Override
    public String getToken() {
        return Shaft.sUserModel.getResponse().getAccess_token();
    }
}
