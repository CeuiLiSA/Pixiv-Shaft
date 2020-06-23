package ceui.lisa.core;

import ceui.lisa.helper.TagFilter;
import ceui.lisa.interfaces.ListShow;
import ceui.lisa.models.IllustsBean;
import io.reactivex.functions.Function;

/**
 * 默认Mapper，从列表中隐藏掉包含“已屏蔽tag”的作品
 * @param <T>
 */
public class Mapper<T extends ListShow<?>> implements Function<T, T> {

    @Override
    public T apply(T t) {
        for (Object o : t.getList()) {
            if (o instanceof IllustsBean) {
                TagFilter.judge(((IllustsBean) o));
            }
        }
        return t;
    }
}
