package ceui.lisa.database;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * A class represents the search history
 * <p>
 *     int id:searchEntity.getKeyword().hashCode() + searchEntity.getSearchType()
 * </p>
 * <p>
 *     String keyword: The search content
 *
 * </p>
 * v44 补了 {@code (pinned, searchTime)} 索引：搜索页 onResume 的两条查询
 * （{@code WHERE pinned = 1} 全量置顶、{@code WHERE pinned = 0 ORDER BY searchTime DESC LIMIT 50}）
 * 此前都是全表扫描 + 排序，而行数随「搜过多少次」无限增长，且 pinned 行还带 previewIllustsJson。
 * */
@Entity(tableName = "search_table", indices = {@Index(value = {"pinned", "searchTime"})})
public final class SearchEntity {

    @PrimaryKey()
    private int id;
    private String keyword;//The search content
    private long searchTime;//Time from 1970s to now
    private int searchType;
    private boolean pinned;
    // 固定标签的预览 illust json，shape 沿用旧 Prime 内置榜 assets: {tag, resp:{illusts:[...]}}
    // 当前只塞 main 一张；只在 pinned=true 时写入，取消固定时置 null。
    private String previewIllustsJson;

    public long getSearchTime() {
        return searchTime;
    }

    public void setSearchTime(long searchTime) {
        this.searchTime = searchTime;
    }

    @Override
    public String toString() {
        return "SearchEntity{" +
                "id=" + id +
                ", keyword='" + keyword + '\'' +
                ", searchTime=" + searchTime +
                ", searchType=" + searchType +
                ", pinned=" + pinned +
                '}';
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public int getSearchType() {
        return searchType;
    }

    public void setSearchType(int searchType) {
        this.searchType = searchType;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public String getPreviewIllustsJson() {
        return previewIllustsJson;
    }

    public void setPreviewIllustsJson(String previewIllustsJson) {
        this.previewIllustsJson = previewIllustsJson;
    }
}
