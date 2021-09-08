package ceui.lisa.utils;

import android.content.res.Resources;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.models.UserBean;

public class UserFolderNameUtil {

    private static final Resources resources = Shaft.getContext().getResources();

    public static String[] USER_FOLDER_NAME_NAMES = new String[]{
            resources.getString(R.string.string_445),
            resources.getString(R.string.string_446) + "_" + resources.getString(R.string.string_447),
            resources.getString(R.string.string_447) + "_" + resources.getString(R.string.string_446),
            resources.getString(R.string.string_446),
            resources.getString(R.string.string_447),
    };

    public static String getCurrentStatusName() {
        int currentIndex = Shaft.sSettings.getSaveForSeparateAuthorStatus();
        if (currentIndex < 0 || currentIndex >= USER_FOLDER_NAME_NAMES.length) {
            currentIndex = 0;
        }
        return USER_FOLDER_NAME_NAMES[currentIndex];
    }

    public static String getFolderNameForUser(UserBean userBean) {
        int currentIndex = Shaft.sSettings.getSaveForSeparateAuthorStatus();
        if (currentIndex < 0 || currentIndex >= USER_FOLDER_NAME_NAMES.length) {
            currentIndex = 0;
        }
        switch (currentIndex) {
            case 1:
                return userBean.getName() + "_" + userBean.getId();
            case 2:
                return userBean.getId() + "_" + userBean.getName();
            case 3:
                return userBean.getName();
            case 4:
                return String.valueOf(userBean.getId());
            default:
                return "";
        }
    }
}
