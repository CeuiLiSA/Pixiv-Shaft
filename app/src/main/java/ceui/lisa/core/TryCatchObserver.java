package ceui.lisa.core;

import io.reactivex.Observer;
import io.reactivex.annotations.NonNull;
import io.reactivex.disposables.Disposable;

public abstract class TryCatchObserver<T> implements Observer<T> {

    @Override
    public void onSubscribe(@NonNull Disposable d) {
        try {
            subscribe(d);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onNext(@NonNull T t) {
        try {
            next(t);
            must();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onError(@NonNull Throwable e) {
        try {
            error(e);
            must();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public void onComplete() {
        try {
            complete();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public abstract void subscribe(Disposable d);

    public abstract void next(T t);

    public abstract void error(Throwable e);

    public abstract void complete();

    public void must() {

    }
}
