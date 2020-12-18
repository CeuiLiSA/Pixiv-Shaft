package ceui.lisa.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

@Entity(tableName = "illust_downloading_table")
public final class DownloadingEntity implements Serializable {

    @PrimaryKey()
    @NonNull
    private String fileName = "";


    private String uuid = "";
    private String taskGson;

    @NonNull
    public String getFileName() {
        return fileName;
    }

    public void setFileName(@NonNull String fileName) {
        this.fileName = fileName;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getTaskGson() {
        return taskGson;
    }

    public void setTaskGson(String taskGson) {
        this.taskGson = taskGson;
    }

    @Override
    public String toString() {
        return "DownloadingEntity{" +
                "uuid='" + uuid + '\'' +
                ", taskGson='" + taskGson + '\'' +
                '}';
    }
}
