package ceui.lisa.viewmodel;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.database.IllustHistoryEntity;
import ceui.lisa.models.IllustsBean;

public class HistoryModel extends BaseModel<IllustHistoryEntity> {

    private List<IllustsBean> all = new ArrayList<>();

    public List<IllustsBean> getAll() {
        return all;
    }

    public void setAll(List<IllustsBean> all) {
        this.all = all;
    }
}
