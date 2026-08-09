package ceui.lisa.viewmodel;


import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import ceui.lisa.models.UserDetailResponse;
import ceui.loxia.Event;
import ceui.loxia.WebUserDetail;

public class UserViewModel extends ViewModel {

    private MutableLiveData<UserDetailResponse> user;

    public MutableLiveData<UserDetailResponse> getUser() {
        if (user == null) {
            user = new MutableLiveData<>();
        }
        return user;
    }

    public MutableLiveData<WebUserDetail> webUserDetail = new MutableLiveData<>();

    public MutableLiveData<Boolean> isUserMuted = new MutableLiveData<>();
    public MutableLiveData<Boolean> isUserBlocked = new MutableLiveData<>();

    public MutableLiveData<Event<Integer>> refreshEvent = new MutableLiveData<>();

    /**
     * 纯小说作者页 banner：本次 ViewModel 生命周期内只拉一次小说列表，
     * 下拉刷新时由页面重置，以便换到最新有封面的作品。
     */
    private boolean novelBannerLoaded = false;

    /** banner 选中的小说 id，点击跳小说详情用（旋转后仍有效）。 */
    public long novelBannerNovelId = 0L;

    public boolean isNovelBannerLoaded() {
        return novelBannerLoaded;
    }

    public void setNovelBannerLoaded(boolean novelBannerLoaded) {
        this.novelBannerLoaded = novelBannerLoaded;
    }

}
