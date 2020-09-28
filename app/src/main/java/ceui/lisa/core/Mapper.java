package ceui.lisa.core;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.helper.TagFilter;
import ceui.lisa.interfaces.ListShow;
import ceui.lisa.models.IllustsBean;
import io.reactivex.functions.Function;

/**
 * 默认Mapper，从列表中隐藏掉包含“已屏蔽tag”的作品
 * @param <T>
 */
public class Mapper<T extends ListShow<?>> implements Function<T, T> {

    private List<IllustsBean> dash = new ArrayList<>();

    @Override
    public T apply(T t) {
        for (Object o : t.getList()) {
            if (o instanceof IllustsBean) {
                boolean isBanned = TagFilter.judge(((IllustsBean) o));
                if (isBanned) {
                    dash.add((IllustsBean) o);
                }
            }
        }

        if (t.getList() != null && dash.size() != 0) {
            t.getList().removeAll(dash);
        }
        return t;
    }

    public List<IllustsBean> getDash() {
        return dash;
    }
}
