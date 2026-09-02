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
import ceui.pixiv.db.FeedCacheDao;
import ceui.pixiv.db.FeedCacheEntity;
import ceui.pixiv.db.GeneralDao;
import ceui.pixiv.db.GeneralEntity;
import ceui.pixiv.db.RemoteKey;
import ceui.pixiv.db.mirror.BookmarkMirrorDao;
import ceui.pixiv.db.mirror.BookmarkMirrorEntity;
import ceui.pixiv.db.mirror.BookmarkMirrorStateEntity;
import ceui.pixiv.db.mirror.BookmarkMirrorTagEntity;
import ceui.pixiv.db.queue.DownloadQueueDao;
import ceui.pixiv.db.queue.DownloadQueueEntity;
import ceui.pixiv.db.synonym.SynonymDao;
import ceui.pixiv.db.synonym.SynonymTagEntity;
import ceui.pixiv.db.synonym.SynonymTargetEntity;

@Database(
        entities = {
                IllustHistoryEntity.class, //浏览历史
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
                DownloadQueueEntity.class, // 批量下载队列（v33）
                SynonymTargetEntity.class, // 同义词词典-目标标签（v36 建表 / v37 加 lastUsedAt, issue #904/#910）
                SynonymTagEntity.class, // 同义词词典-同义词（v36, issue #904）
                FeedCacheEntity.class, // feeds 框架本地优先首屏快照（v39）
                BookmarkMirrorEntity.class, // 收藏镜像主表（v42）
                BookmarkMirrorTagEntity.class, // 收藏镜像标签倒排表（v42）
                BookmarkMirrorStateEntity.class, // 收藏镜像每书架同步状态（v42）
        },
        version = AppDatabase.VERSION,
        exportSchema = true
)
public abstract class AppDatabase extends RoomDatabase {

    public static final int VERSION = 43;
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
    // 迁移 32 -> 33：批量下载持久化队列表
    private static final Migration MIGRATION_32_33 = new Migration(32, 33) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS download_queue (" +
                            "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                            "illustId INTEGER NOT NULL, " +
                            "type TEXT NOT NULL, " +
                            "seq INTEGER NOT NULL, " +
                            "sourceTag TEXT NOT NULL, " +
                            "status TEXT NOT NULL, " +
                            "errorMsg TEXT, " +
                            "retryCount INTEGER NOT NULL DEFAULT 0, " +
                            "createdAt INTEGER NOT NULL, " +
                            "finishedAt INTEGER" +
                            ")"
            );
            database.execSQL("CREATE INDEX IF NOT EXISTS index_download_queue_status ON download_queue(status)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_download_queue_seq ON download_queue(seq)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_download_queue_illustId ON download_queue(illustId)");
        }
    };
    // 关于 "ONE downloading" 不变量：原本计划用 partial unique index 在 DB 层强制
    //   CREATE UNIQUE INDEX uniq_download_queue_one_downloading ON download_queue(status) WHERE status = 'DOWNLOADING'
    // 但 androidx.room.Index 不支持 partial 索引（无 where 子句），手写 migration 加索引会让
    // Room 启动时 validateMigration 失败。改为 QueueDownloadManager.consumeUntilEmpty 里
    // mark DOWNLOADING 前 count 一次的运行时检查；reasonable，因为只有一个 consumer 写状态。

    // 迁移 33 -> 34：download_queue 加 illustGson 列。入队时序列化 Illust 进 DB，
    // 让 consumer / 队列 tab 显示 都不必再打 getIllustByID（冷启动 100+ PENDING 一拥而上
    // 会被 pixiv 429）。老行的 illustGson 为 null，consumer 会 fallback 到 API。
    private static final Migration MIGRATION_33_34 = new Migration(33, 34) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE download_queue ADD COLUMN illustGson TEXT");
        }
    };
    // 迁移 34 -> 35：固定标签存预览 illust json（shape 见 SearchEntity.previewIllustsJson）
    private static final Migration MIGRATION_34_35 = new Migration(34, 35) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE search_table ADD COLUMN previewIllustsJson TEXT");
        }
    };
    // 迁移 35 -> 36：同义词词典两张表（issue #904 按标签收藏优化）
    // 目标标签（收藏夹标签）<- 1:N -> 同义词标签（作品标签别名，备注不参与匹配）
    private static final Migration MIGRATION_35_36 = new Migration(35, 36) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS synonym_target_table (" +
                            "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                            "name TEXT NOT NULL, " +
                            "createdAt INTEGER NOT NULL" +
                            ")"
            );
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_synonym_target_table_name ON synonym_target_table(name)");
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS synonym_tag_table (" +
                            "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                            "targetId INTEGER NOT NULL, " +
                            "name TEXT NOT NULL, " +
                            "remark TEXT, " +
                            "createdAt INTEGER NOT NULL" +
                            ")"
            );
            database.execSQL("CREATE INDEX IF NOT EXISTS index_synonym_tag_table_targetId ON synonym_tag_table(targetId)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_synonym_tag_table_name ON synonym_tag_table(name)");
        }
    };
    // 迁移 36 -> 37：目标标签加 lastUsedAt（issue #910）。「添加为同义词」/「移动」菜单改按最近使用排序，
    // 旧行回填为 createdAt（与原「按创建时间倒序」行为一致，不会打乱已有顺序）。
    private static final Migration MIGRATION_36_37 = new Migration(36, 37) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE synonym_target_table ADD COLUMN lastUsedAt INTEGER NOT NULL DEFAULT 0");
            database.execSQL("UPDATE synonym_target_table SET lastUsedAt = createdAt");
        }
    };

    /**
     * v37 -> v38：给 illust_download_table 加索引列 illustId。
     * hasDownloadRecordByIllustId 从对 illustGson blob 的全表 LIKE 扫描（30000+ 行、2GB+，
     * 单次几百 ms~秒级、烧 CPU 又占读连接 → 详情/头像页发涩）改走索引 O(log n)。
     * ADD COLUMN 是 O(1) 不重写行；存量行 illustId 先留 0，由 DownloadIdBackfill 后台一次性
     * 回填。索引名必须与 Room 由 @Index("illustId") 生成的一致：index_<table>_<column>。
     */
    private static final Migration MIGRATION_37_38 = new Migration(37, 38) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE illust_download_table ADD COLUMN illustId INTEGER NOT NULL DEFAULT 0");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_illust_download_table_illustId ON illust_download_table(illustId)");
        }
    };

    // 迁移 38 -> 39：feeds 框架「本地优先」首屏快照表。一个可缓存 feed 一行、自我覆盖，
    // 只存首屏原始响应 JSON（往后翻页仍走网络）。cacheKey 含账号命名空间；savedAt 建索引
    // 供 LRU 淘汰 / 过期判定排序。列名 / 类型 / 可空性必须与 FeedCacheEntity 完全一致。
    private static final Migration MIGRATION_38_39 = new Migration(38, 39) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS feed_cache_table (" +
                            "cacheKey TEXT NOT NULL PRIMARY KEY, " +
                            "schemaVersion INTEGER NOT NULL, " +
                            "payloadJson TEXT NOT NULL, " +
                            "nextCursor TEXT, " +
                            "savedAt INTEGER NOT NULL" +
                            ")"
            );
            database.execSQL("CREATE INDEX IF NOT EXISTS index_feed_cache_table_savedAt ON feed_cache_table(savedAt)");
        }
    };

    // 迁移 39 -> 40：删掉 illust_recmd_table（五年前的简陋版"本地优先"调试表——localData()/
    // showDataBase() 从未被真正触发过，已被 feeds 框架的 FeedFirstPageCache 取代）。
    private static final Migration MIGRATION_39_40 = new Migration(39, 40) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DROP TABLE IF EXISTS illust_recmd_table");
        }
    };

    /**
     * v40 -> v41：给 illust_download_table 加 page 列 + (illustId, page) 复合索引。
     *
     * 之前"这一页下过没"只能用 FileCreator.customFileName 算出**当前模板**下的文件名去查
     * 主键 —— 用户换过命名模板、或记录是 DownloadImporter 从旧版命名的文件扫进来的
     * （issue #953），就永远查不中：徽标显示未下载、「已存在则跳过」失效、详情页也复用不了
     * 本地文件。加这一列后按 (作品, 页码) 查，跟文件叫什么名字彻底解耦。
     *
     * ADD COLUMN 是 O(1) 不重写行；存量行 page 先留 -1（= 未知，查询自然落空并退回旧的
     * fileName 路径，无回归），由 DownloadPageBackfill 后台一次性回填。
     * 索引名必须与 Room 由 @Index({"illustId","page"}) 生成的一致：index_<table>_<col>_<col>。
     */
    private static final Migration MIGRATION_40_41 = new Migration(40, 41) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE illust_download_table ADD COLUMN page INTEGER NOT NULL DEFAULT -1");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_illust_download_table_illustId_page ON illust_download_table(illustId, page)");
        }
    };

    /**
     * v41 -> v42：收藏镜像三张表（主表 / 标签倒排表 / 每书架同步状态）。
     *
     * 建表而不改表，所以对存量数据零影响；建完是空的，由
     * {@link ceui.pixiv.db.mirror.BookmarkMirrorService} 在用户第一次打开收藏页时
     * 限速后台回填。索引数量偏多是**故意的**：这张表存在的全部意义就是让「按收藏顺序倒序 /
     * 按作者 / 按热度 / 按年份 / 按标签」这些服务端给不了的排序筛选在本地即时出结果，
     * 而写入只发生在每 5 秒一页的后台回填里，写放大完全不在用户感知路径上。
     *
     * 索引名必须与 Room 由 @Index 生成的一致：index_&lt;table&gt;_&lt;col&gt;_&lt;col&gt;…
     */
    private static final Migration MIGRATION_41_42 = new Migration(41, 42) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS bookmark_mirror_table (" +
                            "shelfKey TEXT NOT NULL, " +
                            "targetId INTEGER NOT NULL, " +
                            "ownerUid INTEGER NOT NULL, " +
                            "contentType INTEGER NOT NULL, " +
                            "restrictCode INTEGER NOT NULL, " +
                            "bookmarkSeq INTEGER NOT NULL, " +
                            "payloadJson TEXT NOT NULL, " +
                            "title TEXT NOT NULL, " +
                            "authorId INTEGER NOT NULL, " +
                            "authorName TEXT NOT NULL, " +
                            "workType TEXT NOT NULL, " +
                            "pageCount INTEGER NOT NULL, " +
                            "width INTEGER NOT NULL, " +
                            "height INTEGER NOT NULL, " +
                            "aspectRatio REAL NOT NULL, " +
                            "orientation INTEGER NOT NULL, " +
                            "totalBookmarks INTEGER NOT NULL, " +
                            "totalView INTEGER NOT NULL, " +
                            "textLength INTEGER NOT NULL, " +
                            "createDateMs INTEGER NOT NULL, " +
                            "aiType INTEGER NOT NULL, " +
                            "xRestrict INTEGER NOT NULL, " +
                            "sanityLevel INTEGER NOT NULL, " +
                            "isVisible INTEGER NOT NULL, " +
                            "isMuted INTEGER NOT NULL, " +
                            "seriesId INTEGER NOT NULL, " +
                            "tagCount INTEGER NOT NULL, " +
                            "searchText TEXT NOT NULL, " +
                            "syncedAt INTEGER NOT NULL, " +
                            "generation INTEGER NOT NULL, " +
                            "PRIMARY KEY(shelfKey, targetId)" +
                            ")"
            );
            database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_mirror_table_shelfKey_bookmarkSeq ON bookmark_mirror_table(shelfKey, bookmarkSeq)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_mirror_table_shelfKey_createDateMs ON bookmark_mirror_table(shelfKey, createDateMs)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_mirror_table_shelfKey_totalBookmarks ON bookmark_mirror_table(shelfKey, totalBookmarks)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_mirror_table_shelfKey_totalView ON bookmark_mirror_table(shelfKey, totalView)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_mirror_table_shelfKey_textLength ON bookmark_mirror_table(shelfKey, textLength)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_mirror_table_shelfKey_title ON bookmark_mirror_table(shelfKey, title)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_mirror_table_shelfKey_authorId_bookmarkSeq ON bookmark_mirror_table(shelfKey, authorId, bookmarkSeq)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_mirror_table_shelfKey_seriesId ON bookmark_mirror_table(shelfKey, seriesId)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_mirror_table_shelfKey_workType_bookmarkSeq ON bookmark_mirror_table(shelfKey, workType, bookmarkSeq)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_mirror_table_shelfKey_xRestrict_bookmarkSeq ON bookmark_mirror_table(shelfKey, xRestrict, bookmarkSeq)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_mirror_table_shelfKey_aiType_bookmarkSeq ON bookmark_mirror_table(shelfKey, aiType, bookmarkSeq)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_mirror_table_shelfKey_pageCount_bookmarkSeq ON bookmark_mirror_table(shelfKey, pageCount, bookmarkSeq)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_mirror_table_shelfKey_orientation_bookmarkSeq ON bookmark_mirror_table(shelfKey, orientation, bookmarkSeq)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_mirror_table_shelfKey_generation ON bookmark_mirror_table(shelfKey, generation)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_mirror_table_targetId ON bookmark_mirror_table(targetId)");

            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS bookmark_mirror_tag_table (" +
                            "shelfKey TEXT NOT NULL, " +
                            "targetId INTEGER NOT NULL, " +
                            "tagName TEXT NOT NULL, " +
                            "displayName TEXT NOT NULL, " +
                            "translatedName TEXT NOT NULL, " +
                            "PRIMARY KEY(shelfKey, targetId, tagName)" +
                            ")"
            );
            database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_mirror_tag_table_shelfKey_tagName ON bookmark_mirror_tag_table(shelfKey, tagName)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_mirror_tag_table_shelfKey_targetId ON bookmark_mirror_tag_table(shelfKey, targetId)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_mirror_tag_table_targetId ON bookmark_mirror_tag_table(targetId)");

            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS bookmark_mirror_state_table (" +
                            "shelfKey TEXT NOT NULL, " +
                            "ownerUid INTEGER NOT NULL, " +
                            "contentType INTEGER NOT NULL, " +
                            "restrictCode INTEGER NOT NULL, " +
                            "phase INTEGER NOT NULL, " +
                            "nextUrl TEXT, " +
                            "generation INTEGER NOT NULL, " +
                            "nextBackfillSeq INTEGER NOT NULL, " +
                            "headSeqCursor INTEGER NOT NULL, " +
                            "headBlockCeiling INTEGER NOT NULL, " +
                            "pagesThisRun INTEGER NOT NULL, " +
                            "itemsThisRun INTEGER NOT NULL, " +
                            "firstCompletedAt INTEGER NOT NULL, " +
                            "lastSyncedAt INTEGER NOT NULL, " +
                            "lastFullSweepAt INTEGER NOT NULL, " +
                            "lastErrorAt INTEGER NOT NULL, " +
                            "lastError TEXT, " +
                            "consecutiveFailures INTEGER NOT NULL, " +
                            "cooldownUntil INTEGER NOT NULL, " +
                            "updatedAt INTEGER NOT NULL, " +
                            "PRIMARY KEY(shelfKey)" +
                            ")"
            );
        }
    };

    /**
     * v42 -> v43：收藏镜像主表补 (shelfKey, aspectRatio) 索引，给「宽高比 · 最竖长 / 最横扁」
     * 两个排序用。只加索引不动数据；索引名必须与 Room 由 @Index 生成的一致。
     */
    private static final Migration MIGRATION_42_43 = new Migration(42, 43) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_mirror_table_shelfKey_aspectRatio ON bookmark_mirror_table(shelfKey, aspectRatio)");
        }
    };

    private static final Migration[] ALL_MIGRATIONS = {
            MIGRATION_23_24,
            MIGRATION_24_25,
            MIGRATION_25_26,
            MIGRATION_26_27,
            MIGRATION_27_28,
            MIGRATION_28_29,
            MIGRATION_29_30,
            MIGRATION_30_31,
            MIGRATION_31_32,
            MIGRATION_32_33,
            MIGRATION_33_34,
            MIGRATION_34_35,
            MIGRATION_35_36,
            MIGRATION_36_37,
            MIGRATION_37_38,
            MIGRATION_38_39,
            MIGRATION_39_40,
            MIGRATION_40_41,
            MIGRATION_41_42,
            MIGRATION_42_43,
    };

    /**
     * Production and migration tests share one registry so adding a migration cannot update one
     * path while silently leaving the other stale.
     */
    public static Migration[] migrations() {
        return ALL_MIGRATIONS.clone();
    }

    private static AppDatabase INSTANCE;

    // synchronized：同义词词典（issue #904）让后台线程（SelectTagFeedSource 的同义词勾选）也会触发
    // 首次初始化，与主线程并发 check-then-act 会 double-build 两个 RoomDatabase 实例写同一文件。
    public static synchronized AppDatabase getAppDatabase(Context context) {
        if (INSTANCE == null) {
            INSTANCE =
                    Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, DATABASE_NAME)
                            // allow queries on the main thread.
                            // Don't do this on a real app! See PersistenceBasicSample for an example.
                            //.fallbackToDestructiveMigration()
                            .allowMainThreadQueries()
                            .addMigrations(ALL_MIGRATIONS)
                            .build();
        }
        return INSTANCE;
    }

    public static void destroyInstance() {
        INSTANCE = null;
    }

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

    /** 收藏镜像（v42）：本地整份镜像收藏列表，支撑倒序与花式筛选。 */
    public abstract BookmarkMirrorDao bookmarkMirrorDao();

    public abstract ComicReadingStatsDao comicReadingStatsDao();

    public abstract DownloadQueueDao downloadQueueDao();

    public abstract SynonymDao synonymDao();

    public abstract FeedCacheDao feedCacheDao();

}
