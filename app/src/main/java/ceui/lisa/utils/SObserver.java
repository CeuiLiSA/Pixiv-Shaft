package ceui.lisa.utils;

import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;

public class SObserver<T> implements Observer<T> {

    @Override
    public void onSubscribe(Disposable d) {

    }

    @Override
    public void onNext(T t) {

    }

    @Override
    public void onError(Throwable e) {
        e.printStackTrace();
        Common.showToast(e.toString());
    }

    @Override
    public void onComplete() {

    }

    public interface Back{

        void success();

        void error();
    }
}
