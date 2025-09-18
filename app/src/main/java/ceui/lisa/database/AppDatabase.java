package ceui.lisa.database;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import ceui.lisa.feature.FeatureEntity;
import ceui.pixiv.db.GeneralDao;
import ceui.pixiv.db.GeneralEntity;
import ceui.pixiv.db.RemoteKey;

@Database(
        entities = {
                IllustHistoryEntity.class, //浏览历史
                IllustRecmdEntity.class, //用不到，调试用
                DownloadEntity.class, //下载历史
                UserEntity.class, //多用户保存信息
                SearchEntity.class, //搜索历史
                ImageEntity.class, //用不到
                MuteEntity.class, //记录用户屏蔽的标签
                UUIDEntity.class, //记录用户屏蔽的标签
                FeatureEntity.class, //记录用户收藏的精华列表
                DownloadingEntity.class, //记录用户正在下载中的列表
                GeneralEntity.class, // 新增的 GeneralEntity
                RemoteKey.class,
        },
        version = 27,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    public static final String DATABASE_NAME = "roomDemo-database";
    private static final Migration MIGRATION_23_24 = new Migration(23, 24) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE feature_table ADD COLUMN seriesId INTEGER NOT NULL DEFAULT 0");
        }
    };
    private static final Migration MIGRATION_24_25 = new Migration(24, 25) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE search_table ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0");
        }
    };
    // 迁移 25 -> 26 (创建 general_table)
    private static final Migration MIGRATION_25_26 = new Migration(25, 26) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS general_table (" +
                            "id INTEGER NOT NULL, " +  // id 字段，非空
                            "recordType INTEGER NOT NULL, " +  // recordType 字段，非空
                            "json TEXT NOT NULL, " +
                            "entityType INTEGER NOT NULL, " +
                            "updatedTime INTEGER NOT NULL DEFAULT (strftime('%s', 'now') * 1000), " +
                            "PRIMARY KEY(id, recordType)" +  // 复合主键
                            ")"
            );
        }
    };
    private static final Migration MIGRATION_26_27 = new Migration(26, 27) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // 创建 remote_keys 表，带 lastUpdatedTime 字段
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS remote_keys (" +
                            "recordType INTEGER NOT NULL PRIMARY KEY, " +
                            "nextPageUrl TEXT, " +
                            "lastUpdatedTime INTEGER NOT NULL DEFAULT (strftime('%s', 'now') * 1000)" +
                            ")"
            );
        }
    };
    private static AppDatabase INSTANCE;

    public static AppDatabase getAppDatabase(Context context) {
        if (INSTANCE == null) {
            INSTANCE =
                    Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, DATABASE_NAME)
                            // allow queries on the main thread.
                            // Don't do this on a real app! See PersistenceBasicSample for an example.
                            //.fallbackToDestructiveMigration()
                            .allowMainThreadQueries()
                            .addMigrations(MIGRATION_23_24)
                            .addMigrations(MIGRATION_24_25)
                            .addMigrations(MIGRATION_25_26) // 注册 25 -> 26 迁移
                            .build();
        }
        return INSTANCE;
    }

    public static void destroyInstance() {
        INSTANCE = null;
    }

    public abstract IllustRecmdDao recmdDao();

    public abstract DownloadDao downloadDao();

    public abstract SearchDao searchDao();

    public abstract GeneralDao generalDao();

}

