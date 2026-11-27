package ceui.lisa.utils;


import android.content.Context;

import androidx.sqlite.db.SupportSQLiteDatabase;

import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.zip.GZIPInputStream;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.DownloadDao;
import ceui.lisa.database.IllustHistoryEntity;
import ceui.lisa.database.MuteEntity;
import ceui.lisa.database.SearchDao;
import ceui.lisa.database.SearchEntity;
import ceui.lisa.database.UserEntity;
import ceui.lisa.feature.FeatureEntity;
import ceui.pixiv.db.DbCallTracker;
import ceui.pixiv.db.GeneralDao;
import ceui.pixiv.db.GeneralEntity;
import ceui.pixiv.db.RecordType;
import ceui.lisa.database.HistoryIdTime;
import ceui.pixiv.transfer.TransferRecords;
import ceui.pixiv.download.DownloadsRegistry;
import ceui.pixiv.download.config.DownloadConfigBackup;
import ceui.pixiv.download.config.DownloadConfigStore;
import ceui.pixiv.services.ServicesProvider;
import ceui.pixiv.ui.common.MutedWorkStores;

public class BackupUtils {

    /**
     * 浏览历史读库 / 入库的分批大小。备份链路任何时刻内存里只保留一批实体，
     * 而不是整张 illust_table（单条 illustJson 10~20 KB，几千上万条全量读出
     * 再整体 toJson 会直接 OOM，见 #981）。
     */
    public static final int HISTORY_BATCH_SIZE = 500;

    private static final Type MUTE_LIST_TYPE = new TypeToken<List<MuteEntity>>() {}.getType();
    private static final Type FEATURE_LIST_TYPE = new TypeToken<List<FeatureEntity>>() {}.getType();
    private static final Type SEARCH_LIST_TYPE = new TypeToken<List<SearchEntity>>() {}.getType();
    private static final Type USER_LIST_TYPE = new TypeToken<List<UserEntity>>() {}.getType();
    private static final Type PINNED_USER_LIST_TYPE = new TypeToken<List<GeneralEntity>>() {}.getType();

    public static class BackupEntity {
        /** WebDAV 导出不携带凭据，恢复时保留本机密钥及设备状态。旧文件默认为 false。 */
        private boolean deviceLocalSettings;
        private Settings settings;
        private List<MuteEntity> muteEntityList;
        private List<FeatureEntity> featureEntityList;
        private List<SearchEntity> searchEntityList;
        private List<UserEntity> userEntityList;
        private List<IllustHistoryEntity> illustHistoryEntityList;
        /**
         * 置顶作者（general_table 里 recordType=PINNED_USER 的 User 全量 JSON）。它不在
         * 上面任何一张表里，不单独打包就会在换机 / 重装后还原时静默丢失。
         */
        private List<GeneralEntity> pinnedUserEntityList;
        /**
         * 整份 V3 下载配置（下载路径 / 文件名 / 文件重复时 / 页码起始 / 仅 WiFi）序列化成的
         * JSON 字符串。这里存字符串而不是嵌套对象，是因为 {@link Shaft#sGson} 没有注册
         * StorageChoice 的 sealed 适配器，只有 DownloadConfigJson 那份 Gson 能正确读写；
         * 字段名与云同步 payload 的 {@code downloadConfigV3} 保持一致，两边可互相导入。
         */
        private String downloadConfigV3;

        public Settings getSettings() {
            return settings;
        }

        public void setSettings(Settings settings) {
            this.settings = settings;
        }

        public List<MuteEntity> getMuteEntityList() {
            return muteEntityList;
        }

        public void setMuteEntityList(List<MuteEntity> muteEntityList) {
            this.muteEntityList = muteEntityList;
        }

        public List<FeatureEntity> getFeatureEntityList() {
            return featureEntityList;
        }

        public void setFeatureEntityList(List<FeatureEntity> featureEntityList) {
            this.featureEntityList = featureEntityList;
        }

        public List<SearchEntity> getSearchEntityList() {
            return searchEntityList;
        }

        public void setSearchEntityList(List<SearchEntity> searchEntityList) {
            this.searchEntityList = searchEntityList;
        }

        public List<UserEntity> getUserEntityList() {
            return userEntityList;
        }

        public void setUserEntityList(List<UserEntity> userEntityList) {
            this.userEntityList = userEntityList;
        }

        public List<GeneralEntity> getPinnedUserEntityList() {
            return pinnedUserEntityList;
        }

        public void setPinnedUserEntityList(List<GeneralEntity> pinnedUserEntityList) {
            this.pinnedUserEntityList = pinnedUserEntityList;
        }

        public List<IllustHistoryEntity> getIllustHistoryEntityList() {
            return illustHistoryEntityList;
        }

        public void setIllustHistoryEntityList(List<IllustHistoryEntity> illustHistoryEntityList) {
            this.illustHistoryEntityList = illustHistoryEntityList;
        }

        public String getDownloadConfigV3() {
            return downloadConfigV3;
        }

        public void setDownloadConfigV3(String downloadConfigV3) {
            this.downloadConfigV3 = downloadConfigV3;
        }
    }

    /**
     * 流式导出：JSON 直接写进 target，浏览历史分页读、逐条写。字段名与旧版
     * {@code Shaft.sGson.toJson(BackupEntity)} 完全一致，新旧备份文件互认（#981）。
     * 必须在工作线程调用（读库 + 文件 IO）。
     *
     * 不具备跨设备性的设置（如「标签原文亮暗度」）不在这里排除 —— 它们根本不在
     * {@link Settings} 里，走设备本地的 MMKV（见 {@code TagLegibilityPrefs}），
     * 于是导出侧不需要任何排除名单。
     */
    public static void writeBackupToFile(Context context, boolean backupViewHistory, File target) throws IOException {
        try (OutputStream out = new FileOutputStream(target)) {
            writeBackup(context, backupViewHistory, true, out);
        }
    }

    /**
     * {@link #writeBackupToFile} 的流版本，写完关闭 {@code out}（调用方传 GZIPOutputStream
     * 时由这次 close 收尾压缩流）。
     *
     * @param includeCredentials false 时不导出登录凭据：pixiv 账号列表（{@code userEntityList}
     *        里是完整的 access/refresh token，pixiv 账号可绑定支付方式）以及 Settings 里的
     *        第三方密钥（AI 翻译 API key、aria2 RPC 密钥）——这两项写成空串。供备份要离开本机
     *        存储的出口（WebDAV）使用；还原方见到空串应保留本机原值，见 {@code WebDavBackup}。
     */
    public static void writeBackup(Context context, boolean backupViewHistory, boolean includeCredentials,
                                   OutputStream out) throws IOException {
        writeBackup(context, backupViewHistory, includeCredentials, out, null);
    }

    public static void writeTransferBackup(Context context, OutputStream out, Runnable checkActive) throws IOException {
        SupportSQLiteDatabase db = AppDatabase.getAppDatabase(context).getOpenHelper().getWritableDatabase();
        DbCallTracker.beginLongOperation();
        try {
            // Read-only deferred transaction keeps a consistent WAL snapshot without blocking writers.
            db.beginTransactionReadOnly();
            try {
                writeBackup(context, true, true, out, checkActive);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            DbCallTracker.endLongOperation();
        }
    }

    private static void writeBackup(Context context, boolean backupViewHistory, boolean includeCredentials,
                                    OutputStream out, Runnable transferCheck) throws IOException {
        AppDatabase appDatabase = AppDatabase.getAppDatabase(context);
        try (JsonWriter writer = new JsonWriter(new BufferedWriter(
                new OutputStreamWriter(out, StandardCharsets.UTF_8)))) {
            writer.beginObject();
            if (!includeCredentials) {
                writer.name("deviceLocalSettings").value(true);
            }
            writer.name("settings");
            Shaft.sGson.toJson(includeCredentials ? Shaft.sSettings : settingsWithoutSecrets(),
                    Settings.class, writer);
            writer.name("muteEntityList");
            Shaft.sGson.toJson(appDatabase.searchDao().getAllMuteEntities(), MUTE_LIST_TYPE, writer);
            writer.name("featureEntityList");
            Shaft.sGson.toJson(appDatabase.downloadDao().getAllFeatureEntities(), FEATURE_LIST_TYPE, writer);
            writer.name("searchEntityList");
            Shaft.sGson.toJson(appDatabase.searchDao().getAllSearchEntities(), SEARCH_LIST_TYPE, writer);
            if (includeCredentials) {
                writer.name("userEntityList");
                Shaft.sGson.toJson(appDatabase.downloadDao().getAllUser(), USER_LIST_TYPE, writer);
            }
            writer.name("pinnedUserEntityList");
            Shaft.sGson.toJson(appDatabase.generalDao().getByRecordType(RecordType.PINNED_USER, 0, Integer.MAX_VALUE), PINNED_USER_LIST_TYPE, writer);
            if (backupViewHistory) {
                writer.name("illustHistoryEntityList");
                writer.beginArray();
                int offset = 0;
                while (true) {
                    if (transferCheck != null) transferCheck.run();
                    List<IllustHistoryEntity> page =
                            appDatabase.downloadDao().getAllViewHistory(HISTORY_BATCH_SIZE, offset);
                    if (page == null || page.isEmpty()) {
                        break;
                    }
                    for (IllustHistoryEntity entity : page) {
                        Shaft.sGson.toJson(entity, IllustHistoryEntity.class, writer);
                    }
                    if (page.size() < HISTORY_BATCH_SIZE) {
                        break;
                    }
                    offset += page.size();
                }
                writer.endArray();
            }
            // 「设置 · 下载」里的下载路径 / 文件名等已经搬到 V3 下载配置，不在 Settings 里，
            // 得单独打包（#949）。
            writer.name("downloadConfigV3").value(DownloadConfigBackup.export());
            if (transferCheck != null) {
                writer.name("transferRecords");
                TransferRecords.write(context, writer, transferCheck);
            }
            writer.endObject();
        }
    }

    /** 当前 Settings 的深拷贝，第三方密钥置空；不动 {@link Shaft#sSettings} 本体。 */
    private static Settings settingsWithoutSecrets() {
        Settings copy = Shaft.sGson.fromJson(Shaft.sGson.toJsonTree(Shaft.sSettings, Settings.class), Settings.class);
        copy.setAiTranslateApiKey("");
        copy.setAria2RpcSecret("");
        return copy;
    }

    /** MoonSync 云同步 payload 的还原入口——payload 不含浏览历史且体量小，String 解析即可。 */
    public static boolean restoreBackups(Context context, String backupString) {
        try {
            BackupEntity backupEntity = Shaft.sGson.fromJson(backupString, BackupEntity.class);
            applyRestored(context, backupEntity);
            List<IllustHistoryEntity> illustHistoryEntityList = backupEntity.getIllustHistoryEntityList();
            if (illustHistoryEntityList != null && !illustHistoryEntityList.isEmpty()) {
                AppDatabase.getAppDatabase(context).downloadDao().insertHistories(illustHistoryEntityList);
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 流式还原：直接从文件流解析，浏览历史逐条读、攒批入库，既不把整个文件读成
     * String，也不把全部历史实体同时留在内存里（#981）。settings / downloadConfigV3
     * 先攒着，读完后按「下载配置先于 Settings」的既有顺序统一应用（顺序原因见
     * {@link #applyRestored}）。必须在工作线程调用。
     *
     * @return 还原出的小字段（含账号列表，历史列表不保留）；解析失败返回 null。
     */
    public static BackupEntity restoreBackupEntity(Context context, InputStream inputStream) {
        try {
            BackupEntity backup = readBackup(context, inputStream, null);
            applyRestored(context, backup);
            return backup;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void restoreTransferBackup(Context context, InputStream input, long uid, Runnable checkActive) {
        AppDatabase db = AppDatabase.getAppDatabase(context);
        BackupEntity backup;
        DbCallTracker.beginLongOperation();
        try {
            backup = db.runInTransaction((Callable<BackupEntity>) () -> {
                BackupEntity parsed = readBackup(context, input, checkActive);
                restoreMutes(db, parsed.getMuteEntityList());
                restoreTables(context, parsed, uid);
                checkActive.run();
                return parsed;
            });
        } finally {
            DbCallTracker.endLongOperation();
        }
        if (hasMutes(backup.getMuteEntityList())) MutedWorkStores.invalidateAll();
        Context application = context.getApplicationContext();
        if (application instanceof ServicesProvider) {
            // Reload pinned artists, Watch Later and the other cached general-table IDs.
            ((ServicesProvider) application).getEntityWrapper().initialize();
        }
        applyRestoredSettings(backup, true);
    }

    private static BackupEntity readBackup(Context context, InputStream inputStream,
                                           Runnable transferCheck) throws IOException {
        try (InputStream decoded = maybeGunzip(inputStream);
             JsonReader reader = new JsonReader(new BufferedReader(
                     new InputStreamReader(decoded, StandardCharsets.UTF_8)))) {
            // 旧还原链路是 Gson.fromJson(String),全程 lenient;这里对齐,
            // 否则带 BOM / 轻微不规范的旧备份文件会在 strict 模式下解析失败。
            reader.setLenient(true);
            BackupEntity backupEntity = new BackupEntity();
            DownloadDao downloadDao = AppDatabase.getAppDatabase(context).downloadDao();
            boolean hasTransferRecords = false;
            reader.beginObject();
            while (reader.hasNext()) {
                if (transferCheck != null) transferCheck.run();
                String name = reader.nextName();
                if (reader.peek() == JsonToken.NULL) {
                    reader.skipValue();
                    continue;
                }
                switch (name) {
                    case "deviceLocalSettings":
                        backupEntity.deviceLocalSettings = reader.nextBoolean();
                        break;
                    case "settings":
                        backupEntity.setSettings(Shaft.sGson.fromJson(reader, Settings.class));
                        break;
                    case "muteEntityList":
                        backupEntity.setMuteEntityList(Shaft.sGson.fromJson(reader, MUTE_LIST_TYPE));
                        break;
                    case "featureEntityList":
                        backupEntity.setFeatureEntityList(Shaft.sGson.fromJson(reader, FEATURE_LIST_TYPE));
                        break;
                    case "searchEntityList":
                        backupEntity.setSearchEntityList(Shaft.sGson.fromJson(reader, SEARCH_LIST_TYPE));
                        break;
                    case "userEntityList":
                        backupEntity.setUserEntityList(Shaft.sGson.fromJson(reader, USER_LIST_TYPE));
                        break;
                    case "pinnedUserEntityList":
                        backupEntity.setPinnedUserEntityList(Shaft.sGson.fromJson(reader, PINNED_USER_LIST_TYPE));
                        break;
                    case "downloadConfigV3":
                        backupEntity.setDownloadConfigV3(reader.nextString());
                        break;
                    case "transferRecords":
                        if (transferCheck == null) {
                            reader.skipValue();
                        } else {
                            TransferRecords.read(context, reader, transferCheck);
                            hasTransferRecords = true;
                        }
                        break;
                    case "illustHistoryEntityList":
                        reader.beginArray();
                        List<IllustHistoryEntity> batch = new ArrayList<>(HISTORY_BATCH_SIZE);
                        while (reader.hasNext()) {
                            batch.add(Shaft.sGson.fromJson(reader, IllustHistoryEntity.class));
                            if (batch.size() >= HISTORY_BATCH_SIZE) {
                                restoreHistoryBatch(downloadDao, batch, transferCheck);
                                batch.clear();
                            }
                        }
                        reader.endArray();
                        if (!batch.isEmpty()) restoreHistoryBatch(downloadDao, batch, transferCheck);
                        break;
                    default:
                        reader.skipValue();
                        break;
                }
            }
            reader.endObject();
            // 读到流末尾才能验证 gzip 的 CRC / 长度尾部；close 不会做这一步。
            // 在验证成功前不替换设置，截断文件也不能误报恢复成功。
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("unexpected content after backup");
            }
            // 首版 WebDAV 文件没有元数据；它是 gzip，且不导出 userEntityList。
            if (decoded instanceof GZIPInputStream && backupEntity.getUserEntityList() == null) {
                backupEntity.deviceLocalSettings = true;
            }
            if (transferCheck != null && (!hasTransferRecords || backupEntity.getSettings() == null)) {
                throw new IOException("incomplete device transfer backup");
            }
            return backupEntity;
        }
    }

    private static void restoreHistoryBatch(DownloadDao dao, List<IllustHistoryEntity> batch, Runnable transferCheck) {
        if (transferCheck != null) {
            transferCheck.run();
            List<Long> ids = new ArrayList<>(batch.size());
            for (IllustHistoryEntity row : batch) ids.add(row.getIllustID());
            Map<Long, HistoryIdTime> existing = new java.util.HashMap<>();
            for (HistoryIdTime row : dao.getViewHistoryTimes(ids)) existing.put(row.illustID, row);
            batch.removeIf(row -> {
                HistoryIdTime local = existing.get(row.getIllustID());
                return local != null && (local.type != row.getType() || local.time >= row.getTime());
            });
        }
        dao.insertHistories(batch);
    }

    /**
     * WebDAV 上的备份是 gzip 压缩的 {@code .json.gz}；按魔数识别，用户把它下载下来走
     * 本地「还原」也能直接导入。普通 JSON 原样返回。
     */
    private static InputStream maybeGunzip(InputStream inputStream) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(inputStream);
        buffered.mark(2);
        int b0 = buffered.read();
        int b1 = buffered.read();
        buffered.reset();
        if (b0 == 0x1f && b1 == 0x8b) {
            return new GZIPInputStream(buffered);
        }
        return buffered;
    }

    /** 应用除浏览历史外的还原内容（配置 + 各小表）。 */
    private static void applyRestored(Context context, BackupEntity backupEntity) {
        applyRestoredSettings(backupEntity, false);
        List<MuteEntity> mutes = backupEntity.getMuteEntityList();
        if (hasMutes(mutes)) {
            restoreMutes(AppDatabase.getAppDatabase(context), mutes);
            // Legacy restores write each table independently. Refresh even if a later table fails.
            MutedWorkStores.invalidateAll();
        }
        restoreTables(context, backupEntity, 0L);
        refreshPinnedUsers(context, backupEntity.getPinnedUserEntityList());
    }

    private static void applyRestoredSettings(BackupEntity backupEntity, boolean transfer) {
        // 下载配置自己吞掉所有异常：解析不了就保持本机现状，不影响其余数据还原。
        // 必须排在 Local.setSettings 之前：本机若还没有 V3 配置，store 会用当前
        // Settings 的 downloadWay / rootPathUri 兜底，先还原 Settings 就会把备份里
        // 别的设备的 SAF 目录当成本机的兜底值。
        boolean restoredV3 = DownloadConfigBackup.restore(backupEntity.getDownloadConfigV3());
        Settings settings = backupEntity.getSettings();
        if (settings != null) {
            if (backupEntity.deviceLocalSettings) {
                // 在首次写入 Settings 前合并，远端恢复与下载后的本地恢复走同一语义。
                // 后续表写入失败时，也不会留下已被抹空的本机密钥。
                Settings local = Shaft.sSettings;
                if (settings.getAiTranslateApiKey().isEmpty()) {
                    settings.setAiTranslateApiKey(local.getAiTranslateApiKey());
                }
                if (settings.getAria2RpcSecret().isEmpty()) {
                    settings.setAria2RpcSecret(local.getAria2RpcSecret());
                }
            }
            if (backupEntity.deviceLocalSettings || transfer) {
                Settings local = Shaft.sSettings;
                settings.setCloudHistoryConsentShown(local.isCloudHistoryConsentShown());
                settings.setMoonAppliedVersions(new java.util.HashMap<>(local.getMoonAppliedVersions()));
                settings.setCloudHistoryBackfillDoneUid(0L);
                if (transfer) {
                    settings.setCloudHistorySync(local.isCloudHistorySync());
                    settings.setRootPathUri(local.getRootPathUri());
                    settings.setDownloadWay(local.getDownloadWay());
                }
            }
            Settings.migrateLegacyDoubleTapZoom(settings);
            Settings.migrateLegacyLongPressBehavior(settings);
            if (!restoredV3) {
                // 备份里没有 V3 下载配置段（旧版备份 / 导出失败的空段）时 restore 不落盘，
                // 上面的时序保护就失效了：store 若还停在 FirstRun，必须先把当前兜底配置
                // 钉住，否则下一行覆盖遗留字段后，firstRunFallback 会把备份里异机的
                // 死 SAF 树当成本机兜底（#984 同款症状）。Corrupt 状态不动，留给修复链路。
                DownloadConfigStore.LoadResult loaded = DownloadsRegistry.getStore().load();
                if (loaded instanceof DownloadConfigStore.LoadResult.FirstRun) {
                    DownloadsRegistry.getStore().save(loaded.getConfig());
                }
            }
            Local.setSettings(settings);
        }
    }

    private static boolean hasMutes(List<MuteEntity> mutes) {
        return mutes != null && !mutes.isEmpty();
    }

    private static void restoreMutes(AppDatabase db, List<MuteEntity> muteEntityList) {
        if (hasMutes(muteEntityList)) {
            SearchDao searchDao = db.searchDao();
            for (MuteEntity muteEntity : muteEntityList) {
                searchDao.insertMuteTag(muteEntity);
            }
        }
    }

    private static void restoreTables(Context context, BackupEntity backupEntity, long transferUid) {
        AppDatabase appDatabase = AppDatabase.getAppDatabase(context);
        List<FeatureEntity> featureEntityList = backupEntity.getFeatureEntityList();
        if (featureEntityList != null && !featureEntityList.isEmpty()) {
            DownloadDao downloadDao = appDatabase.downloadDao();
            for (FeatureEntity featureEntity : featureEntityList) {
                downloadDao.insertFeature(featureEntity);
            }
        }
        List<SearchEntity> searchEntityList = backupEntity.getSearchEntityList();
        if (searchEntityList != null && !searchEntityList.isEmpty()) {
            SearchDao searchDao = appDatabase.searchDao();
            for (SearchEntity searchEntity : searchEntityList) {
                if (transferUid != 0) {
                    SearchEntity local = searchDao.findSearchEntity(searchEntity.getId());
                    if (local != null && local.isPinned() && !searchEntity.isPinned()) continue;
                    if (local != null && local.isPinned() == searchEntity.isPinned()
                            && local.getSearchTime() >= searchEntity.getSearchTime()) continue;
                }
                searchDao.insert(searchEntity);
            }
        }
        List<UserEntity> userEntityList = backupEntity.getUserEntityList();
        if (userEntityList != null && !userEntityList.isEmpty()) {
            DownloadDao downloadDao = appDatabase.downloadDao();
            for (UserEntity userEntity : userEntityList) {
                if (transferUid == 0 || (userEntity.getUserID() != transferUid
                        && downloadDao.getUserByUid(userEntity.getUserID()) == null)) {
                    downloadDao.insertUser(userEntity);
                }
            }
        }
        List<GeneralEntity> pinnedUserEntityList = backupEntity.getPinnedUserEntityList();
        if (pinnedUserEntityList != null && !pinnedUserEntityList.isEmpty()) {
            GeneralDao generalDao = appDatabase.generalDao();
            for (GeneralEntity pinnedUser : pinnedUserEntityList) {
                if (transferUid != 0) {
                    if (pinnedUser == null || pinnedUser.getRecordType() != RecordType.PINNED_USER) {
                        throw new IllegalArgumentException("Unsupported pinned user record");
                    }
                    GeneralEntity local = generalDao.getByRecordTypeAndId(RecordType.PINNED_USER, pinnedUser.getId());
                    if (local != null && local.getUpdatedTime() >= pinnedUser.getUpdatedTime()) continue;
                }
                generalDao.insert(pinnedUser);
            }
        }
    }

    private static void refreshPinnedUsers(Context context, List<GeneralEntity> pinnedUsers) {
        if (pinnedUsers != null && !pinnedUsers.isEmpty()) {
            // 这里绕过 EntityWrapper 直接灌了 general_table，它的内存置顶集合要整份重读，
            // 否则作者主页「更多」菜单按旧集合摆「置顶 / 取消置顶」，与列表页对不上。
            Context application = context.getApplicationContext();
            if (application instanceof ServicesProvider) {
                ((ServicesProvider) application).getEntityWrapper().refreshPinnedUsers(context);
            }
        }
    }
}
