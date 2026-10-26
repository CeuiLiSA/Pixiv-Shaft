package ceui.lisa.utils;

import android.content.SharedPreferences;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.DownloadDao;
import ceui.lisa.database.UserEntity;
import ceui.pixiv.api.model.AccountResponse;
import ceui.pixiv.session.SessionManager;
import timber.log.Timber;

/**
 * A class deal with the {@link AccountResponse} and APP {@link Settings}
 * */
public class Local {

    public static final String LOCAL_DATA = "local_data";
    public static final String USER = "user";
    public static final String SETTINGS = "settings";

    public static void saveUser(AccountResponse userModel) {
        if (userModel != null) {
            // Keep SharedPreferences write for legacy compatibility (user switching, database entities)
            String userString = Shaft.sGson.toJson(userModel, AccountResponse.class);
            SharedPreferences.Editor editor = Shaft.sPreferences.edit();
            editor.putString(USER, userString);
            editor.commit();
            // Update SessionManager as the single source of truth
            SessionManager.INSTANCE.postUpdateSession(userModel);
        }
    }

    public static AccountResponse getUser() {
        String json = Shaft.sPreferences
                .getString(USER, "");
        return Shaft.sGson.fromJson(json, AccountResponse.class);
    }

    public static Settings getSettings() {
        String settingsString = Shaft.sPreferences.getString(SETTINGS, "");
        Shaft.awaitSettingsWarm();
        Settings settings = Shaft.sGson.fromJson(settingsString, Settings.class);
        if (settings != null) {
            Settings.migrateLegacyDoubleTapZoom(settings);
            Settings.migrateLegacyLongPressBehavior(settings);
            Settings.migrateLegacyDownloadToasts(settings);
        }
        return settings == null ? new Settings() : settings;
    }

    public static void setSettings(Settings settings) {
        if (settings != null) {
            Settings.migrateLegacyDoubleTapZoom(settings);
            Settings.migrateLegacyLongPressBehavior(settings);
            Settings.migrateLegacyDownloadToasts(settings);
        }
        String settingsGson = Shaft.sGson.toJson(settings);
        SharedPreferences.Editor editor = Shaft.sPreferences.edit();
        editor.putString(SETTINGS, settingsGson);
        editor.apply();
        Shaft.sSettings = settings;
    }

    /**
     * Persist a fully-logged-in user across the three stores, keeping them consistent:
     *  - SharedPreferences + SessionManager (via {@link #saveUser(AccountResponse)}) are the
     *    single source of truth for the login state;
     *  - the Room row is the account-switcher list (an account missing from it only
     *    affects switching, never the login state itself).
     *
     * <p>Blocking: it writes SharedPreferences and inserts a Room row inline. The DB is
     * built with {@code allowMainThreadQueries()}, so the legacy main-thread call sites
     * (OAuth callback / clipboard import) do not throw; new callers should still hop to
     * an IO thread (e.g. {@code withContext(Dispatchers.IO)}).
     *
     * <p>Failure semantics: if {@link #saveUser(AccountResponse)} fails the login is not
     * established; if only the Room insert fails the user is still logged in and the
     * failure is treated as non-fatal by the callers.
     */
    public static void persistLoggedInUser(AccountResponse userModel) {
        // user 缺失就不是一个能用的登录态：调用方必须先校验过再进来。这里不做静默补 0
        // 落库（会往账户切换表塞一条 userID=0 的脏行），也不让它 NPE 崩在这里。
        if (userModel == null || userModel.getUser() == null) {
            Timber.w("persistLoggedInUser: no user in AccountResponse, skip");
            return;
        }
        userModel.getUser().set_login(true);
        saveUser(userModel); // internally calls SessionManager.postUpdateSession (single source of truth)
        UserEntity entity = new UserEntity();
        entity.setLoginTime(System.currentTimeMillis());
        entity.setUserID(userModel.getUser().getId());
        entity.setUserGson(Shaft.sGson.toJson(getUser()));
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insertUser(entity);
    }

    /**
     * 把会话里刷新过的那份 user 写回账号表，**保留原来的入库时间**。
     *
     * <p>账号表这一行是「登录那一刻」冻下来的快照：pixiv 的 OAuth 登录响应里
     * profile_image_urls 只有三张默认占位图（真实头像得走 user/detail 才拿得到），
     * 快照冻下之后账号管理页就一直画占位图 —— 而侧边栏读的是会话、由前台静默同步持续刷新，
     * 两份数据源一旧一新。会话每次拿到新鲜资料就顺手把这一行刷一遍，快照才追得上会话。
     *
     * <p>只更新已存在的行，不凭空插入：表里没有它，说明这个号没在本机登录过（或刚被删掉），
     * 该不该出现在账号列表里是账号管理的事，不该由一次后台刷新决定。
     *
     * <p>值没变就不落盘：这条路径每次回前台都会走一遍，无变化还写会把 WAL 白白撑大。
     * 必须在 IO 线程调用。
     */
    public static void refreshStoredUser(AccountResponse account) {
        if (account == null || account.getUser() == null) return;
        long uid = account.getUser().getId();
        if (uid <= 0L) return;
        DownloadDao dao = AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao();
        UserEntity existing = dao.getUserByUid(uid);
        if (existing == null) return;
        String next = Shaft.sGson.toJson(account);
        if (next.equals(existing.getUserGson())) return;
        existing.setUserGson(next);
        // REPLACE 按主键 userID 覆盖，loginTime 原样带回去，不刷新「登录于」那一行。
        dao.insertUser(existing);
    }
}
