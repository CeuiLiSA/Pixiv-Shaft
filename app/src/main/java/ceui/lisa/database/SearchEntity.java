package ceui.lisa.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "search_table")
public final class SearchEntity {

    @PrimaryKey()
    private int id;
    private String keyword;

    public long getSearchTime() {
        return searchTime;
    }

    public void setSearchTime(long searchTime) {
        this.searchTime = searchTime;
    }

    private long searchTime;

    @Override
    public String toString() {
        return "SearchEntity{" +
                "id=" + id +
                ", keyword='" + keyword + '\'' +
                ", searchTime=" + searchTime +
                ", searchType=" + searchType +
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

    private int searchType;


}
