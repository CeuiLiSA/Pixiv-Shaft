package ceui.lisa.http;

import ceui.lisa.utils.Common;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;

public abstract class ErrorCtrl<T> implements Observer<T> {

    @Override
    public void onSubscribe(Disposable d) {

    }

    @Override
    public void onError(Throwable e) {
        e.printStackTrace();
        Common.showToast(e.toString());
    }

    @Override
    public void onComplete() {

    }
}
