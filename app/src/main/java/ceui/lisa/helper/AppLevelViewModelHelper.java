package ceui.lisa.helper;

import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.IllustHistoryEntity;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.UserBean;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.viewmodel.AppLevelViewModel;

public class AppLevelViewModelHelper {
    /**
     * 使用给定列表数据填充应用级ViewModel
     *
     * @param list 数据源
     * @param <T>  类型
     */
    public static <T> void fill(List<T> list) {
        if (list.size() > 0) {
            if (list.get(0).getClass().equals(IllustsBean.class)) {
                for (IllustsBean illustsBean : (List<IllustsBean>) list) {
                    int userId = illustsBean.getUser().getId();
                    int followUserStatus = getFollowUserStatus(illustsBean.getUser());
                    Shaft.appViewModel.updateFollowUserStatus(userId, followUserStatus);
                }
            } else if (list.get(0).getClass().equals(UserPreviewsBean.class)) {
                for (UserPreviewsBean userPreviewsBean : (List<UserPreviewsBean>) list) {
                    int userId = userPreviewsBean.getUser().getId();
                    int followUserStatus = getFollowUserStatus(userPreviewsBean.getUser());
                    Shaft.appViewModel.updateFollowUserStatus(userId, followUserStatus);
                }
            } else if (list.get(0).getClass().equals(UserBean.class)) {
                for (UserBean userBean : (List<UserBean>) list) {
                    int userId = userBean.getId();
                    int followUserStatus = getFollowUserStatus(userBean);
                    Shaft.appViewModel.updateFollowUserStatus(userId, followUserStatus);
                }
            } else if (list.get(0).getClass().equals(IllustHistoryEntity.class)) {
                for (IllustHistoryEntity entity : (List<IllustHistoryEntity>) list) {
                    IllustsBean illustsBean = Shaft.sGson.fromJson(entity.getIllustJson(), IllustsBean.class);
                    UserBean userBean = illustsBean.getUser();
                    int userId = userBean.getId();
                    int followUserStatus = getFollowUserStatus(userBean);
                    Shaft.appViewModel.updateFollowUserStatus(userId, followUserStatus, AppLevelViewModel.UpdateMethod.IF_ABSENT);
                }
            }
        }
    }

    private static int getFollowUserStatus(UserBean user) {
        return user.isIs_followed() ? AppLevelViewModel.FollowUserStatus.FOLLOWED : AppLevelViewModel.FollowUserStatus.NOT_FOLLOW;
    }

    public static void updateFollowUserStatus(UserBean user, int method) {
        Shaft.appViewModel.updateFollowUserStatus(user.getId(), getFollowUserStatus(user), method);
    }
}
