package ceui.lisa.core;

/**
 * 列表响应的同步后处理（屏蔽过滤 / 自动勾选等），替代原先借用的 {@code io.reactivex.functions.Function}。
 * 约定在后台线程调用（可能读 DB），调用方负责切线程。
 */
@FunctionalInterface
public interface ResponseMapper<T> {
    T apply(T t);
}
