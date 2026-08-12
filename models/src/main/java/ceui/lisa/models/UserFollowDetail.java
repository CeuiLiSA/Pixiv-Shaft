package ceui.lisa.models;

public class UserFollowDetail {
    private FollowDetail follow_detail;

    public FollowDetail getFollow_detail() {
        return follow_detail;
    }

    public void setFollow_detail(FollowDetail follow_detail) {
        this.follow_detail = follow_detail;
    }

    // 三个判定都要能吃下缺字段的响应：follow_detail 整块可能不下发，restrict 在未关注时
    // 也可能是 null。原先是裸解引用 + restrict.equals(...)，两处都会主线程 NPE。
    public boolean isFollow(){
        return follow_detail != null && follow_detail.isIs_followed();
    }

    public boolean isPublicFollow(){
        return isFollow() && Restrict.PUBLIC.equals(follow_detail.getRestrict());
    }

    public boolean isPrivateFollow(){
        return isFollow() && Restrict.PRIVATE.equals(follow_detail.getRestrict());
    }

    public static class FollowDetail{
        private boolean is_followed;
        private String restrict;

        public boolean isIs_followed() {
            return is_followed;
        }

        public void setIs_followed(boolean is_followed) {
            this.is_followed = is_followed;
        }

        public String getRestrict() {
            return restrict;
        }

        public void setRestrict(String restrict) {
            this.restrict = restrict;
        }
    }

    private static class Restrict{
        private static final String PUBLIC = "public";
        private static final String PRIVATE = "private";
    }
}
