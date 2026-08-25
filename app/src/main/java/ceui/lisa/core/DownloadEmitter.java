package ceui.lisa.core;

/**
 * 传输 Body 向 {@link DownloadTask} 报告事件的口子，语义对齐 RxJava 2 的
 * {@code ObservableEmitter<String>}：终态（{@link #onComplete()} / {@link #onError(Throwable)}）
 * 之后再发事件一律静默丢弃；被取消（暂停 / stopAll）或已终态时 {@link #isDisposed()} 为 true，
 * Body 看到它就该尽快退出（保留 stage 供续传）。
 */
interface DownloadEmitter {

    void onNext(String value);

    void onComplete();

    void onError(Throwable e);

    /** 与 {@link #onError} 相同：disposed 后静默丢弃，不会抛出。 */
    void tryOnError(Throwable e);

    boolean isDisposed();
}
