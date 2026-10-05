package ceui.lisa.core;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.helper.IllustNovelFilter;
import ceui.lisa.interfaces.ListShow;
import ceui.lisa.model.ListTrendingtag;
import ceui.pixiv.api.model.Illust;
import ceui.loxia.Novel;
import ceui.pixiv.cache.ObjectPool;

/**
 * 默认Mapper，从列表中隐藏掉包含“已屏蔽tag”的作品
 * @param <T>
 */
public class Mapper<T extends ListShow<?>> implements ResponseMapper<T> {

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
     * {@link #setSearchOnlyAi} 显式开启。
     */
    private boolean searchOnlyAi = false;

    /**
     * 搜索链路「屏蔽 AI」的有效档位（可能来自搜索页「其他条件」的会话临时值，不写全局设置）。
     * null = 用全局 {@link Shaft.sSettings#isDeleteAIIllust()}。默认 null 对其它所有列表无副作用——
     * 只有搜索 repo 经 {@link #setSearchExcludeAi} 显式写入。
     */
    private Boolean searchExcludeAi = null;

    /** 搜索链路在模糊粒子化强度下需要保留 AI 条目交给 feeds 卡打码；老列表没有模糊层，默认仍剔除。 */
    private boolean keepAiForBlur = false;

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

    /** 显式指定「屏蔽 AI」档位（搜索页的会话临时值）；不调则沿用全局设置。 */
    public Mapper<T> setSearchExcludeAi(boolean searchExcludeAi) {
        this.searchExcludeAi = searchExcludeAi;
        return this;
    }

    /** 搜索链路的有效「屏蔽 AI」档位：显式值优先，否则读全局设置。 */
    private boolean effectiveExcludeAi() {
        return searchExcludeAi != null ? searchExcludeAi : Shaft.sSettings.isDeleteAIIllust();
    }

    public Mapper<T> setKeepAiForBlur(boolean keepAiForBlur) {
        this.keepAiForBlur = keepAiForBlur;
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
        // 有效「屏蔽 AI」档位：一次算好，循环里逐条判定用（会话临时值优先，否则读全局设置）
        boolean excludeAi = effectiveExcludeAi();
        // feeds 卡打码（IllustNovelFilter.shouldBlurAi 无参版）只认全局开关。有效档位来自搜索页的
        // 会话临时「屏蔽 AI」、全局却没开时，卡片不会打码——这时还「保留给卡片打码」就等于没屏蔽，
        // 只能按老列表口径剔除（同系列卡）。
        boolean keepForBlur = keepAiForBlur && Shaft.sSettings.isDeleteAIIllust();
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
                // 屏蔽 AI（首页等所有列表共用全局设置；搜索链路可传「其他条件」的会话临时档位）。
                // 但搜索「仅看 AI」时必须让步——否则屏蔽把 AI 去掉、searchOnlyAi 又把非 AI 去掉，
                // 结果会被清空。!searchOnlyAi 仅搜索时为真。
                // 完全不显示强度才剔除；模糊粒子化强度下老列表没有模糊层，同样剔除，只有搜索链路
                // 显式 keepAiForBlur 时保留给 feeds 卡打码；豁免作者一律放行。
                if (!searchOnlyAi && (IllustNovelFilter.shouldHideAi(illust, excludeAi)
                        || (!keepForBlur && IllustNovelFilter.shouldBlurAi(illust, excludeAi)))) {
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
                // 屏蔽 AI 的小说侧（与插画分支同口径）：完全不显示强度才剔除；
                // 模糊粒子化强度下老列表没有模糊层，同样剔除，只有搜索链路 keepAiForBlur 时保留。
                if (!searchOnlyAi && (IllustNovelFilter.shouldHideAi(novel, excludeAi)
                        || (!keepForBlur && IllustNovelFilter.shouldBlurAi(novel, excludeAi)))) {
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
