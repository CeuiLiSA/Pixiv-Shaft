package ceui.lisa.utils;


import android.content.Context;

import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.DownloadDao;
import ceui.lisa.database.MuteEntity;
import ceui.lisa.database.SearchDao;
import ceui.lisa.feature.FeatureEntity;

public class BackupUtils {

    public static class BackupEntity {
        private Settings settings;
        private List<MuteEntity> muteEntityList;
        private List<FeatureEntity> featureEntityList;

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
    }

    public static String getBackupString(Context context) {
        BackupEntity backupEntity = new BackupEntity();
        backupEntity.setSettings(Shaft.sSettings);
        AppDatabase appDatabase = AppDatabase.getAppDatabase(context);
        backupEntity.setMuteEntityList(appDatabase.searchDao().getAllMuteEntities());
        backupEntity.setFeatureEntityList(appDatabase.downloadDao().getAllFeatureEntities());
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
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
