package ceui.lisa.utils;

import android.content.SharedPreferences;

import ceui.lisa.activities.Shaft;
import ceui.lisa.models.UserModel;
/**
 * A class deal with the {@link UserModel} and APP {@link Settings}
 * */
public class Local {

    public static final String LOCAL_DATA = "local_data";
    public static final String USER = "user";
    public static final String SETTINGS = "settings";

    public static void saveUser(UserModel userModel) {
        if (userModel != null) {
            String userString = Shaft.sGson.toJson(userModel, UserModel.class);
            SharedPreferences.Editor editor = Shaft.sPreferences.edit();
            editor.putString(USER, userString);
            if (editor.commit()) {
                Shaft.sUserModel = userModel;
            }
        }
    }

    public static UserModel getUser() {
        return Shaft.sGson.fromJson(
                Shaft.sPreferences
                        .getString(USER, ""),
                UserModel.class);
    }

    public static Settings getSettings() {
        String settingsString = Shaft.sPreferences.getString(SETTINGS, "");
        Settings settings = Shaft.sGson.fromJson(settingsString, Settings.class);
        return settings == null ? new Settings() : settings;
    }

    public static void setSettings(Settings settings) {
        String settingsGson = Shaft.sGson.toJson(settings);
        SharedPreferences.Editor editor = Shaft.sPreferences.edit();
        editor.putString(SETTINGS, settingsGson);
        editor.apply();
        Shaft.sSettings = settings;
    }
}
