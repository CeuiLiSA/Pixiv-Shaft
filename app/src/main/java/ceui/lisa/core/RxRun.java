package ceui.lisa.core;


import androidx.annotation.NonNull;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class RxRun {

    /**
     * runnable在新线程运行，observer在主线程回调，
     *
     * @param runnable 需要异步执行的代码片段
     * @param observer 处理异步执行的结果
     * @param <T> 类型参数
     */
    public static <T> void runOn(RxRunnable<T> runnable, Observer<T> observer) {
        Observable<T> observable = Observable.create(new ObservableOnSubscribe<T>() {
            @Override
            public void subscribe(@NonNull ObservableEmitter<T> emitter) {
                try {
                    if (runnable != null) {
                        T result = runnable.execute();
                        if (result != null) {
                            emitter.onNext(result);
                        }
                        emitter.onComplete();
                    }
                } catch (Exception e) {
                    emitter.onError(e);
                    e.printStackTrace();
                }
            }
        });
        observable.subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(observer);
    }
}
