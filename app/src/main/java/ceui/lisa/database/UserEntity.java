package ceui.lisa.database;

import android.arch.persistence.room.Entity;
import android.arch.persistence.room.PrimaryKey;
import android.support.annotation.NonNull;

@Entity(tableName = "user_table")
public final class UserEntity {

    @PrimaryKey()
    private int userID;
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

    public int getUserID() {
        return userID;
    }

    public void setUserID(int userID) {
        this.userID = userID;
    }

    public String getUserGson() {
        return userGson;
    }

    public void setUserGson(String userGson) {
        this.userGson = userGson;
    }
}
