package ceui.lisa.utils;

import ceui.lisa.interfaces.ListShow;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;


public abstract class ListObserver<T extends ListShow> implements Observer<T> {

    @Override
    public void onSubscribe(Disposable d) {

    }

    @Override
    public void onNext(T t) {
        if (t != null) {
            if (t.getList() != null && t.getList().size() != 0) {
                success(t);
            } else {
                dataError();
            }
        } else {
            netError();
        }
    }

    @Override
    public void onError(Throwable e) {
        e.printStackTrace();
        Common.showToast(e.toString());
        dataError();
    }


    public abstract void success(T t);

    public abstract void dataError();

    public abstract void netError();

    public void onComplete() {

    }
}
