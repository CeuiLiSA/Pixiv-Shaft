package ceui.lisa.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

@Entity(tableName = "illust_download_table")
public final class DownloadEntity implements Serializable {

    @PrimaryKey()
    @NonNull
    private String fileName = "";
    private String filePath = "";
    private String taskGson;
    private String illustGson;
    private long downloadTime;

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    @Override
    public String toString() {
        return "DownloadEntity{" +
                ", taskGson='" + taskGson + '\'' +
                ", illustGson='" + illustGson + '\'' +
                ", downloadTime=" + downloadTime +
                '}';
    }

    public String getTaskGson() {
        return taskGson;
    }

    public void setTaskGson(String taskGson) {
        this.taskGson = taskGson;
    }

    public String getIllustGson() {
        return illustGson;
    }

    public void setIllustGson(String illustGson) {
        this.illustGson = illustGson;
    }

    public long getDownloadTime() {
        return downloadTime;
    }

    public void setDownloadTime(long downloadTime) {
        this.downloadTime = downloadTime;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }
}
