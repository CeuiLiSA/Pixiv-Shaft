package ceui.lisa.models;


public class ReplyCommentBean extends CommentBean {

    private CommentBean parent_comment;

    public CommentBean getParent_comment() {
        return this.parent_comment;
    }

    public void setParent_comment(CommentBean parentCommentBean) {
        this.parent_comment = parentCommentBean;
    }
}
