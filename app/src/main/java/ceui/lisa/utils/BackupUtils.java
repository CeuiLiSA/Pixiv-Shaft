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

public class BackupUtils {

    public static class BackupEntity {
        private Settings settings;
        private List<MuteEntity> muteEntityList;
        private List<FeatureEntity> featureEntityList;
        private List<SearchEntity> searchEntityList;
        private List<UserEntity> userEntityList;
        private List<IllustHistoryEntity> illustHistoryEntityList;

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
    }

    public static String getBackupString(Context context, boolean backupViewHistory) {
        BackupEntity backupEntity = new BackupEntity();
        backupEntity.setSettings(Shaft.sSettings);
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
