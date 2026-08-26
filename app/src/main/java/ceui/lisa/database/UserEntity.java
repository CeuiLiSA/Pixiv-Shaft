package ceui.lisa.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.google.gson.Gson;

import ceui.loxia.AccountResponse;

@Entity(tableName = "user_table")
public final class UserEntity {

    @PrimaryKey()
    private long userID;
    private String userGson;
    private long loginTime;

    public long getLoginTime() {
        return loginTime;
    }

    public void setLoginTime(long loginTime) {
        this.loginTime = loginTime;
    }

    @Override
    public String toString() {
        return "UserEntity{" +
                "userID=" + userID +
                ", userGson='" + userGson + '\'' +
                ", loginTime=" + loginTime +
                '}';
    }

    public long getUserID() {
        return userID;
    }

    public void setUserID(long userID) {
        this.userID = userID;
    }

    public String getUserGson() {
        return userGson;
    }

    public void setUserGson(String userGson) {
        this.userGson = userGson;
    }

    public AccountResponse getUser(Gson gson) {
        return gson.fromJson(userGson, AccountResponse.class);
    }
}
