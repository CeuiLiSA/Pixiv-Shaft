package ceui.lisa.database;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** V3 漫画阅读器 - 用户为某 illust 的某一页保存的位置标签。 */
@Entity(
        tableName = "comic_bookmark_table",
        indices = {@Index(value = {"illustId"})}
)
public class ComicBookmarkEntity {

    @PrimaryKey(autoGenerate = true)
    public long bookmarkId;

    public long illustId;
    public int pageIndex;
    public int totalPages;
    @NonNull
    @ColumnInfo(name = "preview_url")
    public String previewUrl = "";
    @NonNull
    public String note = "";
    public long createdTime;

    public ComicBookmarkEntity() {}

    public ComicBookmarkEntity(long illustId, int pageIndex, int totalPages,
                               @NonNull String previewUrl, @NonNull String note,
                               long createdTime) {
        this.illustId = illustId;
        this.pageIndex = pageIndex;
        this.totalPages = totalPages;
        this.previewUrl = previewUrl;
        this.note = note;
        this.createdTime = createdTime;
    }
}
