package ceui.lisa.page;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 文章目录
 */
@Entity(indices = {@Index(value = {"id", "bookId", "chapterId"})})
public class TbBookChapter {

    @PrimaryKey(autoGenerate = true)
    public int id;

    @ColumnInfo(name = "bookId")
    public int bookId;

    @ColumnInfo(name = "chapterId")
    public long chapterId;

    @ColumnInfo(name = "chapterName")
    public String chapterName;

    @ColumnInfo(name = "content")
    public String content;
}