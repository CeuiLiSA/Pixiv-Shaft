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

    private int count;
    private boolean isSelected = false;

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
}
