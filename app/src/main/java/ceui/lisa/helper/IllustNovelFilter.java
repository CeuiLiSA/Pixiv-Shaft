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
    private static boolean isAiBlocked(boolean excludeAi, boolean createdByAi, User user) {
        return excludeAi && createdByAi && !isAiExemptAuthor(user);
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
        return isAiBlocked(excludeAi, illust.isCreatedByAI(), illust.getUser())
                && Shaft.sSettings.getAiBlockStrength() == 0;
    }

    /** 屏蔽 AI 强度 = 模糊粒子化时，是否应该把这条插画在卡片上打码（豁免作者除外）。 */
    public static boolean shouldBlurAi(Illust illust) {
        return shouldBlurAi(illust, Shaft.sSettings.isDeleteAIIllust());
    }

    /** {@link #shouldHideAi(Illust, boolean)} 的「模糊粒子化」版。 */
    public static boolean shouldBlurAi(Illust illust, boolean excludeAi) {
        return isAiBlocked(excludeAi, illust.isCreatedByAI(), illust.getUser())
                && Shaft.sSettings.getAiBlockStrength() == 1;
    }

    /** 小说版：完全不显示强度下是否剔除（豁免作者除外）。 */
    public static boolean shouldHideAi(Novel novel) {
        return shouldHideAi(novel, Shaft.sSettings.isDeleteAIIllust());
    }

    /** 小说版 {@link #shouldHideAi(Illust, boolean)}。 */
    public static boolean shouldHideAi(Novel novel, boolean excludeAi) {
        return isAiBlocked(excludeAi, novel.isCreatedByAI(), novel.getUser())
                && Shaft.sSettings.getAiBlockStrength() == 0;
    }

    /** 小说版：模糊粒子化强度下是否打码（豁免作者除外）。 */
    public static boolean shouldBlurAi(Novel novel) {
        return shouldBlurAi(novel, Shaft.sSettings.isDeleteAIIllust());
    }

    /** 小说版 {@link #shouldBlurAi(Illust, boolean)}。 */
    public static boolean shouldBlurAi(Novel novel, boolean excludeAi) {
        return isAiBlocked(excludeAi, novel.isCreatedByAI(), novel.getUser())
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

    public static boolean judgeR18Filter(Illust illustsBean) {
        if (!Shaft.sSettings.isR18FilterTempEnable()) {
            return false;
        }
        String tagString = illustsBean.getTagString();
        boolean isHit = tagString.contains("*#R-18,") || tagString.contains("*#R-18G,");
        return isHit;
    }

    public static boolean judgeR18Filter(Novel illustsBean) {
        if (!Shaft.sSettings.isR18FilterTempEnable()) {
            return false;
        }
        String tagString = illustsBean.getTagString();
        boolean isHit = tagString.contains("*#R-18,") || tagString.contains("*#R-18G,");
//        illustsBean.setShield(isHit);
        return isHit;
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
        if (muteEntities == null || muteEntities.size() == 0) {
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
