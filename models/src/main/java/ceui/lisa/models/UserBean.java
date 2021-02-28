package ceui.lisa.models;

import java.io.Serializable;

public class UserBean implements Serializable, UserContainer, Starable {
    /**
     * profile_image_urls : {"px_16x16":"https://i.pximg.net/user-profile/img/2018/06/20/23/27/47/14384932_69771f95cafdac1a1d3da88fcfe4ecab_16.jpg","px_50x50":"https://i.pximg.net/user-profile/img/2018/06/20/23/27/47/14384932_69771f95cafdac1a1d3da88fcfe4ecab_50.jpg","px_170x170":"https://i.pximg.net/user-profile/img/2018/06/20/23/27/47/14384932_69771f95cafdac1a1d3da88fcfe4ecab_170.jpg"}
     * id : 31655571
     * name : details
     * account : mercisbv
     * mail_address : 290071582@qq.com
     * is_premium : false
     * x_restrict : 2
     * is_mail_authorized : true
     * require_policy_agreement : false
     */

    private ProfileImageUrlsBean profile_image_urls;
    private int id;
    private String name;
    private String comment;
    private String account;
    private String password;
    private String mail_address;
    private boolean is_login;
    private boolean is_premium;
    private boolean is_followed;
    private long lastTokenTime = -1;
    private int x_restrict;
    private boolean is_mail_authorized;
    private boolean require_policy_agreement;

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public boolean isIs_followed() {
        return is_followed;
    }

    public void setIs_followed(boolean is_followed) {
        this.is_followed = is_followed;
    }

    public long getLastTokenTime() {
        return lastTokenTime;
    }

    public void setLastTokenTime(long lastTokenTime) {
        this.lastTokenTime = lastTokenTime;
    }

    public ProfileImageUrlsBean getProfile_image_urls() {
        return profile_image_urls;
    }

    public void setProfile_image_urls(ProfileImageUrlsBean profile_image_urls) {
        this.profile_image_urls = profile_image_urls;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getMail_address() {
        return mail_address;
    }

    public void setMail_address(String mail_address) {
        this.mail_address = mail_address;
    }

    public boolean isIs_premium() {
        return is_premium;
    }

    public void setIs_premium(boolean is_premium) {
        this.is_premium = is_premium;
    }

    public int getX_restrict() {
        return x_restrict;
    }

    public void setX_restrict(int x_restrict) {
        this.x_restrict = x_restrict;
    }

    public boolean isIs_mail_authorized() {
        return is_mail_authorized;
    }

    public void setIs_mail_authorized(boolean is_mail_authorized) {
        this.is_mail_authorized = is_mail_authorized;
    }

    public boolean isRequire_policy_agreement() {
        return require_policy_agreement;
    }

    public void setRequire_policy_agreement(boolean require_policy_agreement) {
        this.require_policy_agreement = require_policy_agreement;
    }

    public String getPassword() {
        return password;
    }

    public boolean isIs_login() {
        return is_login;
    }

    public void setIs_login(boolean is_login) {
        this.is_login = is_login;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public int getUserId() {
        return id;
    }

    @Override
    public int getItemID() {
        return getId();
    }

    @Override
    public void setItemID(int id) {
        setId(id);
    }

    @Override
    public boolean isItemStared() {
        return isIs_followed();
    }

    @Override
    public void setItemStared(boolean isLiked) {
        setIs_followed(isLiked);
    }

    @Override
    public String toString() {
        return "UserBean{" +
                "profile_image_urls=" + profile_image_urls +
                ", id=" + id +
                ", name='" + name + '\'' +
                ", comment='" + comment + '\'' +
                ", account='" + account + '\'' +
                ", password='" + password + '\'' +
                ", mail_address='" + mail_address + '\'' +
                ", is_login=" + is_login +
                ", is_premium=" + is_premium +
                ", is_followed=" + is_followed +
                ", lastTokenTime=" + lastTokenTime +
                ", x_restrict=" + x_restrict +
                ", is_mail_authorized=" + is_mail_authorized +
                ", require_policy_agreement=" + require_policy_agreement +
                '}';
    }
}
