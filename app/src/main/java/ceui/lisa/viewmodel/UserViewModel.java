package ceui.lisa.viewmodel;


import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import ceui.lisa.models.UserDetailResponse;
import ceui.lisa.models.UserFollowDetail;

public class UserViewModel extends ViewModel {

    private MutableLiveData<UserDetailResponse> user;

    private MutableLiveData<UserFollowDetail> userFollowDetail;

    public MutableLiveData<UserDetailResponse> getUser() {
        if (user == null) {
            user = new MutableLiveData<>();
        }
        return user;
    }

    public MutableLiveData<UserFollowDetail> getUserFollowDetail() {
        if (userFollowDetail == null) {
            userFollowDetail = new MutableLiveData<>();
        }
        return userFollowDetail;
    }
}
