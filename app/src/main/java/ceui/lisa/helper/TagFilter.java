package ceui.lisa.helper;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.MuteEntity;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.TagsBean;

public class TagFilter {

    public static boolean judge(IllustsBean illustsBean) {
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
