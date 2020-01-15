package ceui.lisa.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;

import ceui.lisa.activities.Shaft;
import ceui.lisa.models.UserModel;

public class Local {

    public static final String LOCAL_DATA = "local_data";
    public static final String USER = "user";

    public static void saveUser(UserModel userModel) {
        if (userModel != null) {
            String token = userModel.getResponse().getAccess_token();
            if (!token.contains("Bearer ")) {
                userModel.getResponse().setAccess_token("Bearer " + token);
            }
            String userString = Shaft.sGson.toJson(userModel, UserModel.class);
            SharedPreferences localData = Shaft.getContext().getSharedPreferences(LOCAL_DATA, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = localData.edit();
            editor.putString(USER, userString);
            if (editor.commit()) {
                Shaft.sUserModel = userModel;
            }
        }
    }

    public static UserModel getUser() {
        return Shaft.sGson.fromJson(
                Shaft.getContext()
                        .getSharedPreferences(LOCAL_DATA, Context.MODE_PRIVATE)
                        .getString(USER, ""),
                UserModel.class);
    }

    public static Settings getSettings() {
        SharedPreferences localData = Shaft.getContext().getSharedPreferences(LOCAL_DATA, Context.MODE_PRIVATE);
        String settingsString = localData.getString("settings", "");
        Settings settings = new Gson().fromJson(settingsString, Settings.class);
        return settings == null ? new Settings() : settings;
    }

    public static void setSettings(Settings settings) {
        SharedPreferences localData = Shaft.getContext().getSharedPreferences(LOCAL_DATA, Context.MODE_PRIVATE);
        Gson gson = new Gson();
        String settingsGson = gson.toJson(settings);
        SharedPreferences.Editor editor = localData.edit();
        editor.putString("settings", settingsGson);
        editor.apply();
        Shaft.sSettings = settings;
    }

    public static boolean getBoolean(String key, boolean defValue) {
        SharedPreferences localData = Shaft.getContext().getSharedPreferences(LOCAL_DATA, Context.MODE_PRIVATE);
        Common.showLog("getBoolean " + key + " " + localData.getBoolean(key, defValue));
        return localData.getBoolean(key, defValue);
    }

    public static void setBoolean(String key, boolean value) {
        SharedPreferences localData = Shaft.getContext().getSharedPreferences(LOCAL_DATA, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = localData.edit();
        editor.putBoolean(key, value);
        editor.apply();
    }
}
