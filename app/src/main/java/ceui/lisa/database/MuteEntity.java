package ceui.lisa.database;

import androidx.room.Entity;

@Entity(tableName = "tag_mute_table", primaryKeys = {"id", "type"})
public final class MuteEntity {

    private int id;
    private String tagJson;
    private long searchTime;
    private int type; //0标签，1插画漫画，2小说，3用户

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

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
                ", type=" + type +
                '}';
    }
}
