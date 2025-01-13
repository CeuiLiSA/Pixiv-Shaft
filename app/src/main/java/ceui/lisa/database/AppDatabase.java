package ceui.lisa.database;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import ceui.lisa.feature.FeatureEntity;

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
        },
        version = 25,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    public static final String DATABASE_NAME = "roomDemo-database";

    private static AppDatabase INSTANCE;

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
}

