package ceui.lisa.models;

import java.io.Serializable;

public class UserHolder implements Serializable {

    /**
     * user : {"id":1174108,"name":"をこめ","account":"wokome","profile_image_urls":{"medium":"https://i.pximg.net/user-profile/img/2013/04/17/15/05/20/6117056_1f2488a1d3cfa18ace358b5556aa9ce1_170.png"},"is_followed":false}
     */

    private UserBean user;

    public UserBean getUser() {
        return user;
    }

    public void setUser(UserBean user) {
        this.user = user;
    }
}
