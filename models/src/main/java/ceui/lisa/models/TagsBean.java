package ceui.lisa.models;


import java.io.Serializable;

public class TagsBean implements Serializable {
    /**
     * name : 山の女王ファリア
     * translated_name : 山之女王 法俐雅
     */

    private String name;
    private String translated_name;

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
}
