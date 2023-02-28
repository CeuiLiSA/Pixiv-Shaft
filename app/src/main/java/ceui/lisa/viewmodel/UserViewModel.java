package ceui.lisa.viewmodel;


import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import ceui.lisa.models.UserDetailResponse;
import ceui.loxia.Event;

public class UserViewModel extends ViewModel {

    private MutableLiveData<UserDetailResponse> user;

    public MutableLiveData<UserDetailResponse> getUser() {
        if (user == null) {
            user = new MutableLiveData<>();
        }
        return user;
    }

    public MutableLiveData<Boolean> isUserMuted = new MutableLiveData<>();
    public MutableLiveData<Boolean> isUserBlocked = new MutableLiveData<>();

    public MutableLiveData<Event<Integer>> refreshEvent = new MutableLiveData<>();

}
