package ceui.lisa.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "tag_mute_table")
public final class TagMuteEntity {

    @PrimaryKey()
    private int id;
    private String tagJson;
    private long searchTime;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getTagJson() {
        return tagJson;
    }

    public void setTagJson(String tagJson) {
        this.tagJson = tagJson;
    }

    public long getSearchTime() {
        return searchTime;
    }

    public void setSearchTime(long searchTime) {
        this.searchTime = searchTime;
    }

    @Override
    public String toString() {
        return "TagMuteEntity{" +
                "id=" + id +
                ", tagJson='" + tagJson + '\'' +
                ", searchTime=" + searchTime +
                '}';
    }
}
