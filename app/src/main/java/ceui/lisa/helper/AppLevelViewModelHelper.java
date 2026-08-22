package ceui.lisa.helper;

import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.IllustHistoryEntity;
import ceui.loxia.Illust;
import ceui.lisa.models.UserBean;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.viewmodel.AppLevelViewModel;
import ceui.loxia.User;

public class AppLevelViewModelHelper {
    /**
     * 使用给定列表数据填充应用级ViewModel
     *
     * @param list 数据源
     * @param <T>  类型
     */
    public static <T> void fill(List<T> list) {
        if (list.size() > 0) {
            if (list.get(0).getClass().equals(Illust.class)) {
                for (Illust illustsBean : (List<Illust>) list) {
                    // user 可空(loxia Illust.user 声明就是 User?)。这里过去是裸解引用,靠「调用方
                    // 交进来的 bean 都过过内容过滤链(judgeUserID 会先碰 getUser())」这个隐含契约
                    // 兜着 —— 而 IllustFeedFragment.poolableBeansOf 是 open 的,子类(如推荐页把
                    // 排行榜预览头的 bean 也交出来)明确不做内容过滤,契约就破了。破了就是主线程 NPE,
                    // 且 IllustFeedPoolSync 的 collector 会随之死掉、不再自愈。没有 user 就记不了
                    // 关注态,跳过即可,不值得为此崩一次。
                    User user = illustsBean.getUser();
                    if (user == null) {
                        continue;
                    }
                    Shaft.appViewModel.updateFollowUserStatus((int) user.getId(), getFollowUserStatus(user));
                }
            } else if (list.get(0).getClass().equals(UserPreviewsBean.class)) {
                for (UserPreviewsBean userPreviewsBean : (List<UserPreviewsBean>) list) {
                    UserBean user = userPreviewsBean.getUser();
                    if (user == null) {
                        continue;
                    }
                    Shaft.appViewModel.updateFollowUserStatus(user.getId(), getFollowUserStatus(user));
                }
            } else if (list.get(0).getClass().equals(UserBean.class)) {
                for (UserBean userBean : (List<UserBean>) list) {
                    int userId = userBean.getId();
                    int followUserStatus = getFollowUserStatus(userBean);
                    Shaft.appViewModel.updateFollowUserStatus(userId, followUserStatus);
                }
            } else if (list.get(0).getClass().equals(IllustHistoryEntity.class)) {
                for (IllustHistoryEntity entity : (List<IllustHistoryEntity>) list) {
                    // 历史是本地 JSON 反序列化来的,更没有「服务端保证有 user」这回事:
                    // 旧版本存下的、或存量被截断的 JSON 都可能还原出 null user / null bean。
                    Illust illustsBean = Shaft.sGson.fromJson(entity.getIllustJson(), Illust.class);
                    User userBean = illustsBean == null ? null : illustsBean.getUser();
                    if (userBean == null) {
                        continue;
                    }
                    Shaft.appViewModel.updateFollowUserStatus((int) userBean.getId(), getFollowUserStatus(userBean), AppLevelViewModel.UpdateMethod.IF_ABSENT);
                }
            }
        }
    }

    private static int getFollowUserStatus(UserBean user) {
        return user.isIs_followed() ? AppLevelViewModel.FollowUserStatus.FOLLOWED : AppLevelViewModel.FollowUserStatus.NOT_FOLLOW;
    }

    private static int getFollowUserStatus(User user) {
        return Boolean.TRUE.equals(user.is_followed()) ? AppLevelViewModel.FollowUserStatus.FOLLOWED : AppLevelViewModel.FollowUserStatus.NOT_FOLLOW;
    }

    public static void updateFollowUserStatus(UserBean user, int method) {
        Shaft.appViewModel.updateFollowUserStatus(user.getId(), getFollowUserStatus(user), method);
    }
}
