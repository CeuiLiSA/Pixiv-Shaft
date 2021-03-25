package ceui.lisa.helper;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.MuteEntity;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.TagsBean;
import ceui.lisa.utils.Common;

public class IllustFilter {

    public static boolean judge(IllustsBean illust) {
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

    public static boolean judgeUserID(IllustsBean illust) {
        MuteEntity temp = AppDatabase.getAppDatabase(Shaft.getContext())
                .searchDao()
                .getMuteEntityByID(illust.getUser().getUserId());
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
                if (tagString.contains(name)) {
                    illustsBean.setShield(true);
                    return true;
                }
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
