package ceui.lisa.database;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.io.Serializable;

@Entity(tableName = "illust_download_table",
        indices = {@Index("illustId"), @Index({"illustId", "page"})})
public final class DownloadEntity implements Serializable {

    @PrimaryKey()
    @NonNull
    private String fileName = "";
    private String filePath = "";
    private String taskGson;
    private String illustGson;
    private long downloadTime;

    /**
     * 作品自身 id（插画 / 小说），v38 新增的索引列。之前判断“这幅画下过没”要对 illustGson
     * blob 做全表 LIKE 扫描（30000+ 行 2GB+ 单次几百 ms~秒级、烧 CPU 又占读连接），加了这列
     * 走索引后 O(log n)。插入时由 {@link DownloadIdExtractor} 从 illustGson 算出（或调用方
     * 直接 set 已知 id）；存量行由 {@code DownloadIdBackfill} 一次性后台回填。
     * 0 = 尚未回填，-1 = 解析失败 / 无 id。
     *
     * @ColumnInfo(defaultValue="0") 必须与迁移的 ADD COLUMN ... DEFAULT 0 保持一致：
     * entity 声明默认 → fresh install 的 CREATE TABLE 与升级迁移都带 DEFAULT 0，schema
     * 校验一致（真机 v37→v38 验证通过）。**改这里等于改 v38 identity hash，改了必须升版本号**，
     * 否则已迁到 v38 的设备会报 "Room cannot verify data integrity"（hash 不匹配）崩。
     */
    @ColumnInfo(defaultValue = "0")
    private long illustId;

    /**
     * 这一行对应作品的第几页（0 基，与 {@code Illust.meta_pages} 的下标一致）。
     * v41 新增，配合 {@code (illustId, page)} 复合索引。
     *
     * <p>之前"这一页下过没"只能拿 {@code FileCreator.customFileName} 算出**当前模板**下
     * 的文件名去查主键 —— 用户换过模板、或记录是 {@code DownloadImporter} 从旧版命名的
     * 文件扫进来的，就永远查不中。有了这一列，查询按 (作品, 页码) 走，跟文件叫什么名字
     * 彻底解耦。
     *
     * <p>两个负数哨兵，别混：
     * <ul>
     *   <li>{@code -1} = 还没回填过（v41 之前的存量行）。{@code DownloadPageBackfill}
     *       只捞这一类。</li>
     *   <li>{@code -2} = 回填试过了但文件名解析不出页码。**必须与 -1 区分**，否则回填
     *       每一批都会重新捞到同一批行，永远跑不完。</li>
     * </ul>
     * 两种情况下按 (illustId, page) 的查询都自然落空，调用方退回原来的 fileName 主键
     * 查询，不产生回归。
     *
     * <p>同 {@link #illustId} 的告警：{@code @ColumnInfo(defaultValue)} 必须与迁移里的
     * {@code ADD COLUMN ... DEFAULT} 一致，**改这里等于改 v41 identity hash，改了必须
     * 升版本号**，否则已迁到 v41 的设备会报 "Room cannot verify data integrity" 崩。
     */
    @ColumnInfo(defaultValue = "-1")
    private int page = -1;

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    @Override
    public String toString() {
        return "DownloadEntity{" +
                ", taskGson='" + taskGson + '\'' +
                ", illustGson='" + illustGson + '\'' +
                ", downloadTime=" + downloadTime +
                '}';
    }

    public String getTaskGson() {
        return taskGson;
    }

    public void setTaskGson(String taskGson) {
        this.taskGson = taskGson;
    }

    public String getIllustGson() {
        return illustGson;
    }

    public void setIllustGson(String illustGson) {
        this.illustGson = illustGson;
    }

    public long getDownloadTime() {
        return downloadTime;
    }

    public void setDownloadTime(long downloadTime) {
        this.downloadTime = downloadTime;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getIllustId() {
        return illustId;
    }

    public void setIllustId(long illustId) {
        this.illustId = illustId;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }
}
