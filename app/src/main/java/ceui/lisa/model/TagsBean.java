package ceui.lisa.model;


import java.io.Serializable;

public class TagsBean implements Serializable {
    /**
     * name : 山の女王ファリア
     * translated_name : 山之女王 法俐雅
     */

    private String name;
    private String translated_name;

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
