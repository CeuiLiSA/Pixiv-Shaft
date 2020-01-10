package ceui.lisa.models;

import java.io.Serializable;

public class CommentsBean implements Serializable {

    private String comment;
    private String date;
    private int id;
    private ParentCommentBean parent_comment;
    private UserBean user;

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

    public ParentCommentBean getParent_comment() {
        return this.parent_comment;
    }

    public void setParent_comment(ParentCommentBean paramParentCommentBean) {
        this.parent_comment = paramParentCommentBean;
    }

    public UserBean getUser() {
        return this.user;
    }

    public void setUser(UserBean paramUserBean) {
        this.user = paramUserBean;
    }

    public static class ParentCommentBean implements Serializable {
        private String comment;
        private String date;
        private int id;
        private UserBean user;

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

        public UserBean getUser() {
            return this.user;
        }

        public void setUser(UserBean paramUserBeanX) {
            this.user = paramUserBeanX;
        }
    }
}

