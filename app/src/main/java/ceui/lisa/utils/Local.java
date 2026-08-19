package ceui.lisa.utils;

import android.content.SharedPreferences;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.UserEntity;
import ceui.lisa.models.UserModel;
import ceui.pixiv.session.SessionManager;
import timber.log.Timber;

/**
 * A class deal with the {@link UserModel} and APP {@link Settings}
 * */
public class Local {

    public static final String LOCAL_DATA = "local_data";
    public static final String USER = "user";
    public static final String SETTINGS = "settings";

    public static void saveUser(UserModel userModel) {
        if (userModel != null) {
            // Keep SharedPreferences write for legacy compatibility (user switching, database entities)
            String userString = Shaft.sGson.toJson(userModel, UserModel.class);
            SharedPreferences.Editor editor = Shaft.sPreferences.edit();
            editor.putString(USER, userString);
            editor.commit();
            // Update SessionManager as the single source of truth
            SessionManager.INSTANCE.postUpdateSession(userModel);
        }
    }

    public static UserModel getUser() {
        String json = Shaft.sPreferences
                .getString(USER, "");
        Timber.d("getUserJson%s", json);
        return Shaft.sGson.fromJson(json, UserModel.class);
    }

    public static Settings getSettings() {
        String settingsString = Shaft.sPreferences.getString(SETTINGS, "");
        Settings settings = Shaft.sGson.fromJson(settingsString, Settings.class);
        if (settings != null) {
            Settings.migrateLegacyDoubleTapZoom(settings);
        }
        return settings == null ? new Settings() : settings;
    }

    public static void setSettings(Settings settings) {
        if (settings != null) {
            Settings.migrateLegacyDoubleTapZoom(settings);
        }
        String settingsGson = Shaft.sGson.toJson(settings);
        SharedPreferences.Editor editor = Shaft.sPreferences.edit();
        editor.putString(SETTINGS, settingsGson);
        editor.apply();
        Shaft.sSettings = settings;
    }

    /**
     * Persist a fully-logged-in user across the three stores, keeping them consistent:
     *  - SharedPreferences + SessionManager (via {@link #saveUser(UserModel)}) are the
     *    single source of truth for the login state;
     *  - the Room row is the account-switcher list (an account missing from it only
     *    affects switching, never the login state itself).
     *
     * <p>Blocking: it writes SharedPreferences and inserts a Room row inline. The DB is
     * built with {@code allowMainThreadQueries()}, so the legacy main-thread call sites
     * (OAuth callback / clipboard import) do not throw; new callers should still hop to
     * an IO thread (e.g. {@code withContext(Dispatchers.IO)}).
     *
     * <p>Failure semantics: if {@link #saveUser(UserModel)} fails the login is not
     * established; if only the Room insert fails the user is still logged in and the
     * failure is treated as non-fatal by the callers.
     */
    public static void persistLoggedInUser(UserModel userModel) {
        // user 缺失就不是一个能用的登录态：调用方必须先校验过再进来。这里不做静默补 0
        // 落库（会往账户切换表塞一条 userID=0 的脏行），也不让它 NPE 崩在这里。
        if (userModel == null || userModel.getUser() == null) {
            Timber.w("persistLoggedInUser: no user in UserModel, skip");
            return;
        }
        userModel.getUser().setIs_login(true);
        saveUser(userModel); // internally calls SessionManager.postUpdateSession (single source of truth)
        UserEntity entity = new UserEntity();
        entity.setLoginTime(System.currentTimeMillis());
        entity.setUserID(userModel.getUser().getId());
        entity.setUserGson(Shaft.sGson.toJson(getUser()));
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insertUser(entity);
    }
}
