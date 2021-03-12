package ceui.lisa.core;


public abstract class RxRunnable<T> {

    /**
     * 新线程执行
     *
     * @return 异步操作的结果
     * @throws Exception ex
     */
    public abstract T execute() throws Exception;

    /**
     * 主线程执行
     */
    public void beforeExecute() {

    }
}
