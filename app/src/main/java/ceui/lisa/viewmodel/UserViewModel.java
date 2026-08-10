package ceui.lisa.viewmodel;


import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import ceui.lisa.models.UserDetailResponse;
import ceui.loxia.Event;
import ceui.loxia.Novel;
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
     * 纯小说作者页 banner 兜底（issue #978）：本次 ViewModel 生命周期内是否已成功拉过小说列表。
     * 「一篇有封面的都没有」也算拉过，避免每次 displayUser 都重打一次请求；
     * 请求失败不落这个标记，好让下次进 displayUser 还能再试。
     */
    public boolean novelBannerFetched = false;

    /**
     * banner 选中的那篇小说（封面 URL + 点击目标）。深色模式/语言切换会真正重建 Activity
     * （configChanges 只挡了旋转），ViewModel 连同这份选择存活 —— 页面据此直接重绑 banner，
     * 不必也不该再打一次请求。下拉刷新时页面清空它，以便换到最新投稿的封面。
     */
    public Novel novelBannerNovel = null;

}
