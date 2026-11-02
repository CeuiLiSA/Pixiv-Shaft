package ceui.lisa.database;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 老版浏览历史（v44 补了 {@code time} 索引）。
 *
 * {@code getAllViewHistory} 是 {@code ORDER BY time DESC LIMIT ? OFFSET ?}，此前无索引 →
 * 全表扫描 + 临时 B-tree 排序，而每行都带一份完整 Illust JSON。
 * {@code ProfileManager.buildProfile} 冷启动时要连翻 5 页。
 */
@Entity(tableName = "illust_table", indices = {@Index("time")})
public final class IllustHistoryEntity {

    @PrimaryKey()
    private long illustID;
    private String illustJson;
    private long time;
    private int type; // 0插画， 1小说

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public long getIllustID() {
        return illustID;
    }

    public void setIllustID(long illustID) {
        this.illustID = illustID;
    }

    public String getIllustJson() {
        return illustJson;
    }

    public void setIllustJson(String illustJson) {
        this.illustJson = illustJson;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    @Override
    public String toString() {
        return "IllustHistoryEntity{" +
                "illustID=" + illustID +
                ", illustJson='" + illustJson + '\'' +
                ", time=" + time +
                ", type=" + type +
                '}';
    }
}
