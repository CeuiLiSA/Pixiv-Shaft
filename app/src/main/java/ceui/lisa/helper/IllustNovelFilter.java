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
