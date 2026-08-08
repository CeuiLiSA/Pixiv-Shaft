package ceui.lisa.utils;


import android.content.Context;

import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.DownloadDao;
import ceui.lisa.database.IllustHistoryEntity;
import ceui.lisa.database.MuteEntity;
import ceui.lisa.database.SearchDao;
import ceui.lisa.database.SearchEntity;
import ceui.lisa.database.UserEntity;
import ceui.lisa.feature.FeatureEntity;
import ceui.pixiv.download.config.DownloadConfigBackup;

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

    public static class BackupEntity {
        private Settings settings;
        private List<MuteEntity> muteEntityList;
        private List<FeatureEntity> featureEntityList;
        private List<SearchEntity> searchEntityList;
        private List<UserEntity> userEntityList;
        private List<IllustHistoryEntity> illustHistoryEntityList;
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
     */
    public static void writeBackupToFile(Context context, boolean backupViewHistory, File target) throws IOException {
        AppDatabase appDatabase = AppDatabase.getAppDatabase(context);
        try (JsonWriter writer = new JsonWriter(new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8)))) {
            writer.beginObject();
            writer.name("settings");
            Shaft.sGson.toJson(Shaft.sSettings, Settings.class, writer);
            writer.name("muteEntityList");
            Shaft.sGson.toJson(appDatabase.searchDao().getAllMuteEntities(), MUTE_LIST_TYPE, writer);
            writer.name("featureEntityList");
            Shaft.sGson.toJson(appDatabase.downloadDao().getAllFeatureEntities(), FEATURE_LIST_TYPE, writer);
            writer.name("searchEntityList");
            Shaft.sGson.toJson(appDatabase.searchDao().getAllSearchEntities(), SEARCH_LIST_TYPE, writer);
            writer.name("userEntityList");
            Shaft.sGson.toJson(appDatabase.downloadDao().getAllUser(), USER_LIST_TYPE, writer);
            if (backupViewHistory) {
                writer.name("illustHistoryEntityList");
                writer.beginArray();
                int offset = 0;
                while (true) {
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
            writer.endObject();
        }
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
        try (JsonReader reader = new JsonReader(new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8)))) {
            BackupEntity backupEntity = new BackupEntity();
            DownloadDao downloadDao = AppDatabase.getAppDatabase(context).downloadDao();
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (reader.peek() == JsonToken.NULL) {
                    reader.skipValue();
                    continue;
                }
                switch (name) {
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
                    case "downloadConfigV3":
                        backupEntity.setDownloadConfigV3(reader.nextString());
                        break;
                    case "illustHistoryEntityList":
                        // 历史是文件里唯一可能巨大的字段：边读边分批 REPLACE 入库。
                        // 文件中途损坏时已入库的批次会留下（历史行是幂等 upsert，无害），
                        // 但 settings 等配置只在整个文件解析成功后才应用。
                        reader.beginArray();
                        List<IllustHistoryEntity> batch = new ArrayList<>(HISTORY_BATCH_SIZE);
                        while (reader.hasNext()) {
                            batch.add(Shaft.sGson.fromJson(reader, IllustHistoryEntity.class));
                            if (batch.size() >= HISTORY_BATCH_SIZE) {
                                downloadDao.insertHistories(batch);
                                batch.clear();
                            }
                        }
                        reader.endArray();
                        if (!batch.isEmpty()) {
                            downloadDao.insertHistories(batch);
                        }
                        break;
                    default:
                        reader.skipValue();
                        break;
                }
            }
            reader.endObject();
            applyRestored(context, backupEntity);
            return backupEntity;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /** 应用除浏览历史外的还原内容（配置 + 各小表）。 */
    private static void applyRestored(Context context, BackupEntity backupEntity) {
        // 下载配置自己吞掉所有异常：解析不了就保持本机现状，不影响其余数据还原。
        // 必须排在 Local.setSettings 之前：本机若还没有 V3 配置，store 会用当前
        // Settings 的 downloadWay / rootPathUri 兜底，先还原 Settings 就会把备份里
        // 别的设备的 SAF 目录当成本机的兜底值。
        DownloadConfigBackup.restore(backupEntity.getDownloadConfigV3());
        Settings settings = backupEntity.getSettings();
        if (settings != null) {
            Local.setSettings(settings);
        }
        AppDatabase appDatabase = AppDatabase.getAppDatabase(context);
        List<MuteEntity> muteEntityList = backupEntity.getMuteEntityList();
        if (muteEntityList != null && !muteEntityList.isEmpty()) {
            SearchDao searchDao = appDatabase.searchDao();
            for (MuteEntity muteEntity : muteEntityList) {
                searchDao.insertMuteTag(muteEntity);
            }
        }
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
                searchDao.insert(searchEntity);
            }
        }
        List<UserEntity> userEntityList = backupEntity.getUserEntityList();
        if (userEntityList != null && !userEntityList.isEmpty()) {
            DownloadDao downloadDao = appDatabase.downloadDao();
            for (UserEntity userEntity : userEntityList) {
                downloadDao.insertUser(userEntity);
            }
        }
    }
}
