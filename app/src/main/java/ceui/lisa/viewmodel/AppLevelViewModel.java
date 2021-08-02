package ceui.lisa.viewmodel;

import android.app.Application;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;

public class AppLevelViewModel extends AndroidViewModel {

    private ConcurrentMap<Integer, MutableLiveData<Integer>> followUserStatus;
    private ConcurrentMap<Integer, MutableLiveData<Integer>> starIllustStatus;
    private ConcurrentMap<Integer, MutableLiveData<Integer>> starNovelStatus;

    public AppLevelViewModel(@NonNull Application application) {
        super(application);
        followUserStatus = new ConcurrentHashMap<>();
        starIllustStatus = new ConcurrentHashMap<>();
        starNovelStatus = new ConcurrentHashMap<>();
    }

    public MutableLiveData<Integer> getFollowUserLiveData(int userId) {
        MutableLiveData<Integer> data = followUserStatus.get(userId);
        if (data == null) {
            data = new MutableLiveData<Integer>(FollowUserStatus.UNKNOWN);
            followUserStatus.put(userId, data);
        }
        return data;
    }

    public void updateFollowUserStatus(int userId, int status) {
        MutableLiveData<Integer> data = followUserStatus.get(userId);
        if (data != null) {
            Integer currentValue = data.getValue();
            if(FollowUserStatus.isPreciseFollow(currentValue) && status == FollowUserStatus.FOLLOWED){
                return;
            }
            data.setValue(status);
        } else {
            followUserStatus.put(userId, new MutableLiveData<Integer>(status));
        }
    }

    public MutableLiveData<Integer> getStarIllustLiveData(int illustId) {
        MutableLiveData<Integer> data = starIllustStatus.get(illustId);
        if (data == null) {
            data = new MutableLiveData<Integer>(StarIllustStatus.UNKNOWN);
            starIllustStatus.put(illustId, data);
        }
        return data;
    }

    public void updateStarIllustStatus(int illustId, int status) {
        MutableLiveData<Integer> data = starIllustStatus.get(illustId);
        if (data != null) {
            data.setValue(status);
        } else {
            starIllustStatus.put(illustId, new MutableLiveData<Integer>(status));
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
}
