package ceui.lisa.models;

public class CommentBean extends UserHolder {

    private String comment;
    private String date;
    private CommentStamp stamp;
    private int id;
    private String commentWithConvertedEmoji;

    public String getComment() {
        return this.comment;
    }

    public CommentStamp getStamp() {
        return stamp;
    }

    public void setStamp(CommentStamp stamp) {
        this.stamp = stamp;
    }

    public void setComment(String comment) {
        this.comment = comment;
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

    public String getCommentWithConvertedEmoji() {
        return commentWithConvertedEmoji != null ? commentWithConvertedEmoji : comment;
    }

    public void setCommentWithConvertedEmoji(String commentWithoutEmoji) {
        this.commentWithConvertedEmoji = commentWithoutEmoji;
    }
}
