package ceui.lisa.models;

public class MutedUsersBean extends UserHolder {

    /**
     * user : {"id":1236873,"name":"カオミン","account":"shibu11","profile_image_urls":{"medium":"https://i.pximg.net/user-profile/img/2018/09/09/14/08/06/14754860_a5717bca9f221b0ebabad8a2b2b34d5b_170.jpg"}}
     * is_premium_slot : false
     */

    private boolean is_premium_slot;

    public boolean isIs_premium_slot() {
        return is_premium_slot;
    }

    public void setIs_premium_slot(boolean is_premium_slot) {
        this.is_premium_slot = is_premium_slot;
    }
}
