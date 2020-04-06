package ceui.lisa.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "illust_table")
public final class IllustHistoryEntity {

    @PrimaryKey()
    private int illustID;
    private String illustJson;
    private long time;
    private int type; // 0插画， 1小说

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public int getIllustID() {
        return illustID;
    }

    public void setIllustID(int illustID) {
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
