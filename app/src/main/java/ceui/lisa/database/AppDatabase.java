package ceui.lisa.database;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import ceui.lisa.feature.FeatureEntity;
import ceui.pixiv.db.DiscoveryDao;
import ceui.pixiv.db.DiscoveryEntity;
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
                DiscoveryEntity.class, // 发现池候选作品
                NovelBookmarkEntity.class, // V3 阅读器书签
                NovelAnnotationEntity.class, // V3 阅读器划线/笔记
                NovelReadingStatsEntity.class, // V3 阅读器单本统计
                DailyReadingStatsEntity.class, // V3 阅读器每日统计
                NovelCustomThemeEntity.class, // V3 阅读器自定义主题
                NovelCustomFontEntity.class, // V3 阅读器自定义字体
                ComicBookmarkEntity.class, // V3 漫画阅读器书签
                ComicReadingStatsEntity.class, // V3 漫画阅读器累计统计
        },
        version = 32,
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
    private static final Migration MIGRATION_27_28 = new Migration(27, 28) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // 创建 discovery_table 发现池表
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS discovery_table (" +
                            "illustId INTEGER NOT NULL PRIMARY KEY, " +
                            "illustJson TEXT NOT NULL, " +
                            "score REAL NOT NULL, " +
                            "source TEXT NOT NULL, " +
                            "collectedTime INTEGER NOT NULL, " +
                            "shown INTEGER NOT NULL DEFAULT 0" +
                            ")"
            );
        }
    };
    private static final Migration MIGRATION_28_29 = new Migration(28, 29) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // discovery_table 新增 authorId 列，用于采样时的画师去重（避免逐条 JSON 反序列化）
            database.execSQL(
                    "ALTER TABLE discovery_table ADD COLUMN authorId INTEGER NOT NULL DEFAULT 0"
            );
        }
    };
    // 迁移 29 -> 30：V3 阅读器新增 6 张表（书签、划线/笔记、单本统计、每日统计、自定义主题、自定义字体）
    private static final Migration MIGRATION_29_30 = new Migration(29, 30) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS novel_bookmark_table (" +
                            "bookmarkId INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                            "novelId INTEGER NOT NULL, " +
                            "charIndex INTEGER NOT NULL, " +
                            "pageIndex INTEGER NOT NULL, " +
                            "preview TEXT NOT NULL, " +
                            "note TEXT NOT NULL DEFAULT '', " +
                            "createdTime INTEGER NOT NULL" +
                            ")"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_novel_bookmark_table_novelId ON novel_bookmark_table(novelId)"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_novel_bookmark_table_createdTime ON novel_bookmark_table(createdTime)"
            );

            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS novel_annotation_table (" +
                            "annotationId INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                            "novelId INTEGER NOT NULL, " +
                            "charStart INTEGER NOT NULL, " +
                            "charEnd INTEGER NOT NULL, " +
                            "excerpt TEXT NOT NULL, " +
                            "note TEXT NOT NULL DEFAULT '', " +
                            "color INTEGER NOT NULL, " +
                            "kind INTEGER NOT NULL DEFAULT 0, " +
                            "createdTime INTEGER NOT NULL, " +
                            "updatedTime INTEGER NOT NULL" +
                            ")"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_novel_annotation_table_novelId ON novel_annotation_table(novelId)"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_novel_annotation_table_novelId_charStart ON novel_annotation_table(novelId, charStart)"
            );

            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS novel_reading_stats_table (" +
                            "novelId INTEGER NOT NULL PRIMARY KEY, " +
                            "lastCharIndex INTEGER NOT NULL DEFAULT 0, " +
                            "lastPageIndex INTEGER NOT NULL DEFAULT 0, " +
                            "totalPageCount INTEGER NOT NULL DEFAULT 0, " +
                            "lastReadTime INTEGER NOT NULL DEFAULT 0, " +
                            "firstReadTime INTEGER NOT NULL DEFAULT 0, " +
                            "openCount INTEGER NOT NULL DEFAULT 0, " +
                            "totalDurationMs INTEGER NOT NULL DEFAULT 0, " +
                            "totalFlips INTEGER NOT NULL DEFAULT 0, " +
                            "totalCharsRead INTEGER NOT NULL DEFAULT 0, " +
                            "completed INTEGER NOT NULL DEFAULT 0" +
                            ")"
            );

            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS novel_daily_reading_stats_table (" +
                            "dayEpoch INTEGER NOT NULL PRIMARY KEY, " +
                            "durationMs INTEGER NOT NULL DEFAULT 0, " +
                            "charsRead INTEGER NOT NULL DEFAULT 0, " +
                            "flipCount INTEGER NOT NULL DEFAULT 0, " +
                            "novelsTouched INTEGER NOT NULL DEFAULT 0" +
                            ")"
            );

            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS novel_custom_theme_table (" +
                            "themeId INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                            "name TEXT NOT NULL, " +
                            "backgroundColor INTEGER NOT NULL, " +
                            "textColor INTEGER NOT NULL, " +
                            "secondaryTextColor INTEGER NOT NULL, " +
                            "accentColor INTEGER NOT NULL, " +
                            "linkColor INTEGER NOT NULL, " +
                            "selectionColor INTEGER NOT NULL, " +
                            "highlightColor INTEGER NOT NULL, " +
                            "dividerColor INTEGER NOT NULL, " +
                            "chapterTitleColor INTEGER NOT NULL, " +
                            "isDark INTEGER NOT NULL, " +
                            "backgroundImagePath TEXT, " +
                            "createdTime INTEGER NOT NULL" +
                            ")"
            );

            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS novel_custom_font_table (" +
                            "fontId INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                            "displayName TEXT NOT NULL, " +
                            "relativePath TEXT NOT NULL, " +
                            "originalUri TEXT NOT NULL, " +
                            "byteSize INTEGER NOT NULL, " +
                            "installedTime INTEGER NOT NULL" +
                            ")"
            );
        }
    };
    private static final Migration MIGRATION_31_32 = new Migration(31, 32) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS comic_reading_stats_table (" +
                            "illustId INTEGER NOT NULL PRIMARY KEY, " +
                            "lastPageIndex INTEGER NOT NULL DEFAULT 0, " +
                            "totalPageCount INTEGER NOT NULL DEFAULT 0, " +
                            "lastReadTime INTEGER NOT NULL DEFAULT 0, " +
                            "firstReadTime INTEGER NOT NULL DEFAULT 0, " +
                            "openCount INTEGER NOT NULL DEFAULT 0, " +
                            "totalDurationMs INTEGER NOT NULL DEFAULT 0, " +
                            "totalFlips INTEGER NOT NULL DEFAULT 0, " +
                            "completed INTEGER NOT NULL DEFAULT 0" +
                            ")"
            );
        }
    };
    private static final Migration MIGRATION_30_31 = new Migration(30, 31) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS comic_bookmark_table (" +
                            "bookmarkId INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                            "illustId INTEGER NOT NULL, " +
                            "pageIndex INTEGER NOT NULL, " +
                            "totalPages INTEGER NOT NULL, " +
                            "preview_url TEXT NOT NULL, " +
                            "note TEXT NOT NULL, " +
                            "createdTime INTEGER NOT NULL" +
                            ")"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_comic_bookmark_table_illustId " +
                            "ON comic_bookmark_table (illustId)"
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
                            .addMigrations(MIGRATION_26_27) // 注册 26 -> 27 迁移
                            .addMigrations(MIGRATION_27_28) // 注册 27 -> 28 迁移 (discovery_table)
                            .addMigrations(MIGRATION_28_29) // 注册 28 -> 29 迁移 (discovery_table + authorId)
                            .addMigrations(MIGRATION_29_30) // 注册 29 -> 30 迁移 (V3 阅读器 6 张表)
                            .addMigrations(MIGRATION_30_31) // 注册 30 -> 31 迁移 (V3 漫画书签)
                            .addMigrations(MIGRATION_31_32) // 注册 31 -> 32 迁移 (V3 漫画累计统计)
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

    public abstract DiscoveryDao discoveryDao();

    public abstract NovelBookmarkDao novelBookmarkDao();

    public abstract NovelAnnotationDao novelAnnotationDao();

    public abstract NovelReadingStatsDao novelReadingStatsDao();

    public abstract DailyReadingStatsDao dailyReadingStatsDao();

    public abstract NovelCustomThemeDao novelCustomThemeDao();

    public abstract NovelCustomFontDao novelCustomFontDao();

    public abstract ComicBookmarkDao comicBookmarkDao();

    public abstract ComicReadingStatsDao comicReadingStatsDao();

}

