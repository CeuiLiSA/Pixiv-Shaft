package ceui.lisa.models;

public class NewUser {


    /**
     * access_token : mHc-FNKwg3OvAqq_PNGkDMwtg616FI4gRvg0zvqnJvg
     * expires_in : 3600
     * token_type : bearer
     * scope :
     * refresh_token : riYm_TmWR1Zx-kOPo_pc6uAUuWQXM4A-eDUmicG9tLU
     * user : {"profile_image_urls":{"px_16x16":"https://i.pximg.net/user-profile/img/2020/10/20/16/22/51/19539756_68b7efa18c57614e800da9825367666f_16.jpg","px_50x50":"https://i.pximg.net/user-profile/img/2020/10/20/16/22/51/19539756_68b7efa18c57614e800da9825367666f_50.jpg","px_170x170":"https://i.pximg.net/user-profile/img/2020/10/20/16/22/51/19539756_68b7efa18c57614e800da9825367666f_170.jpg"},"id":"47041657","name":"shaft测试","account":"user_pluto","mail_address":"pixshaft@gmail.com","is_premium":false,"x_restrict":0,"is_mail_authorized":true,"require_policy_agreement":false}
     * device_token : 01bde3a0960d369673673139154985d3
     * response : {"access_token":"mHc-FNKwg3OvAqq_PNGkDMwtg616FI4gRvg0zvqnJvg","expires_in":3600,"token_type":"bearer","scope":"","refresh_token":"riYm_TmWR1Zx-kOPo_pc6uAUuWQXM4A-eDUmicG9tLU","user":{"profile_image_urls":{"px_16x16":"https://i.pximg.net/user-profile/img/2020/10/20/16/22/51/19539756_68b7efa18c57614e800da9825367666f_16.jpg","px_50x50":"https://i.pximg.net/user-profile/img/2020/10/20/16/22/51/19539756_68b7efa18c57614e800da9825367666f_50.jpg","px_170x170":"https://i.pximg.net/user-profile/img/2020/10/20/16/22/51/19539756_68b7efa18c57614e800da9825367666f_170.jpg"},"id":"47041657","name":"shaft测试","account":"user_pluto","mail_address":"pixshaft@gmail.com","is_premium":false,"x_restrict":0,"is_mail_authorized":true,"require_policy_agreement":false},"device_token":"01bde3a0960d369673673139154985d3"}
     */

    private String access_token;
    private Integer expires_in;
    private String token_type;
    private String scope;
    private String refresh_token;
    private UserBean user;
    private String device_token;

    public String getAccess_token() {
        return access_token;
    }

    public void setAccess_token(String access_token) {
        this.access_token = access_token;
    }

    public Integer getExpires_in() {
        return expires_in;
    }

    public void setExpires_in(Integer expires_in) {
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
}
