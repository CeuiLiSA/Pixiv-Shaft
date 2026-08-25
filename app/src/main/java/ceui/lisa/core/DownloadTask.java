package ceui.lisa.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import ceui.lisa.utils.Common;

/**
 * {@link Manager} 单条传输的执行/取消句柄，替代原先的
 * {@code Observable.create(...).subscribeOn(io).observeOn(io).doFinally(...).subscribe(onNext, onError)}
 * + {@code Disposable}。语义逐条对齐 RxJava 2：
 *
 * <ul>
 *   <li>{@link Body} 在 IO 线程跑，通过 {@link DownloadEmitter} 发 onNext / onComplete / onError；
 *       终态（complete / error）之后再发任何事件一律静默丢弃，{@link DownloadEmitter#isDisposed()}
 *       在终态或被取消后都返回 true（与 {@code ObservableCreate.CreateEmitter} 一致）。</li>
 *   <li>Body 跑完后，值/错误在**同一条 IO 线程**上顺序交给 {@code onNext} / {@code onError}
 *       消费者（对应 observeOn(io)：消费仍在 IO，不进主线程）。交付前再查一次取消标记——
 *       "producer 已 onNext、consumer 还没跑"这段窗口里被 cancel 的话该值被丢弃，
 *       和 observeOn 跳变窗口的行为相同。</li>
 *   <li>{@code onNext} 消费者抛异常 → 转交 {@code onError}；{@code onError} 抛异常 → 只打日志
 *       （对应 RxJavaPlugins 的全局兜底，不 crash）。</li>
 *   <li>{@code onFinally} 恰好跑一次：正常结束时在 IO 线程、任务收尾之后；被 {@link #cancel()}
 *       时立刻在调用 cancel 的线程上跑（和 doFinally 对 dispose 的行为一致），之后 Body
 *       自己看 isDisposed 退出，不会再触发一次。</li>
 *   <li>{@link #cancel()} 只在 Body 还在跑（producing 阶段）时中断工作线程（Rx 的
 *       subscribeOn worker dispose 也会 interrupt）；已进入消费阶段就不中断——Rx 里消费者
 *       在另一条 observeOn 线程上，dispose 本来就碰不到它，DB 写入 / finishWrite 不能被打断。</li>
 * </ul>
 */
final class DownloadTask {

    interface Body {
        void run(DownloadEmitter emitter) throws Exception;
    }

    interface Consumer<T> {
        void accept(T value) throws Exception;
    }

    private static final int PRODUCING = 0;
    private static final int CONSUMING = 1;
    private static final int DONE = 2;

    private final Body body;
    private final Consumer<String> onNext;
    private final Consumer<Throwable> onError;
    private final Runnable onFinally;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean finallyRan = new AtomicBoolean(false);
    /**
     * 守住「读 phase/worker → interrupt」与「phase 切换 → 清中断标记」两段的原子性：
     * 否则 cancel() 读到 PRODUCING 后被抢占，工作线程跑完交付、归还线程池、领走别条下载，
     * 主线程恢复时 interrupt 会打到那条无辜的下载（Rx 的 FutureTask.cancel(true) 靠 runner CAS
     * 防的就是这个）。
     */
    private final Object interruptLock = new Object();
    private Thread worker;
    private int phase = PRODUCING;

    private DownloadTask(Body body, Consumer<String> onNext, Consumer<Throwable> onError, Runnable onFinally) {
        this.body = body;
        this.onNext = onNext;
        this.onError = onError;
        this.onFinally = onFinally;
    }

    static DownloadTask launch(ExecutorService io, Body body, Consumer<String> onNext,
                               Consumer<Throwable> onError, Runnable onFinally) {
        DownloadTask task = new DownloadTask(body, onNext, onError, onFinally);
        io.execute(task::run);
        return task;
    }

    /** 对应 {@code Disposable.dispose()}：幂等。 */
    void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        synchronized (interruptLock) {
            Thread t = worker;
            if (phase == PRODUCING && t != null && t != Thread.currentThread()) {
                t.interrupt();
            }
        }
        runFinally();
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    private void runFinally() {
        if (!finallyRan.compareAndSet(false, true)) return;
        try {
            onFinally.run();
        } catch (Throwable t) {
            Common.showLog("DownloadTask onFinally threw: " + t);
        }
    }

    private void run() {
        if (cancelled.get()) {
            // 排队期间就被取消：doFinally 已在 cancel() 里跑过，Body 不再执行。
            return;
        }
        synchronized (interruptLock) {
            worker = Thread.currentThread();
        }
        Emitter emitter = new Emitter();
        try {
            try {
                body.run(emitter);
            } catch (Throwable t) {
                // Body 自己没兜住的异常（Rx 里 create 的 lambda 抛出会走 tryOnError）。
                emitter.tryOnError(t);
            }
            // producing 结束：清掉可能残留的中断标记（线程要还给池子）。与 cancel() 同锁，
            // 保证不会有 interrupt 在这之后才落下来。
            synchronized (interruptLock) {
                phase = CONSUMING;
                Thread.interrupted();
            }
            if (cancelled.get()) {
                return;
            }
            deliver(emitter);
        } finally {
            synchronized (interruptLock) {
                phase = DONE;
                worker = null;
                Thread.interrupted();
            }
            runFinally();
        }
    }

    private void deliver(Emitter emitter) {
        List<String> values;
        Throwable error;
        synchronized (emitter) {
            values = new ArrayList<>(emitter.values);
            error = emitter.error;
        }
        for (String v : values) {
            if (cancelled.get()) return;
            try {
                onNext.accept(v);
            } catch (Throwable t) {
                // LambdaObserver：onNext 抛错 → 取消上游并转 onError。
                error = t;
                break;
            }
        }
        if (error != null && !cancelled.get()) {
            try {
                onError.accept(error);
            } catch (Throwable t) {
                Common.showLog("DownloadTask onError handler threw: " + t);
            }
        }
    }

    private final class Emitter implements DownloadEmitter {
        private final List<String> values = new ArrayList<>();
        private Throwable error;
        private boolean terminated;

        @Override
        public synchronized void onNext(String value) {
            if (isDisposed()) return;
            values.add(value);
        }

        @Override
        public synchronized void onComplete() {
            if (isDisposed()) return;
            terminated = true;
        }

        @Override
        public synchronized void onError(Throwable e) {
            if (isDisposed()) return;
            error = e;
            terminated = true;
        }

        @Override
        public void tryOnError(Throwable e) {
            onError(e);
        }

        @Override
        public boolean isDisposed() {
            return cancelled.get() || terminated;
        }
    }
}
