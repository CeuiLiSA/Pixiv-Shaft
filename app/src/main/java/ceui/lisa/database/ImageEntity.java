package ceui.lisa.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;


@Entity(tableName = "upload_image_table")
public class ImageEntity {

    @PrimaryKey()
    private int id;
    private String fileName;
    private String filePath;
    private long uploadTime;


    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getUploadTime() {
        return uploadTime;
    }

    public void setUploadTime(long uploadTime) {
        this.uploadTime = uploadTime;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }
}
