package ceui.lisa.models;

import java.io.Serializable;

public class CommentStamp implements Serializable {

    private Long stamp_id;
    private String stamp_url;

    public Long getStamp_id() {
        return stamp_id;
    }

    public void setStamp_id(Long stamp_id) {
        this.stamp_id = stamp_id;
    }

    public String getStamp_url() {
        return stamp_url;
    }

    public void setStamp_url(String stamp_url) {
        this.stamp_url = stamp_url;
    }
}
