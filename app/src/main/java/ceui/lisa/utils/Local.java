package ceui.lisa.utils;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.model.UserModel;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class Local {

    public static final String LOCAL_DATA = "local_data";
    public static final String USER = "user";

    public static void saveUser(UserModel userModel) {
        if (userModel != null) {
            userModel.getResponse().getUser().setIs_login(true);
            String token = userModel.getResponse().getAccess_token();
            userModel.getResponse().setAccess_token("Bearer " + token);
            Gson gson = new Gson();
            String userString = gson.toJson(userModel, UserModel.class);
            SharedPreferences localData = Shaft.getContext().getSharedPreferences(LOCAL_DATA, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = localData.edit();
            editor.putString(USER, userString);
            editor.apply();
            Shaft.sUserModel = userModel;
        }
    }

    public static UserModel getUser() {
        SharedPreferences localData = Shaft.getContext().getSharedPreferences(LOCAL_DATA, Context.MODE_PRIVATE);
        String userString = localData.getString(USER, "");
        Gson gson = new Gson();
        UserModel userModel = gson.fromJson(userString, UserModel.class);
        return userModel;
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

    public static boolean getBoolean(String key, boolean defValue){
        SharedPreferences localData = Shaft.getContext().getSharedPreferences(LOCAL_DATA, Context.MODE_PRIVATE);
        Common.showLog("getBoolean " + key + " " + localData.getBoolean(key, defValue));
        return localData.getBoolean(key, defValue);
    }

    public static void setBoolean(String key, boolean value){
        SharedPreferences localData = Shaft.getContext().getSharedPreferences(LOCAL_DATA, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = localData.edit();
        editor.putBoolean(key, value);
        editor.apply();
    }
}
