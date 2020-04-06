package ceui.lisa.helper;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.TagMuteEntity;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.MutedHistory;
import ceui.lisa.models.TagsBean;
import ceui.lisa.utils.Common;

public class TagFilter {

    public static void judge(IllustsBean illustsBean) {
        String tagString = illustsBean.getTagString();
        if (TextUtils.isEmpty(tagString)) {
            return;
        }

        List<TagsBean> temp = getMutedTags();
        for (TagsBean bean : temp) {
            String name = "*#" + bean.getName() + ",";
            if (tagString.contains(name)) {
                illustsBean.setShield(true);
                break;
            }
        }
    }

    public static List<TagsBean> getMutedTags() {
        List<TagsBean> result = new ArrayList<>();
        List<TagMuteEntity> muteEntities = AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().getAllMutedTags();
        if (muteEntities == null || muteEntities.size() == 0) {
            return result;
        }
        for (TagMuteEntity muteEntity : muteEntities) {
            TagsBean bean = Shaft.sGson.fromJson(muteEntity.getTagJson(), TagsBean.class);
            result.add(bean);
        }
        return result;
    }
}
