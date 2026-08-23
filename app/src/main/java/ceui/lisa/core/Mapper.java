package ceui.lisa.core;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.helper.IllustNovelFilter;
import ceui.lisa.interfaces.ListShow;
import ceui.lisa.model.ListTrendingtag;
import ceui.loxia.Illust;
import ceui.loxia.Novel;
import ceui.loxia.ObjectPool;
import io.reactivex.functions.Function;

/**
 * 默认Mapper，从列表中隐藏掉包含“已屏蔽tag”的作品
 * @param <T>
 */
public class Mapper<T extends ListShow<?>> implements Function<T, T> {

    private boolean skipR18Filter = false;

    /**
     * 搜索「R-18 限制」三档客户端过滤：0=不限、1=仅安全(去掉 R18)、2=仅 R-18(去掉全年龄)。
     * 默认 0 对其它所有列表无副作用——只有搜索 repo 经 {@link #setSearchR18Restriction} 显式开启。
     * 判定只看作者显式的 x_restrict（{@link Illust#isR18File()}，1=R-18/2=R-18G 都算 R18），
     * 不碰 sanity_level，避免把没打 R18 标记的普通(含轻微敏感)作品误删。
     */
    private int searchR18Restriction = 0;

    /**
     * 搜索「仅看 AI」客户端过滤（issue #909）：true 时只留下 AI 生成作品
     * （插画 {@link Illust#isCreatedByAI()} / 小说 {@link Novel#isCreatedByAI()}，
     * 即 ai_type==2），其余剔除。默认 false 对其它所有列表无副作用——只有搜索 repo 经
     * {@link #setSearchOnlyAi} 显式开启。屏蔽 AI 仍走全局 {@link Shaft.sSettings#isDeleteAIIllust()}。
     */
    private boolean searchOnlyAi = false;

    public Mapper<T> enableSkipR18Filter() {
        this.skipR18Filter = true;
        return this;
    }

    public Mapper<T> setSearchR18Restriction(int searchR18Restriction) {
        this.searchR18Restriction = searchR18Restriction;
        return this;
    }

    public Mapper<T> setSearchOnlyAi(boolean searchOnlyAi) {
        this.searchOnlyAi = searchOnlyAi;
        return this;
    }

    /** 该作品是否被搜索 R18 三档拒掉（isR18 = x_restrict > 0）。不限档恒不拒。 */
    private boolean searchR18Rejects(boolean isR18) {
        if (searchR18Restriction == 1) return isR18;    // 仅安全：R18 全去掉
        if (searchR18Restriction == 2) return !isR18;   // 仅 R-18：全年龄全去掉
        return false;
    }

    @Override
    public T apply(T t) {
        List<Object> dash = new ArrayList<>();
        boolean shouldHidAiIllusts = Shaft.sSettings.isDeleteAIIllust();
        for (Object o : t.getList()) {
            if (o instanceof Illust) {
                Illust illust = (Illust) o;
                if (!Boolean.TRUE.equals(illust.getVisible())) {
                    dash.add(o);
                    continue;
                }
                boolean isTagBanned = IllustNovelFilter.judgeTag(illust);
                boolean isIdBanned = IllustNovelFilter.judgeID(illust);
                boolean isUserBanned = IllustNovelFilter.judgeUserID(illust);
                boolean isR18FilterBanned = !skipR18Filter && IllustNovelFilter.judgeR18Filter(illust);
                boolean isCreatedByAI = illust.isCreatedByAI();
                if (isTagBanned || isIdBanned || isUserBanned || isR18FilterBanned
                        || searchR18Rejects(illust.isR18File())
                        || (searchOnlyAi && !isCreatedByAI)) {   // 仅看 AI：剔除非 AI 作品
                    dash.add(o);
                }
                // 全局屏蔽 AI（首页等所有列表共用）；但搜索「仅看 AI」时必须让步——否则全局 hide
                // 把 AI 去掉、searchOnlyAi 又把非 AI 去掉，结果会被清空。!searchOnlyAi 仅搜索时为真。
                if (shouldHidAiIllusts && isCreatedByAI && !searchOnlyAi) {
                    dash.add(o);
                }
                ObjectPool.INSTANCE.updateIllust((Illust) o);
            }
            if (o instanceof Novel) {
                Novel novel = (Novel) o;
                boolean isTagBanned = IllustNovelFilter.judgeTag(novel);
                boolean isIdBanned = IllustNovelFilter.judgeID(novel);
                boolean isUserBanned = IllustNovelFilter.judgeUserID(novel);
                boolean isR18FilterBanned = !skipR18Filter && IllustNovelFilter.judgeR18Filter(novel);
                // 小说专属：正文字数区间 + 超长标签名自动屏蔽（issue #743）。插画分支不挂。
                boolean isSpamBanned = IllustNovelFilter.judgeNovelSpam(novel);
                if (isTagBanned || isIdBanned || isUserBanned || isR18FilterBanned || isSpamBanned
                        || searchR18Rejects(novel.getX_restrict() != null && novel.getX_restrict() > 0)
                        || (searchOnlyAi && !novel.isCreatedByAI())) {   // 仅看 AI：剔除非 AI 小说
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
