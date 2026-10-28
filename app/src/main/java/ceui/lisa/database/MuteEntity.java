package ceui.lisa.database;

import androidx.room.Entity;
import androidx.room.Index;

/**
 * 屏蔽名单。v44 补了 {@code (type, searchTime)} 索引。
 *
 * 这张表的查询一律是「按 type 过滤 + 按 searchTime 倒序」：{@code allMutedTags}（屏蔽标签
 * 全量，搜索页 onResume 每次都要）、{@code mutedWorks}、{@code getMutedUser}。此前无索引，
 * 每条都是全表扫描 + 临时 B-tree 排序，而每行还带一份 tagJson。
 *
 * 复合主键是 {@code (id, type)}，id 打头，所以 `type = ? AND id = ?` 这类点查本来就走主键，
 * 这里不需要再单独建 id 索引。
 */
@Entity(tableName = "tag_mute_table", primaryKeys = {"id", "type"},
        indices = {@Index(value = {"type", "searchTime"})})
public final class MuteEntity {

    private long id;
    private String tagJson;
    private long searchTime;
    private int type; //0标签，1插画漫画，2小说，3用户

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
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
