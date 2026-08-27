package ceui.lisa.helper;

import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.IllustHistoryEntity;
import ceui.loxia.Illust;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.viewmodel.AppLevelState;
import ceui.loxia.ServicesProvider;
import ceui.loxia.User;

public class AppLevelStateHelper {

    /** legacy 静态工具没有 Context，经 Shaft.getContext() 过桥取进程级服务；不要再新增别的静态入口。 */
    private static AppLevelState state() {
        return ((ServicesProvider) Shaft.getContext()).getAppLevelState();
    }

    /**
     * 使用给定列表数据填充进程级关注态表
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
                    state().updateFollowUserStatus(user.getId(), getFollowUserStatus(user));
                }
            } else if (list.get(0).getClass().equals(UserPreviewsBean.class)) {
                for (UserPreviewsBean userPreviewsBean : (List<UserPreviewsBean>) list) {
                    User user = userPreviewsBean.getUser();
                    if (user == null) {
                        continue;
                    }
                    state().updateFollowUserStatus(user.getId(), getFollowUserStatus(user));
                }
            } else if (list.get(0).getClass().equals(User.class)) {
                for (User user : (List<User>) list) {
                    long userId = user.getId();
                    int followUserStatus = getFollowUserStatus(user);
                    state().updateFollowUserStatus(userId, followUserStatus);
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
                    state().updateFollowUserStatus(userBean.getId(), getFollowUserStatus(userBean), AppLevelState.UpdateMethod.IF_ABSENT);
                }
            }
        }
    }

    private static int getFollowUserStatus(User user) {
        return Boolean.TRUE.equals(user.is_followed()) ? AppLevelState.FollowUserStatus.FOLLOWED : AppLevelState.FollowUserStatus.NOT_FOLLOW;
    }

    public static void updateFollowUserStatus(User user, int method) {
        state().updateFollowUserStatus(user.getId(), getFollowUserStatus(user), method);
    }
}
