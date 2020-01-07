package ceui.lisa.model;

import java.util.List;

import ceui.lisa.models.CommentsBean;
import ceui.lisa.interfaces.ListShow;

public class IllustCommentsResponse implements ListShow<CommentsBean> {
    private List<CommentsBean> comments;
    private String next_url;
    private int total_comments;

    public List<CommentsBean> getComments() {
        return this.comments;
    }

    public void setComments(List<CommentsBean> paramList) {
        this.comments = paramList;
    }

    public String getNext_url() {
        return this.next_url;
    }

    public void setNext_url(String paramString) {
        this.next_url = paramString;
    }

    public int getTotal_comments() {
        return this.total_comments;
    }

    public void setTotal_comments(int paramInt) {
        this.total_comments = paramInt;
    }

    @Override
    public List<CommentsBean> getList() {
        return comments;
    }

    @Override
    public String getNextUrl() {
        return next_url;
    }
}
