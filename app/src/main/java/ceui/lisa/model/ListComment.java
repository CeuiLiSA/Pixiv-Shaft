package ceui.lisa.model;

import java.util.List;

import ceui.lisa.interfaces.ListShow;
import ceui.lisa.models.ReplyCommentBean;

public class ListComment implements ListShow<ReplyCommentBean> {
    private List<ReplyCommentBean> comments;
    private String next_url;
    private int total_comments;

    public List<ReplyCommentBean> getComments() {
        return this.comments;
    }

    public void setComments(List<ReplyCommentBean> paramList) {
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
    public List<ReplyCommentBean> getList() {
        return comments;
    }

    @Override
    public String getNextUrl() {
        return next_url;
    }
}
