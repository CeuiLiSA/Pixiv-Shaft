package ceui.lisa.model;

public class ExportUser {

    private String userName;
    private String userPassword;
    private String LOCAL_USER = "pixiv_shaft_local_user";

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserPassword() {
        return userPassword;
    }

    public void setUserPassword(String userPassword) {
        this.userPassword = userPassword;
    }

    public String getLOCAL_USER() {
        return LOCAL_USER;
    }

    public void setLOCAL_USER(String LOCAL_USER) {
        this.LOCAL_USER = LOCAL_USER;
    }
}
