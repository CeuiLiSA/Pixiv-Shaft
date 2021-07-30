package ceui.lisa.viewmodel;


import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import ceui.lisa.models.UserDetailResponse;
import ceui.lisa.models.UserFollowDetail;

public class UserViewModel extends ViewModel {

    private MutableLiveData<UserDetailResponse> user;

    public MutableLiveData<UserDetailResponse> getUser() {
        if (user == null) {
            user = new MutableLiveData<>();
        }
        return user;
    }
}
