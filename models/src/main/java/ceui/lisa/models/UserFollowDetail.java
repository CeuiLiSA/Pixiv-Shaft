package ceui.lisa.models;

public class UserFollowDetail {
    private FollowDetail follow_detail;

    public FollowDetail getFollow_detail() {
        return follow_detail;
    }

    public void setFollow_detail(FollowDetail follow_detail) {
        this.follow_detail = follow_detail;
    }

    public boolean isFollow(){
        return getFollow_detail().isIs_followed();
    }

    public boolean isPublicFollow(){
        return getFollow_detail().isIs_followed() && getFollow_detail().getRestrict().equals(Restrict.PUBLIC);
    }

    public boolean isPrivateFollow(){
        return getFollow_detail().isIs_followed() && getFollow_detail().getRestrict().equals(Restrict.PRIVATE);
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
