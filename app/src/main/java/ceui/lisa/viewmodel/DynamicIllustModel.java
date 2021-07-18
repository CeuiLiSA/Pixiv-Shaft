package ceui.lisa.viewmodel;

import java.util.List;

import ceui.lisa.helper.DeduplicateArrayList;
import ceui.lisa.models.IllustsBean;

public class DynamicIllustModel extends BaseModel<IllustsBean> {

    @Override
    public List<IllustsBean> getContent() {
        if (content == null) {
            content = new DeduplicateArrayList<>();
        }
        return content;
    }
}
