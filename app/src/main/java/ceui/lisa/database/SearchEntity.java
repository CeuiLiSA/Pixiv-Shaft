package ceui.lisa.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

import ceui.lisa.http.AppApi;

/**
 * A class represents the search history
 * <p>
 *     String keyword:The search content
 *     <p>
 *         Value 1:{@link AppApi#getIllustByID(String, long)}
 *     </p>
 *     <p>
 *         Value 2:{@link AppApi#getIllustByID(String, long)}
 *     </p>
 *
 * </p>
 * */
@Entity(tableName = "search_table")
public final class SearchEntity {

    @PrimaryKey()
    private int id;
    private String keyword;//The search content
    private long searchTime;//Time from 1970s to now
    private int searchType;
    private boolean pinned;

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
}
