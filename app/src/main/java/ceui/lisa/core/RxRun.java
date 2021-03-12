package ceui.lisa.core;


import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

public class RxRun {

    /**
     * runnable在新线程运行，observer在主线程回调。
     * 默认第一阶段发射的参数与第二阶段接收的参数类型不一致，使用rxjava-map做了一波转换。
     * 其实也可以把Function做的转换操作放到subscribe里面执行
     *
     * @param runnable 需要异步执行的代码片段
     * @param observer 处理异步执行的结果
     * @param mapper   类型转换
     * @param <T>      emitter发射类型
     * @param <R>      observer接收类型
     */
    public static <T, R> void runOn(RxRunnable<T> runnable, Observer<R> observer, Function<T, R> mapper) {
        if (runnable == null) {
            return;
        }
        runnable.beforeExecute();
        Observable<T> observable = Observable.create(emitter -> {
            try {
                T result = runnable.execute();
                emitter.onNext(result);
                emitter.onComplete();
            } catch (Exception e) {
                emitter.onError(e);
            }
        });
        observable.subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .map(mapper)
                .subscribe(observer);
    }


    /**
     * 方法功能同上，只不过操作的参数类型一致，
     *
     * @param runnable 需要异步执行的代码片段
     * @param observer 处理异步执行的结果
     * @param <T>      类型参数
     */
    public static <T> void runOn(RxRunnable<T> runnable, Observer<T> observer) {
        runOn(runnable, observer, t -> t);
    }
}
