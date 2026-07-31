package ceui.lisa.helper;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.MuteEntity;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.NovelBean;
import ceui.lisa.models.TagsBean;
import ceui.lisa.utils.Common;
import ceui.loxia.Novel;
import ceui.loxia.Tag;
import ceui.loxia.User;

public class IllustNovelFilter {

    public static boolean judge(IllustsBean illust) {
        return judgeID(illust) || judgeTag(illust) || judgeUserID(illust) ;
    }

    public static boolean judge(NovelBean illust) {
        return judgeID(illust) || judgeTag(illust) || judgeUserID(illust) ;
    }

    public static boolean judgeID(IllustsBean illust) {
        List<MuteEntity> temp = AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().getMutedIllusts();
        boolean isBanned = false;
        if (!Common.isEmpty(temp)) {
            for (MuteEntity muteEntity : temp) {
                if (muteEntity.getId() == illust.getId()) {
                    isBanned = true;
                    break;
                }
            }
        }
        return isBanned;
    }

    public static boolean judgeID(NovelBean illust) {
        List<MuteEntity> temp = AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().getMutedIllusts();
        boolean isBanned = false;
        if (!Common.isEmpty(temp)) {
            for (MuteEntity muteEntity : temp) {
                if (muteEntity.getId() == illust.getId()) {
                    isBanned = true;
                    break;
                }
            }
        }
        return isBanned;
    }

    public static boolean judgeUserID(IllustsBean illust) {
        MuteEntity temp = AppDatabase.getAppDatabase(Shaft.getContext())
                .searchDao()
                .getUserMuteEntityByID(illust.getUser().getUserId());
        return temp != null;
    }

    public static boolean judgeUserID(NovelBean illust) {
        MuteEntity temp = AppDatabase.getAppDatabase(Shaft.getContext())
                .searchDao()
                .getUserMuteEntityByID(illust.getUser().getUserId());
        return temp != null;
    }

    public static boolean judgeTag(IllustsBean illustsBean) {
        String tagString = illustsBean.getTagString();
        if (TextUtils.isEmpty(tagString)) {
            return false;
        }

        List<TagsBean> temp = getMutedTags();
        for (TagsBean bean : temp) {
            if (bean.isEffective()) {
                String name = "*#" + bean.getName() + ",";
                if (bean.getFilter_mode() == 0 && tagString.contains(name)) {
                    illustsBean.setShield(true);
                    return true;
                } else if (bean.getFilter_mode() == 1 && Pattern.compile(bean.getName()).matcher(tagString).find()) {
                    illustsBean.setShield(true);
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean judgeTag(NovelBean illustsBean) {
        String tagString = illustsBean.getTagString();
        if (TextUtils.isEmpty(tagString)) {
            return false;
        }

        List<TagsBean> temp = getMutedTags();
        for (TagsBean bean : temp) {
            if (bean.isEffective()) {
                String name = "*#" + bean.getName() + ",";
                if (bean.getFilter_mode() == 0 && tagString.contains(name)) {
//                    illustsBean.setShield(true);
                    return true;
                } else if (bean.getFilter_mode() == 1 && Pattern.compile(bean.getName()).matcher(tagString).find()) {
//                    illustsBean.setShield(true);
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean judgeR18Filter(IllustsBean illustsBean) {
        if (!Shaft.sSettings.isR18FilterTempEnable()) {
            return false;
        }
        String tagString = illustsBean.getTagString();
        boolean isHit = tagString.contains("*#R-18,") || tagString.contains("*#R-18G,");
        illustsBean.setShield(isHit);
        return isHit;
    }

    public static boolean judgeR18Filter(NovelBean illustsBean) {
        if (!Shaft.sSettings.isR18FilterTempEnable()) {
            return false;
        }
        String tagString = illustsBean.getTagString();
        boolean isHit = tagString.contains("*#R-18,") || tagString.contains("*#R-18G,");
//        illustsBean.setShield(isHit);
        return isHit;
    }

    // ── loxia Novel 版判定 ────────────────────────────────────────────────

    /** loxia Novel 无 getTagString(),这里按 NovelBean 同款格式 `*#name,` 现拼一遍。 */
    private static String novelTagString(Novel novel) {
        List<Tag> tags = novel.getTags();
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Tag tag : tags) {
            sb.append("*#").append(tag.getName()).append(",");
        }
        return sb.toString();
    }

    public static boolean judge(Novel novel) {
        return judgeID(novel) || judgeTag(novel) || judgeUserID(novel);
    }

    public static boolean judgeID(Novel novel) {
        List<MuteEntity> temp = AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().getMutedIllusts();
        if (!Common.isEmpty(temp)) {
            for (MuteEntity muteEntity : temp) {
                if (muteEntity.getId() == novel.getId()) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean judgeUserID(Novel novel) {
        User user = novel.getUser();
        if (user == null) {
            return false;
        }
        MuteEntity temp = AppDatabase.getAppDatabase(Shaft.getContext())
                .searchDao()
                .getUserMuteEntityByID((int) user.getId());
        return temp != null;
    }

    public static boolean judgeTag(Novel novel) {
        String tagString = novelTagString(novel);
        if (TextUtils.isEmpty(tagString)) {
            return false;
        }
        List<TagsBean> temp = getMutedTags();
        for (TagsBean bean : temp) {
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

    public static boolean judgeR18Filter(Novel novel) {
        if (!Shaft.sSettings.isR18FilterTempEnable()) {
            return false;
        }
        String tagString = novelTagString(novel);
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

    public static boolean judgeNovelSpam(NovelBean novel) {
        return judgeNovelSpam(
                novel,
                Shaft.sSettings.getNovelFilterMinTextLength(),
                Shaft.sSettings.getNovelFilterMaxTextLength(),
                Shaft.sSettings.getNovelFilterMaxTagNameLength());
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
    static boolean judgeNovelSpam(NovelBean novel, int minLength, int maxLength, int maxTagNameLength) {
        if (rejectsByTextLength(novel.getText_length(), minLength, maxLength)) {
            return true;
        }
        List<TagsBean> tags = novel.getTags();
        if (maxTagNameLength <= 0 || tags == null) {
            return false;
        }
        for (TagsBean tag : tags) {
            if (tagNameTooLong(tag.getName(), maxTagNameLength)) {
                return true;
            }
        }
        return false;
    }

    /** loxia {@link Novel} 版，判定必须与上面的 {@link NovelBean} 重载逐条一致。 */
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
