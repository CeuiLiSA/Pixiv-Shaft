package ceui.lisa.database;

import android.database.Cursor;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

import ceui.lisa.feature.FeatureEntity;
import kotlinx.coroutines.flow.Flow;

//保存下载历史记录
@Dao
public interface DownloadDao {

    /**
     * 添加一个下载记录
     *
     * @param illustTask
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(DownloadEntity illustTask);

    /**
     * 统一的下载记录写入口：插入前若 illustId 未定（<=0），先由 illustGson 顶层 "id" 算出，
     * 保证每条新行都带正确 illustId —— 否则 [DownloadIdBackfill] 跑完后新行 illustId=0，
     * 索引查不到 → “已下载”徽标误判成没下过。调用方若已知 id 可先 {@code setIllustId(...)}
     * 跳过解析（Manager 下载完成分支就直接传 illust.id）。所有往 illust_download_table 写
     * DownloadEntity 的地方都必须走这个而不是裸 {@link #insert(DownloadEntity)}。
     */
    default void insertDownload(DownloadEntity entity) {
        if (entity.getIllustId() <= 0L) {
            entity.setIllustId(DownloadIdExtractor.extractIllustId(entity.getIllustGson()));
        }
        insert(entity);
    }

    /**
     * 批量写入且**不覆盖**已有行 —— 只给 {@code DownloadImporter}（扫描导入本地文件，
     * issue #953）用。
     *
     * 绝不能让导入走 {@link #insertDownload}：那条路是 REPLACE，而本表主键只有
     * fileName。用户盘上一个同名文件就会把真实下载记录整行顶掉（丢 filePath 和完整
     * illustGson），扫一次目录能静默毁掉一大片记录。IGNORE 语义下已有行原样保留，
     * 导入是纯增量的，重复扫同一个目录也是幂等的。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertIgnoreAll(List<DownloadEntity> entities);

    /**
     * 这批 fileName 里哪些已经在库里 —— 导入前算"将跳过多少"用，让用户在真正写库
     * 之前就看到准确的数字。调用方需按 SQLite 变量上限（999）分片。
     */
    @Query("SELECT fileName FROM illust_download_table WHERE fileName IN (:fileNames)")
    List<String> filterExistingFileNames(List<String> fileNames);

    /**
     * 把某个作品所有下载行的 illustGson 换成完整版。导入时先写的是
     * {@code {"id":123}} 这种最小 JSON，{@code ImportMetadataEnricher} 回
     * v1/illust/detail 拉到真数据后用这个覆盖，卡片上就有标题 / 作者 / 封面了。
     */
    @Query("UPDATE illust_download_table SET illustGson = :illustGson WHERE illustId = :illustId")
    void updateIllustGsonByIllustId(long illustId, String illustGson);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertDownloading(DownloadingEntity entity);

    @Delete
    void deleteDownloading(DownloadingEntity entity);

    /**
     * 删除一条下载记录
     *
     * @param userEntity
     */
    @Delete
    void delete(DownloadEntity userEntity);

    /**
     * 获取全部下载记录
     *
     * @param limit
     * @param offset
     * @return
     */
    @Query("SELECT * FROM illust_download_table ORDER BY downloadTime DESC LIMIT :limit OFFSET :offset")
    List<DownloadEntity> getAll(int limit, int offset);

    /**
     * 模糊搜索已下载记录。在 fileName 和 illustGson（含 title / user.name 等
     * 反序列化前的 JSON 文本）两个字段里 LIKE 命中。LIMIT 600 与
     * [DoneListV3Fragment.PAGE_SIZE] 一致，避免搜索后看到的卡比平时还多。
     */
    @Query("SELECT * FROM illust_download_table WHERE " +
            "fileName LIKE '%' || :keyword || '%' OR " +
            "illustGson LIKE '%' || :keyword || '%' " +
            "ORDER BY downloadTime DESC LIMIT 600")
    List<DownloadEntity> searchDownloads(String keyword);

    /**
     * Reactive 列表：Room InvalidationTracker 在 illust_download_table 任意
     * 变更时自动 emit 新快照。已完成 tab 用这个替代 1.5s 轮询 + DOWNLOAD_FINISH
     * 广播兜底 —— Manager 写 DownloadEntity 时 Room 自己就会通知 collector。
     */
    @Query("SELECT * FROM illust_download_table ORDER BY downloadTime DESC LIMIT :limit")
    Flow<List<DownloadEntity>> flowAll(int limit);

    /**
     * 判断是否存在指定插画 id 的下载记录（通过 illustGson 中的 "id":xxx 片段匹配）。
     * 兼容 id 作为最后一个字段（以 `}` 结尾）和非末尾字段（以 `,` 结尾）两种 Gson 序列化顺序。
     */
    @Query("SELECT COUNT(*) > 0 FROM illust_download_table WHERE " +
            "illustGson LIKE '%\"id\":' || :illustId || ',%' OR " +
            "illustGson LIKE '%\"id\":' || :illustId || '}%'")
    boolean hasDownloadRecordByIllustId(long illustId);

    /**
     * illustId 走索引的 O(log n) 版本，取代 {@link #hasDownloadRecordByIllustId} 对 illustGson
     * blob 的全表 LIKE 扫描（2GB+ 库单次几百 ms~秒级、还烧 CPU 占读连接 → 详情/头像页发涩）。
     * v38 起 illust_download_table 有 illustId 索引列（插入即算、存量后台回填）。回填未完成
     * 期间仍需 LIKE 版兜底，统一入口见 {@code DownloadStateProbeKt.hasDownloadRecord}。
     */
    @Query("SELECT COUNT(*) > 0 FROM illust_download_table WHERE illustId = :illustId")
    boolean hasDownloadRecordByIllustIdIndexed(long illustId);

    // ---- v38 illustId 索引列的存量回填（DownloadIdBackfill 用）----

    /** 还有多少行没回填（illustId 仍是初始的 0）。 */
    @Query("SELECT COUNT(*) FROM illust_download_table WHERE illustId = 0")
    int countDownloadsNeedingIdBackfill();

    /** 取一批未回填的行（只带回填要用的 fileName + illustGson）。WHERE illustId=0 走索引，不全表扫。 */
    @Query("SELECT fileName, illustGson FROM illust_download_table WHERE illustId = 0 LIMIT :limit")
    List<DownloadIdRow> getDownloadsNeedingIdBackfill(int limit);

    /** 回填单行的 illustId（按主键 fileName 定位）。 */
    @Query("UPDATE illust_download_table SET illustId = :illustId WHERE fileName = :fileName")
    void setDownloadIllustId(String fileName, long illustId);

    /**
     * 按 fileName(主键)精确取一条下载记录。详情页把每页用 FileCreator.customFileName
     * 算出的文件名直接拿来查,命中就读本地文件复用下载,不回 pixiv 重新下。走主键索引,
     * 30000+ 条下载库下也是 O(log n),不像 illustGson LIKE 那样全表扫 blob。
     */
    @Query("SELECT * FROM illust_download_table WHERE fileName = :fileName LIMIT 1")
    DownloadEntity getDownloadByFileName(String fileName);

    /**
     * 详情页一次取回本作品所有候选页。fileName 是主键，IN 查询仍走主键索引；相比逐页调用
     * {@link #getDownloadByFileName}，多 P 作品从 N 次 Room/SQLite 往返降为 1 次。
     */
    @Query("SELECT * FROM illust_download_table WHERE fileName IN (:fileNames)")
    List<DownloadEntity> getDownloadsByFileNames(List<String> fileNames);

    /**
     * 按 (作品, 页码) 取这个作品已下载的页。v41 的 {@code (illustId, page)} 复合索引，O(log n)。
     *
     * <p>取代 {@link #getDownloadsByFileNames} 那条"先用当前模板算出文件名再查主键"的路：
     * 用户换过命名模板、或记录是 {@code DownloadImporter} 从旧版命名的文件扫进来的
     * （issue #953），文件名根本对不上，只有按 id + 页码查才命中。调用方仍应在这里落空时
     * 退回 fileName 查询 —— v41 之前的存量行 page 是 -1，回填跑完前查不到。
     *
     * <p>投影成 {@link DownloadedPage} 而不是 {@code SELECT *}：illustGson 单行几 KB，
     * 172P 的作品一次就是近 1MB 无谓 blob 读，而 feed 里每张卡片都会建一个
     * {@code IllustAdapter} 调这里。理由详见 {@link DownloadedPage}。
     */
    @Query("SELECT fileName, filePath, page FROM illust_download_table " +
            "WHERE illustId = :illustId AND page >= 0 ORDER BY downloadTime DESC")
    List<DownloadedPage> getDownloadedPages(long illustId);

    /**
     * 同一页的全部候选，新的记录优先。不能 {@code LIMIT 1}：用户换模板、重复下载或导入
     * 旧目录后，同一个 (illustId,page) 可能有多行；任取一行若刚好已删除/失权，会无视
     * 另一条仍可读的记录而再次下载。只投影三列，避免批量下载逐页探测时读取
     * {@code illustGson} 大字段。
     */
    @Query("SELECT fileName, filePath, page FROM illust_download_table " +
            "WHERE illustId = :illustId AND page = :page ORDER BY downloadTime DESC")
    List<DownloadedPage> getDownloadedPageCandidates(long illustId, int page);

    // ---- v41 page 列的存量回填（DownloadPageBackfill 用）----

    /**
     * 取一批还有行没回填 page 的作品 id。
     *
     * <p><b>按作品取而不是按行取</b>：页码基准（文件名里的 {@code p0} 起还是 {@code p1} 起）
     * 单看一个文件名判不出来，必须拿同一作品所有页一起推（见
     * {@code PageBaseInference}）。逐行回填会在基准判错时把第 N 页的本地图错配到第 N±1 页
     * —— 那比"查不到"还糟。
     *
     * <p>只投影 illustId，不碰 illustGson 那个 blob 列（30000+ 行 2GB+，扫它正是 v38 一路
     * 在躲的事）。{@code (illustId, page)} 复合索引覆盖了整个查询。
     */
    @Query("SELECT DISTINCT illustId FROM illust_download_table WHERE page = -1 AND illustId > 0 LIMIT :limit")
    List<Long> getIllustIdsNeedingPageBackfill(int limit);

    /** 某个作品还没回填 page 的全部文件名。走 {@code (illustId, page)} 索引。 */
    @Query("SELECT fileName FROM illust_download_table WHERE illustId = :illustId AND page = -1")
    List<String> getFileNamesNeedingPageBackfill(long illustId);

    /** 回填单行的 page（按主键 fileName 定位）。 */
    @Query("UPDATE illust_download_table SET page = :page WHERE fileName = :fileName")
    void setDownloadPage(String fileName, int page);

    /**
     * 重命名一条下载记录（issue #567 的批量重命名）：文件在磁盘上改名成功后，把主键
     * fileName 和 filePath（SAF 重命名后 document uri 会变）一起改过来。fileName 是主键，
     * 目标名已存在时 SQLite 抛 constraint 异常 —— 调用方（RenameSweeper）在计划阶段已
     * 去重，仍冲突则捕获并把磁盘文件名改回去，保持记录与磁盘一致。
     */
    @Query("UPDATE illust_download_table SET fileName = :newFileName, filePath = :newFilePath WHERE fileName = :oldFileName")
    void renameDownload(String oldFileName, String newFileName, String newFilePath);

    @Query("SELECT IFNULL(MAX(rowid), 0) FROM illust_downloading_table")
    long getDownloadingHighWaterMark();

    /** Keyset paging keeps enqueue order when earlier rows are deleted during recovery. */
    @Query("SELECT rowid, taskGson FROM illust_downloading_table "
            + "WHERE rowid > :after AND rowid <= :through ORDER BY rowid ASC LIMIT :limit")
    Cursor getDownloadingBatch(long after, long through, int limit);

    /**
     *
     */
    @Query("DELETE FROM illust_download_table")
    void deleteAllDownload();

    /**
     * 给 BulkDownloadCacheCleaner 估"清出来多少字节"用 —— 单 illustGson 列就是占用大头,
     * 元数据/index 不算在用户感知的"瘦身额度"里。
     */
    @Query("SELECT IFNULL(SUM(LENGTH(illustGson)), 0) FROM illust_download_table")
    long sumIllustGsonBytes();

    @Query("DELETE FROM illust_downloading_table")
    void deleteAllDownloading();


    /**
     * 新增一个浏览历史
     *
     * @param illustHistoryEntity
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(IllustHistoryEntity illustHistoryEntity);

    /**
     * 批量写入浏览历史。备份还原走这里:一批一个事务,几千上万行逐条 insert
     * (每条一个事务)在低端机上要跑几十秒(#981)。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertHistories(List<IllustHistoryEntity> illustHistoryEntities);

    /**
     * 删除一个浏览历史
     *
     * @param userEntity
     */
    @Delete
    void delete(IllustHistoryEntity userEntity);

    /**
     *
     */
    @Query("DELETE FROM illust_table")
    void deleteAllHistory();

    /**
     * 分页查询所有浏览历史
     *
     * @param limit
     * @param offset
     * @return
     */
    @Query("SELECT * FROM illust_table ORDER BY time DESC LIMIT :limit OFFSET :offset")
    List<IllustHistoryEntity> getAllViewHistory(int limit, int offset);

    /**
     * 只取 illustID 列,给 DiscoveryPool 之类只需要 id 集合的调用方用。
     * 全表 SELECT * 会把每行 illustJson 一起塞进 CursorWindow,历史攒多了就 OOM。
     */
    @Query("SELECT illustID FROM illust_table")
    List<Integer> getAllViewHistoryIds();

    /**
     * 浏览历史总条数
     */
    @Query("SELECT COUNT(*) FROM illust_table")
    int getViewHistoryCount();

    /**
     * 按 type 分页查询浏览历史（0=插画/漫画, 1=小说）
     */
    @Query("SELECT * FROM illust_table WHERE type = :type ORDER BY time DESC LIMIT :limit OFFSET :offset")
    List<IllustHistoryEntity> getViewHistoryByType(int type, int limit, int offset);

    @Query("SELECT COUNT(*) FROM illust_table WHERE type = :type")
    int getViewHistoryCountByType(int type);

    /**
     * 按 id 批量取 (illustID, time, type) 投影,云端历史物化回写(#989)用来做 LWW 比较。
     * type 必须带上:本表主键只有 illustID,小说与插画同号会同槽,物化侧靠它拒绝跨类覆盖。
     * 调用方一次最多传一页(≤100 个 id),不会撞 SQLite 999 变量上限。
     */
    @Query("SELECT illustID, time, type FROM illust_table WHERE illustID IN (:ids)")
    List<HistoryIdTime> getViewHistoryTimes(List<Integer> ids);

    /**
     * 云端回填(#989)用的 keyset 分页:严格按 time 递减往老走。不能用 LIMIT/OFFSET——
     * 回填期间用户继续浏览会往表头插新行,offset 整体后移,页边界的老记录会被跳过,
     * 而回填按 uid 只跑一次,漏了就永远漏了。同一毫秒的并列行会被 &lt; 略过,可忽略。
     */
    @Query("SELECT * FROM illust_table WHERE type = :type AND time < :beforeTime ORDER BY time DESC LIMIT :limit")
    List<IllustHistoryEntity> getViewHistoryByTypeBefore(int type, long beforeTime, int limit);

    @Query("DELETE FROM illust_table WHERE type = :type")
    void deleteAllHistoryByType(int type);

    /**
     * 全库模糊搜索浏览历史。LIKE 命中 illustJson 字段 —— Pixiv 的 title /
     * user.name / tags 都会落在序列化后的 JSON 文本里，搜索词命中其中任意
     * 子串即返回。限 200 条，避免内存爆。
     */
    @Query("SELECT * FROM illust_table WHERE illustJson LIKE '%' || :keyword || '%' ORDER BY time DESC LIMIT 200")
    List<IllustHistoryEntity> searchViewHistory(String keyword);

    /**
     * type-aware 全库模糊搜索：浏览历史 tabs 的「插画」「小说」分 tab 各自要
     * 按 type 单独过滤。LIKE illustJson 命中 title/user.name 等子串。
     */
    @Query("SELECT * FROM illust_table WHERE type = :type AND illustJson LIKE '%' || :keyword || '%' ORDER BY time DESC LIMIT 200")
    List<IllustHistoryEntity> searchViewHistoryByType(String keyword, int type);


    /**
     * 新增一个用户
     *
     * @param userEntity
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertUser(UserEntity userEntity);


    /**
     * 删除一个用户
     *
     * @param userEntity
     */
    @Delete
    void deleteUser(UserEntity userEntity);

    @Query("SELECT * FROM user_table ORDER BY loginTime DESC")
    List<UserEntity> getAllUser();

    @Query("SELECT * FROM user_table limit 1")
    UserEntity getCurrentUser();

    @Query("SELECT * FROM upload_image_table ORDER BY uploadTime DESC")
    List<ImageEntity> getUploadedImage();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertUploadedImage(ImageEntity imageEntity);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertFeature(FeatureEntity holder);

    @Query("SELECT * FROM feature_table ORDER BY dateTime DESC LIMIT :limit OFFSET :offset")
    List<FeatureEntity> getFeatureList(int limit, int offset);

    @Delete
    void deleteFeature(FeatureEntity userEntity);

    @Query("DELETE FROM feature_table")
    void deleteAllFeature();

    @Query("SELECT * FROM feature_table")
    List<FeatureEntity> getAllFeatureEntities();
}
