package ceui.lisa.models;


import java.io.Serializable;

public class TagsBean implements Serializable {
    /**
     * name : 山の女王ファリア
     * translated_name : 山之女王 法俐雅
     */

    private String name;
    private String translated_name;
    private boolean effective = true; //屏蔽是否生效，
    private boolean added_by_uploaded_user;
    private boolean is_registered;

    private int count;
    private boolean isSelected;
    private int filter_mode = 0; //过滤模式：0普通，1正则

    public boolean isSelected() {
        return isSelected;
    }

    public void setSelected(boolean selected) {
        isSelected = selected;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTranslated_name() {
        return translated_name;
    }

    public void setTranslated_name(String translated_name) {
        this.translated_name = translated_name;
    }

    public boolean isAdded_by_uploaded_user() {
        return added_by_uploaded_user;
    }

    public void setAdded_by_uploaded_user(boolean added_by_uploaded_user) {
        this.added_by_uploaded_user = added_by_uploaded_user;
    }

    public boolean isEffective() {
        return effective;
    }

    public void setEffective(boolean effective) {
        this.effective = effective;
    }

    public boolean isIs_registered() {
        return is_registered;
    }

    public void setIs_registered(boolean is_registered) {
        this.is_registered = is_registered;
    }

    public boolean isSelectedLocalOrRemote() {
        return isSelected || is_registered;
    }

    public void setSelectedLocalAndRemote(boolean selected) {
        isSelected = selected;
        is_registered = selected;
    }

    public int getFilter_mode() {
        return filter_mode;
    }

    public void setFilter_mode(int filter_mode) {
        this.filter_mode = filter_mode;
    }
}
