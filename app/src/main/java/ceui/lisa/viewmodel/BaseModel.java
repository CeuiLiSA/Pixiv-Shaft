package ceui.lisa.viewmodel;

import android.util.Log;

import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.database.IllustHistoryEntity;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.UserBean;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.utils.Common;


public class BaseModel<T> extends ViewModel{

    List<T> content = null;
    private boolean isLoaded = false;
    private BaseRepo mBaseRepo;

    public BaseModel() {
        Common.showLog("trace 构造 000");
    }

    public List<T> getContent() {
        if (content == null) {
            content = new ArrayList<>();
        }
        return content;
    }

    public void load(List<T> list, boolean isFresh) {
        if (isFresh) {
            content.clear();
        }
        content.addAll(list);
        isLoaded = true;
    }

    public void load(List<T> list, int index) {
        content.addAll(index, list);
    }

    public boolean isLoaded() {
        return isLoaded;
    }

    public BaseRepo getBaseRepo() {
        return mBaseRepo;
    }

    public void setBaseRepo(BaseRepo baseRepo) {
        mBaseRepo = baseRepo;
    }

    public void tidyAppViewModel(){
        tidyAppViewModel(content);
    }

    public void tidyAppViewModel(List<T> list) {
        if (list.size() > 0) {
            if (list.get(0).getClass().equals(IllustsBean.class)) {
                for (IllustsBean illustsBean : (List<IllustsBean>) list) {
                    int userId = illustsBean.getUser().getId();
                    int followUserStatus = illustsBean.getUser().isIs_followed() ? AppLevelViewModel.FollowUserStatus.FOLLOWED : AppLevelViewModel.FollowUserStatus.NOT_FOLLOW;
                    Shaft.appViewModel.updateFollowUserStatus(userId, followUserStatus);
                }
            } else if (list.get(0).getClass().equals(UserPreviewsBean.class)) {
                for (UserPreviewsBean userPreviewsBean : (List<UserPreviewsBean>) list) {
                    int userId = userPreviewsBean.getUser().getId();
                    int followUserStatus = userPreviewsBean.getUser().isIs_followed() ? AppLevelViewModel.FollowUserStatus.FOLLOWED : AppLevelViewModel.FollowUserStatus.NOT_FOLLOW;
                    Shaft.appViewModel.updateFollowUserStatus(userId, followUserStatus);
                }
            } else if (list.get(0).getClass().equals(UserBean.class)) {
                for (UserBean userBean : (List<UserBean>) list) {
                    int userId = userBean.getId();
                    int followUserStatus = userBean.isIs_followed() ? AppLevelViewModel.FollowUserStatus.FOLLOWED : AppLevelViewModel.FollowUserStatus.NOT_FOLLOW;
                    Shaft.appViewModel.updateFollowUserStatus(userId, followUserStatus);
                }
            } else if (list.get(0).getClass().equals(IllustHistoryEntity.class)) {
                for (IllustHistoryEntity entity : (List<IllustHistoryEntity>) list) {
                    IllustsBean illustsBean = Shaft.sGson.fromJson(entity.getIllustJson(), IllustsBean.class);
                    UserBean userBean = illustsBean.getUser();
                    int userId = userBean.getId();
                    int followUserStatus = userBean.isIs_followed() ? AppLevelViewModel.FollowUserStatus.FOLLOWED : AppLevelViewModel.FollowUserStatus.NOT_FOLLOW;
                    Shaft.appViewModel.updateFollowUserStatusIfAbsent(userId, followUserStatus);
                }
            }
        }
    }
}
