package ceui.lisa.models;

import java.io.Serializable;

public class ParentCommentBean extends UserHolder {

    private String comment;
    private String date;
    private int id;

    public String getComment() {
        return this.comment;
    }

    public void setComment(String paramString) {
        this.comment = paramString;
    }

    public String getDate() {
        return this.date;
    }

    public void setDate(String paramString) {
        this.date = paramString;
    }

    public int getId() {
        return this.id;
    }

    public void setId(int paramInt) {
        this.id = paramInt;
    }

}
