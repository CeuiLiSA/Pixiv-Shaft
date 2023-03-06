package ceui.lisa.core;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.helper.IllustNovelFilter;
import ceui.lisa.interfaces.ListShow;
import ceui.lisa.model.ListTrendingtag;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.NovelBean;
import ceui.loxia.ObjectPool;
import io.reactivex.functions.Function;

/**
 * 默认Mapper，从列表中隐藏掉包含“已屏蔽tag”的作品
 * @param <T>
 */
public class Mapper<T extends ListShow<?>> implements Function<T, T> {

    @Override
    public T apply(T t) {
        List<Object> dash = new ArrayList<>();
        for (Object o : t.getList()) {
            if (o instanceof IllustsBean) {
                boolean isTagBanned = IllustNovelFilter.judgeTag((IllustsBean) o);
                boolean isIdBanned = IllustNovelFilter.judgeID((IllustsBean) o);
                boolean isUserBanned = IllustNovelFilter.judgeUserID((IllustsBean) o);
                boolean isR18FilterBanned = IllustNovelFilter.judgeR18Filter((IllustsBean) o);
                if (isTagBanned || isIdBanned || isUserBanned || isR18FilterBanned) {
                    dash.add(o);
                }
                ObjectPool.INSTANCE.updateIllust((IllustsBean) o);
            }
            if (o instanceof NovelBean) {
                boolean isTagBanned = IllustNovelFilter.judgeTag((NovelBean) o);
                boolean isIdBanned = IllustNovelFilter.judgeID((NovelBean) o);
                boolean isUserBanned = IllustNovelFilter.judgeUserID((NovelBean) o);
                boolean isR18FilterBanned = IllustNovelFilter.judgeR18Filter((NovelBean) o);
                if (isTagBanned || isIdBanned || isUserBanned || isR18FilterBanned) {
                    dash.add(o);
                }
            }
        }

        if (t.getList() != null && dash.size() != 0) {
            t.getList().removeAll(dash);
        }
        return t;
    }
}
