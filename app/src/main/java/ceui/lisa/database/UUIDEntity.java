package ceui.lisa.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.google.gson.reflect.TypeToken;

import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.core.IDWithList;
import ceui.lisa.models.IllustsBean;

@Entity(tableName = "uuid_list_table")
public final class UUIDEntity implements IDWithList<IllustsBean> {

    @NonNull
    @PrimaryKey()
    private String uuid;
    private String listJson;

    @NonNull
    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        if (uuid != null) {
            this.uuid = uuid;
        }
    }

    public String getListJson() {
        return listJson;
    }

    public void setListJson(String listJson) {
        this.listJson = listJson;
    }

    @Override
    public String toString() {
        return "UUIDEntity{" +
                "uuid='" + uuid + '\'' +
                ", listJson='" + listJson + '\'' +
                '}';
    }

    @Override
    public String getUUID() {
        return uuid;
    }

    @Override
    public List<IllustsBean> getList() {
        return Shaft.sGson.fromJson(listJson, new TypeToken<List<IllustsBean>>(){}.getType());
    }
}
