package ceui.lisa.http;

import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.ObservableTransformer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class Rx {

    private static final ObservableTransformer newThreadTransformer = upstream -> upstream
            .subscribeOn(Schedulers.newThread())
            .observeOn(AndroidSchedulers.mainThread());


    private static final ObservableTransformer ioTransformer = upstream -> upstream
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread());


    private static final ObservableTransformer computationTransformer = upstream -> upstream
            .subscribeOn(Schedulers.computation())
            .observeOn(AndroidSchedulers.mainThread());

    public static <T> ObservableTransformer<T, T> newThread() {
        return (ObservableTransformer<T, T>) newThreadTransformer;
    }

    public static <T> ObservableTransformer<T, T> io() {
        return (ObservableTransformer<T, T>) ioTransformer;
    }

    public static <T> ObservableTransformer<T, T> computation() {
        return (ObservableTransformer<T, T>) computationTransformer;
    }
}
