package ceui.lisa.viewmodel;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import androidx.lifecycle.MutableLiveData;

/**
 * 关注 / 收藏态的进程级内存表。一个进程一份，由 {@link ceui.lisa.activities.Shaft} 构造、
 * 经 {@link ceui.loxia.ServicesProvider#getAppLevelState()} 取用。
 *
 * <p>它以前叫 AppLevelViewModel 且继承 AndroidViewModel、挂在 Shaft 的 static 字段上——
 * 但它从没跟任何 ViewModelStore 绑定过生命周期，本质就是一张跨页面共享的表，
 * 所以改成普通 class：单测可以 new 第二份，也不再伪装成 lifecycle 组件。
 */
public class AppLevelState {

    private final ConcurrentMap<Integer, MutableLiveData<Integer>> followUserStatus;
    private final ConcurrentMap<Integer, MutableLiveData<Integer>> starIllustStatus;
    private final ConcurrentMap<Integer, MutableLiveData<Integer>> starNovelStatus;

    public AppLevelState() {
        followUserStatus = new ConcurrentHashMap<>();
        starIllustStatus = new ConcurrentHashMap<>();
        starNovelStatus = new ConcurrentHashMap<>();
    }

    public MutableLiveData<Integer> getFollowUserLiveData(int userId) {
        MutableLiveData<Integer> data = followUserStatus.get(userId);
        if (data == null) {
            data = new MutableLiveData<>(FollowUserStatus.UNKNOWN);
            followUserStatus.put(userId, data);
        }
        return data;
    }

    public void updateFollowUserStatus(int userId, int status) {
        updateFollowUserStatus(userId, status, UpdateMethod.NORMAL);
    }

    public void updateFollowUserStatus(int userId, int status, int method) {
        MutableLiveData<Integer> data = followUserStatus.get(userId);
        switch (method) {
            case UpdateMethod.IF_ABSENT:
                if (data != null) {
                    Integer currentValue = data.getValue();
                    if (currentValue != null && currentValue == FollowUserStatus.UNKNOWN) {
                        data.setValue(status);
                    }
                } else {
                    followUserStatus.put(userId, new MutableLiveData<>(status));
                }
                break;
            case UpdateMethod.FORCE_REPLACE:
                if (data != null) {
                    data.setValue(status);
                } else {
                    followUserStatus.put(userId, new MutableLiveData<>(status));
                }
                break;
            default:
                if (data != null) {
                    Integer currentValue = data.getValue();
                    if (FollowUserStatus.isPreciseFollow(currentValue) && status == FollowUserStatus.FOLLOWED) {
                        return;
                    }
                    data.setValue(status);
                } else {
                    followUserStatus.put(userId, new MutableLiveData<>(status));
                }
                break;
        }
    }

    public MutableLiveData<Integer> getStarIllustLiveData(int illustId) {
        MutableLiveData<Integer> data = starIllustStatus.get(illustId);
        if (data == null) {
            data = new MutableLiveData<>(StarIllustStatus.UNKNOWN);
            starIllustStatus.put(illustId, data);
        }
        return data;
    }

    public void updateStarIllustStatus(int illustId, int status) {
        MutableLiveData<Integer> data = starIllustStatus.get(illustId);
        if (data != null) {
            data.setValue(status);
        } else {
            starIllustStatus.put(illustId, new MutableLiveData<>(status));
        }
    }

    public static class FollowUserStatus{
        public static final int UNKNOWN = 0;
        public static final int NOT_FOLLOW = 1;
        public static final int FOLLOWED = 2;
        public static final int FOLLOWED_PUBLIC = 3;
        public static final int FOLLOWED_PRIVATE = 4;

        public static boolean isFollowed(int status) {
            return status == FOLLOWED || status == FOLLOWED_PUBLIC || status == FOLLOWED_PRIVATE;
        }

        public static boolean isPreciseFollow(int status) {
            return status == FOLLOWED_PUBLIC || status == FOLLOWED_PRIVATE;
        }

        public static boolean isPublicFollowed(int status) {
            return status == FOLLOWED_PUBLIC;
        }

        public static boolean isPrivateFollowed(int status) {
            return status == FOLLOWED_PRIVATE;
        }
    }

    public static class StarIllustStatus {
        public static final int UNKNOWN = 0;
        public static final int NOT_STAR = 1;
        public static final int STARRED = 2;
        public static final int STARRED_PUBLIC = 3;
        public static final int STARRED_PRIVATE = 4;

        public static boolean isStarred(int status) {
            return status == STARRED || status == STARRED_PUBLIC || status == STARRED_PRIVATE;
        }

        public static boolean isPublicStarred(int status) {
            return status == STARRED_PUBLIC;
        }

        public static boolean isPrivateStarred(int status) {
            return status == STARRED_PRIVATE;
        }
    }

    public static class UpdateMethod {
        public static final int NORMAL = 0;
        public static final int IF_ABSENT = 1;
        public static final int FORCE_REPLACE = 2;
    }
}
