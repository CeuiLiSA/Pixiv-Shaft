package ceui.lisa.helper;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.MuteEntity;
import ceui.pixiv.api.model.Illust;
import ceui.loxia.Novel;
import ceui.lisa.models.TagsBean;
import ceui.pixiv.ui.common.IllustMuteStore;
import ceui.pixiv.ui.common.NovelMuteStore;
import ceui.loxia.Tag;
import ceui.loxia.User;

public class IllustNovelFilter {

    public static boolean judge(Illust illust) {
        return judgeID(illust) || judgeTag(illust) || judgeUserID(illust) ;
    }

    public static boolean judge(Novel illust) {
        return judgeID(illust) || judgeTag(illust) || judgeUserID(illust) ;
    }

    /**
     * 命中「屏蔽此作品」记录（{@code tag_mute_table} 的 type 1/2）就整条从列表里删掉。
     *
     * <p><b>只给画不出遮罩的列表用</b>——legacy {@link ceui.lisa.core.Mapper} 那批老列表、以及
     * 首页排行榜预览头那种横向条（它们走 legacy RAdapter，没有模糊层和粒子层）。feeds 框架下的
     * 插画/小说卡**不挂这条**：那边被屏蔽的作品是**遮罩**——卡片留在原位糊掉 + 盖粒子，点一下
     * 即取消屏蔽、长按菜单同样能取消（见 {@code ceui.pixiv.ui.common.MutedWorkStore}）。在条目过滤链上滤掉
     * 就等于 UI 上根本不存在这一条，取消屏蔽无处下手。改动这条界之前先看那个类的注释。
     *
     * <p>问的是 store 的内存名单而不是查库：{@link ceui.lisa.core.Mapper} 是**逐条**调这个方法的，
     * 而原先每次调用都要跑一趟 {@code SELECT * FROM tag_mute_table WHERE type = 1} —— 每行都把几 KB
     * 的作品 JSON 读出来再扔掉，一页 30 条就是 30 次全表扫。store 手里正好有一份同源的 id Set。
     * 顺带也消掉了「内存已屏蔽、异步 insert 还没落盘」这段时间里老列表与 feeds 的分歧。
     */
    public static boolean judgeID(Illust illust) {
        return IllustMuteStore.INSTANCE.isMuted(illust.getId());
    }

    /**
     * 小说版，界同上。
     *
     * <p>问的是**小说**那份名单（type=2）而不是插画的：插画 id 与小说 id 是两条互相独立的自增
     * 序列，同号完全可能。这里原本查的是插画那批，于是「屏蔽插画 #12345」会顺带让小说 #12345
     * 从列表里消失，而真正屏蔽掉的小说一篇也拦不住。
     */
    public static boolean judgeID(Novel illust) {
        return NovelMuteStore.INSTANCE.isMuted(illust.getId());
    }

    public static boolean judgeUserID(Illust illust) {
        if (illust.getUser() == null) {
            return false;
        }
        MuteEntity temp = AppDatabase.getAppDatabase(Shaft.getContext())
                .searchDao()
                .getUserMuteEntityByID(illust.getUser().getId());
        return temp != null;
    }

    public static boolean judgeUserID(Novel illust) {
        User user = illust.getUser();
        if (user == null) {
            return false;
        }
        MuteEntity temp = AppDatabase.getAppDatabase(Shaft.getContext())
                .searchDao()
                .getUserMuteEntityByID(user.getUserId());
        return temp != null;
    }

    private static boolean isAiExemptAuthor(User user) {
        if (user == null) {
            return false;
        }
        return isAiExemptAuthor(
                user.getId(),
                Shaft.sSettings.getAiBlockExemptAuthorIds(),
                Shaft.sSettings.getAiBlockExemptDisabledIds());
    }

    /**
     * 判定本体，显式传入两个集合。抽出来是为了能在裸 JVM 单测里覆盖——读 {@link Shaft#sSettings}
     * 会触发 Application 子类的类初始化，在 unit test 里直接炸。见 AiBlockExemptAuthorTest。
     *
     * <p>豁免是「在名单里 **且** 这一条没有被临时停用」：停用的 ID 仍留在名单里（省去删了再加回来），
     * 但不产生任何豁免效果。{@code userId <= 0} 一律不认。
     */
    static boolean isAiExemptAuthor(long userId, Set<Long> exemptIds, Set<Long> disabledIds) {
        if (userId <= 0) {
            return false;
        }
        return exemptIds.contains(userId) && !disabledIds.contains(userId);
    }

    /**
     * 全局「不显示 AI 生成的作品」是否命中这条作品：开关开着、作品是 AI、作者不在豁免名单。
     * 命中后按 {@link ceui.lisa.utils.Settings#getAiBlockStrength()} 分流：0=完全不显示（列表剔除）、
     * 1=模糊粒子化（feeds 卡打码；没有模糊层的老列表仍剔除，见 {@link ceui.lisa.core.Mapper}）。
     */
    /**
     * 「屏蔽 AI」是否命中这条作品：档位开着、作品是 AI、作者不在豁免名单。
     *
     * <p>[excludeAi] 由调用方给出——搜索链路会传「其他条件」里的会话临时档位（不写设置），
     * 其余调用方传全局 {@link Shaft.sSettings#isDeleteAIIllust()}。
     */
    private static boolean isAiBlocked(boolean excludeAi, Illust illust) {
        return isAiBlocked(excludeAi, illust, isAiWork(illust));
    }

    /** 同上，但「这条算不算 AI」由调用方给——一次绑定里多处要问时，标签规则只该匹配一遍。 */
    private static boolean isAiBlocked(boolean excludeAi, Illust illust, boolean aiWork) {
        return excludeAi && aiWork && !isAiExemptAuthor(illust.getUser());
    }

    private static boolean isAiBlocked(boolean excludeAi, Novel novel) {
        return excludeAi && isAiWork(novel) && !isAiExemptAuthor(novel.getUser());
    }

    /**
     * 这条作品是否算「AI 生成」——AI 判定的**唯一入口**。
     *
     * <p>两个来源等价：官方 {@code ai_type == 2}，或标签命中用户启用的 AI 标签规则
     * （issue #1252 需求 1，见 {@link AiTagRules}）。屏蔽、仅看 AI、打码都问这一个方法，
     * 不要在别处再写 {@code isCreatedByAI()}，否则标签识别只覆盖一半链路。
     *
     * <p>标签判定要读设置、逐条规范化比较，比读一个 int 贵得多，所以**调用点必须保持惰性**：
     * 先判「屏蔽 AI 是否开着」「是否仅看 AI」，再进来问；别在循环里无条件预计算。
     * {@link #isAiBlocked(boolean, Illust)} 已经把 {@code excludeAi} 放在最前面短路。
     *
     * <p>唯一例外是**展示型**调用点：AI 角标、详情页那张 AI 卡不看屏蔽开关，本来就要对每条作品
     * 问一次（见 {@code ceui.pixiv.ui.common.IllustStaggerRenderer#buildBadges}）。这类调用点
     * 不适用上面的惰性要求，但同一次绑定里只该问一遍——打码判定与角标共用一份结果，见
     * {@link #shouldBlurAi(Illust, boolean, boolean)}。
     */
    public static boolean isAiWork(Illust illust) {
        return isAiWork(illust.isCreatedByAI(), illust.getTagNames());
    }

    /** 小说版 {@link #isAiWork(Illust)}。 */
    public static boolean isAiWork(Novel novel) {
        return isAiWork(novel.isCreatedByAI(), novel.getTagNames());
    }

    /**
     * 判定本体，给「不是 [Illust] / [Novel]、但也想按同一口径打 AI 标记」的模型用
     * （例如广场缩略图 {@code StreetThumbnail}，它自带 {@code aiType} 与标签名）。
     *
     * @param officialAi 模型自己的官方 AI 标记是否命中
     * @param tagNames   作品标签名；null / 空一律不算
     */
    public static boolean isAiWork(boolean officialAi, List<String> tagNames) {
        if (officialAi) {
            return true;
        }
        if (tagNames == null || tagNames.isEmpty()) {
            return false;
        }
        Set<String> disabled = Shaft.sSettings.getAiTagDisabledTags();
        Set<String> userTags = Shaft.sSettings.getAiTagUserTags();
        // 规则全关（内置全停用、也没有启用的自定义标签）时标签匹配没有意义：直接早退，别为每条
        // 作品逐条规范化比较。角标、打码、屏蔽、仅看 AI 最后都走到这里。
        if (!AiTagRules.hasEnabledRule(disabled, userTags)) {
            return false;
        }
        return AiTagRules.hasAiTag(tagNames, disabled, userTags);
    }

    /** 屏蔽 AI 强度 = 完全不显示时，是否应该把这条插画从列表里剔除（豁免作者除外）。 */
    public static boolean shouldHideAi(Illust illust) {
        return shouldHideAi(illust, Shaft.sSettings.isDeleteAIIllust());
    }

    /**
     * 档位显式传入的版本。搜索链路传「其他条件」的会话临时档位，这样临时「屏蔽 AI」不写全局
     * 设置也能生效；其余调用方走无参版（读全局）。强度仍读全局——它只决定命中后是「剔除」还是
     * 「打码」，不是筛选条件本身。
     */
    public static boolean shouldHideAi(Illust illust, boolean excludeAi) {
        return isAiBlocked(excludeAi, illust)
                && Shaft.sSettings.getAiBlockStrength() == 0;
    }

    /** 屏蔽 AI 强度 = 模糊粒子化时，是否应该把这条插画在卡片上打码（豁免作者除外）。 */
    public static boolean shouldBlurAi(Illust illust) {
        return shouldBlurAi(illust, Shaft.sSettings.isDeleteAIIllust());
    }

    /** {@link #shouldHideAi(Illust, boolean)} 的「模糊粒子化」版。 */
    public static boolean shouldBlurAi(Illust illust, boolean excludeAi) {
        return isAiBlocked(excludeAi, illust)
                && Shaft.sSettings.getAiBlockStrength() == 1;
    }

    /**
     * 「AI 判定已经算过」的版本：{@code aiWork} 传 {@link #isAiWork(Illust)} 的结果。
     *
     * <p>给一次绑定里既判打码、又要画 AI 角标的渲染器用（见
     * {@code ceui.pixiv.ui.common.IllustStaggerRenderer}）：标签匹配要逐条规范化比较，是这条
     * 链路上最贵的一步，同一张卡在一次 bind 里问两遍纯属浪费。语义与
     * {@link #shouldBlurAi(Illust, boolean)} 完全一致——豁免作者与强度档位照旧现读，只有
     * 「算不算 AI」这一项由调用方给。
     */
    public static boolean shouldBlurAi(Illust illust, boolean excludeAi, boolean aiWork) {
        return isAiBlocked(excludeAi, illust, aiWork)
                && Shaft.sSettings.getAiBlockStrength() == 1;
    }

    /** 小说版：完全不显示强度下是否剔除（豁免作者除外）。 */
    public static boolean shouldHideAi(Novel novel) {
        return shouldHideAi(novel, Shaft.sSettings.isDeleteAIIllust());
    }

    /** 小说版 {@link #shouldHideAi(Illust, boolean)}。 */
    public static boolean shouldHideAi(Novel novel, boolean excludeAi) {
        return isAiBlocked(excludeAi, novel)
                && Shaft.sSettings.getAiBlockStrength() == 0;
    }

    /** 小说版：模糊粒子化强度下是否打码（豁免作者除外）。 */
    public static boolean shouldBlurAi(Novel novel) {
        return shouldBlurAi(novel, Shaft.sSettings.isDeleteAIIllust());
    }

    /** 小说版 {@link #shouldBlurAi(Illust, boolean)}。 */
    public static boolean shouldBlurAi(Novel novel, boolean excludeAi) {
        return isAiBlocked(excludeAi, novel)
                && Shaft.sSettings.getAiBlockStrength() == 1;
    }

    public static boolean judgeTag(Illust illustsBean) {
        String tagString = illustsBean.getTagString();
        if (TextUtils.isEmpty(tagString)) {
            return false;
        }
        return matchesMutedTags(tagString, getMutedTags());
    }

    public static boolean judgeTag(Novel illustsBean) {
        String tagString = illustsBean.getTagString();
        if (TextUtils.isEmpty(tagString)) {
            return false;
        }
        return matchesMutedTags(tagString, getMutedTags());
    }

    /**
     * 单个标签名是否命中「屏蔽标签」规则。给只有标签、没有作品的地方用——热门标签页、搜索页的
     * 热门标签行（pixez#1182：屏蔽过的 tag 仍然摆在推荐标签里，一点就搜出来）。
     *
     * <p>口径与 {@link #judgeTag(Illust)} 完全一致：把这一个名字拼成同款 {@code *#name,} 串再过
     * 同一套规则——普通模式按整名匹配，正则模式在串上 find，未生效的规则不参与。
     *
     * <p>{@code mutedTags} 由调用方取一次 {@link #getMutedTags()} 传进来：过滤一整列标签时不必每个名字查一遍库。
     */
    public static boolean isTagNameMuted(String tagName, List<TagsBean> mutedTags) {
        if (tagName == null || tagName.isEmpty()) {
            return false;
        }
        return matchesMutedTags("*#" + tagName + ",", mutedTags);
    }

    /** 标签串（{@code *#a,*#b,} 格式）是否命中任一条生效中的屏蔽规则。 */
    static boolean matchesMutedTags(String tagString, List<TagsBean> mutedTags) {
        for (TagsBean bean : mutedTags) {
            if (bean.isEffective()) {
                String name = "*#" + bean.getName() + ",";
                if (bean.getFilter_mode() == 0 && tagString.contains(name)) {
                    return true;
                } else if (bean.getFilter_mode() == 1 && Pattern.compile(bean.getName()).matcher(tagString).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 全局「R-18 过滤」（设置页默认项 / 长按头像临时切换）是否命中这条插画。
     *
     * <p>两条路径任一命中即算：分级字段 {@link Illust#isR18File()}（{@code x_restrict} 1=R-18、2=R-18G），
     * 或标签串里有字面 {@code R-18} / {@code R-18G}。
     *
     * <p><b>只认标签会漏人。</b>pixiv 允许作者只勾「閲覧制限」而不加 R-18 标签，服务端照样按 R-18 下发
     * （{@code sanity_level} 同时抬到 6）。这类作品在卡片角标、详情页「制限」栏、搜索「其他条件 → 仅安全」
     * 里都算 R-18，只有这里漏——于是出现「卡上挂着 R-18 角标，却被 R-18 过滤放行」。
     * 实例 #151031908：x_restrict=1、sanity_level=6、tags 里没有 R-18（作者设了登录可见 + R 级）。
     *
     * <p>标签那条仍然保留：网页搜索来的小说不一定带 {@code x_restrict}，历史缓存 JSON 也可能缺字段。
     *
     * <p>不碰 {@code sanity_level}（与 {@link ceui.lisa.core.Mapper} 同口径）：sanity 4 是 R-15 级敏感，
     * 不该被「R-18 过滤」误杀。
     */
    public static boolean judgeR18Filter(Illust illustsBean) {
        return Shaft.sSettings.isR18FilterTempEnable() && hitsR18Filter(illustsBean);
    }

    /** 小说版，口径同上：{@code x_restrict} 或字面 R-18 标签。 */
    public static boolean judgeR18Filter(Novel illustsBean) {
        return Shaft.sSettings.isR18FilterTempEnable() && hitsR18Filter(illustsBean);
    }

    /**
     * 「这条作品算不算 R-18」——不看开关，只回答事实。
     *
     * <p>给需要把 R-18 从「丢掉」改成「按开关临时隐藏」的列表用：那边要能在开关翻转后按条目自身的
     * 字段重新算一次显隐，而 {@link #judgeR18Filter(Illust)} 把开关一起揉在返回值里，关着的时候
     * 拿不到「它本来是 R-18」这个事实。
     *
     * <p>口径与 {@link #judgeR18Filter(Illust)} 完全一致（同一个方法体，不许分叉）。
     */
    public static boolean hitsR18Filter(Illust illust) {
        return isR18Rated(illust) || hitsR18Tag(illust == null ? null : illust.getTagString());
    }

    /** 小说版 {@link #hitsR18Filter(Illust)}。 */
    public static boolean hitsR18Filter(Novel novel) {
        return isR18Rated(novel) || hitsR18Tag(novel == null ? null : novel.getTagString());
    }

    /** 分级字段判定：{@code x_restrict} 1/2 都算 R18（同 {@link Illust#isR18File()}）。 */
    static boolean isR18Rated(Illust illust) {
        return illust != null && illust.isR18File();
    }

    /** 小说版分级字段判定。 */
    static boolean isR18Rated(Novel novel) {
        return novel != null && novel.getX_restrict() != null && novel.getX_restrict() > 0;
    }

    /** 标签串（{@code *#a,*#b,} 格式）里是否有字面 R-18 / R-18G。 */
    static boolean hitsR18Tag(String tagString) {
        if (tagString == null) {
            return false;
        }
        return tagString.contains("*#R-18,") || tagString.contains("*#R-18G,");
    }

    // ── 小说自动屏蔽：正文字数区间 + 超长标签名（issue #743）────────────────
    //
    // 反复开小号换 tag 刷广告的，靠「屏蔽 tag / 屏蔽画师」永远追不上——换个号换个 tag 就绕过去了。
    // 但这类东西有两个稳定特征：正文极短（几十字的广告位），以及把整句招揽话术塞进 tag 名。
    // 这里按这两个特征做阈值屏蔽，三个阈值各自 0 = 关闭，只挂在小说列表上，插画/漫画不走这条。

    /**
     * 正文字数是否落在被屏蔽区间外。{@code textLength <= 0} 视为「拿不到字数」，一律放行——
     * 宁可漏杀也不能因为接口没返字段就把正常作品吃掉。两个阈值各自 {@code <= 0} 表示没开。
     */
    private static boolean rejectsByTextLength(int textLength, int minLength, int maxLength) {
        if (textLength <= 0) {
            return false;
        }
        if (minLength > 0 && textLength < minLength) {
            return true;
        }
        return maxLength > 0 && textLength > maxLength;
    }

    /** 单个标签名是否超过长度上限；{@code maxTagNameLength <= 0} 表示这条没开。 */
    private static boolean tagNameTooLong(String name, int maxTagNameLength) {
        return maxTagNameLength > 0 && name != null && name.length() > maxTagNameLength;
    }

    public static boolean judgeNovelSpam(Novel novel) {
        return judgeNovelSpam(
                novel,
                Shaft.sSettings.getNovelFilterMinTextLength(),
                Shaft.sSettings.getNovelFilterMaxTextLength(),
                Shaft.sSettings.getNovelFilterMaxTagNameLength());
    }

    /**
     * 阈值显式传入的判定本体。抽出这层是为了能在裸 JVM 单测里覆盖——读 {@link Shaft#sSettings}
     * 会触发 Application 子类的类初始化，在 unit test 里直接炸。见 NovelSpamFilterTest。
     */
    static boolean judgeNovelSpam(Novel novel, int minLength, int maxLength, int maxTagNameLength) {
        Integer textLength = novel.getText_length();
        if (rejectsByTextLength(textLength == null ? 0 : textLength, minLength, maxLength)) {
            return true;
        }
        List<Tag> tags = novel.getTags();
        if (maxTagNameLength <= 0 || tags == null) {
            return false;
        }
        for (Tag tag : tags) {
            if (tagNameTooLong(tag.getName(), maxTagNameLength)) {
                return true;
            }
        }
        return false;
    }

    public static List<TagsBean> getMutedTags() {
        List<TagsBean> result = new ArrayList<>();
        List<MuteEntity> muteEntities = AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().getAllMutedTags();
        if (muteEntities == null || muteEntities.isEmpty()) {
            return result;
        }
        for (MuteEntity muteEntity : muteEntities) {
            TagsBean bean = Shaft.sGson.fromJson(muteEntity.getTagJson(), TagsBean.class);
            result.add(bean);
        }
        return result;
    }

    public static List<MuteEntity> getMutedWorks() {
        return AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().getMutedWorks();
    }
}
