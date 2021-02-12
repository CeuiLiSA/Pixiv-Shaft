package ceui.lisa.models;

import java.io.Serializable;

public class UserModel implements Serializable, UserContainer {

    @Override
    public int getUserId() {
        return user.getId();
    }

    private String access_token;
    private int expires_in;
    private String token_type;
    private String scope;
    private String refresh_token;
    private UserBean user;
    private String device_token;
    private String local_user;

    public String getAccess_token() {
        return "Bearer " + access_token;
    }

    public void setAccess_token(String access_token) {
        this.access_token = access_token;
    }

    public int getExpires_in() {
        return expires_in;
    }

    public void setExpires_in(int expires_in) {
        this.expires_in = expires_in;
    }

    public String getToken_type() {
        return token_type;
    }

    public void setToken_type(String token_type) {
        this.token_type = token_type;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getRefresh_token() {
        return refresh_token;
    }

    public void setRefresh_token(String refresh_token) {
        this.refresh_token = refresh_token;
    }

    public UserBean getUser() {
        return user;
    }

    public void setUser(UserBean user) {
        this.user = user;
    }

    public String getDevice_token() {
        return device_token;
    }

    public void setDevice_token(String device_token) {
        this.device_token = device_token;
    }

    public String getLocal_user() {
        return local_user;
    }

    public void setLocal_user(String local_user) {
        this.local_user = local_user;
    }
}
