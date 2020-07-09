package ceui.lisa.model;

public class CustomFileNameCell {

    private String title;
    private String desc;
    private int code;
    private boolean isChecked;

    public CustomFileNameCell(String title, String desc, int code, boolean isChecked) {
        this.title = title;
        this.desc = desc;
        this.code = code;
        this.isChecked = isChecked;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public boolean isChecked() {
        return isChecked;
    }

    public void setChecked(boolean checked) {
        isChecked = checked;
    }
}
