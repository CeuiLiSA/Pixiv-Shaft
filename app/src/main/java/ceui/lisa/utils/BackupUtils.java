package ceui.lisa.utils;


import android.content.Context;

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

    public static String getBackupString(Context context, boolean backupViewHistory) {
        BackupEntity backupEntity = new BackupEntity();
        backupEntity.setSettings(Shaft.sSettings);
        // 「设置 · 下载」里的下载路径 / 文件名等已经搬到 V3 下载配置，不在 Settings 里，
        // 得单独打包（#949）。
        backupEntity.setDownloadConfigV3(DownloadConfigBackup.export());
        AppDatabase appDatabase = AppDatabase.getAppDatabase(context);
        backupEntity.setMuteEntityList(appDatabase.searchDao().getAllMuteEntities());
        backupEntity.setFeatureEntityList(appDatabase.downloadDao().getAllFeatureEntities());
        backupEntity.setSearchEntityList(appDatabase.searchDao().getAllSearchEntities());
        backupEntity.setUserEntityList(appDatabase.downloadDao().getAllUser());
        if (backupViewHistory){
            backupEntity.setIllustHistoryEntityList(appDatabase.downloadDao().getAllViewHistoryEntities());
        }
        return Shaft.sGson.toJson(backupEntity);
    }

    public static boolean restoreBackups(Context context, String backupString) {
        try {
            BackupEntity backupEntity = Shaft.sGson.fromJson(backupString, BackupEntity.class);
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
            List<IllustHistoryEntity> illustHistoryEntityList = backupEntity.getIllustHistoryEntityList();
            if (illustHistoryEntityList != null && !illustHistoryEntityList.isEmpty()) {
                DownloadDao downloadDao = appDatabase.downloadDao();
                for (IllustHistoryEntity illustHistoryEntity : illustHistoryEntityList) {
                    downloadDao.insert(illustHistoryEntity);
                }
            }

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
