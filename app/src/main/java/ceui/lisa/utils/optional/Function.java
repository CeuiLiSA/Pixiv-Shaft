package ceui.lisa.utils.optional;

import java.util.Objects;

public interface Function<T, R> {


    R apply(T t);


    default <V> java.util.function.Function<V, R> compose(Function<? super V, ? extends T> before) {
        Objects.requireNonNull(before);
        return (V v) -> apply(before.apply(v));
    }

    default <V> java.util.function.Function<T, V> andThen(Function<? super R, ? extends V> after) {
        Objects.requireNonNull(after);
        return (T t) -> after.apply(apply(t));
    }

    static <T> java.util.function.Function<T, T> identity() {
        return t -> t;
    }
}
