package ceui.lisa.models;

public class UserState {


    /**
     * user_state : {"is_mail_authorized":false,"has_changed_pixiv_id":false,"can_change_pixiv_id":true}
     */

    private UserStateBean user_state;

    public UserStateBean getUser_state() {
        return user_state;
    }

    public void setUser_state(UserStateBean user_state) {
        this.user_state = user_state;
    }

    public static class UserStateBean {
        /**
         * is_mail_authorized : false
         * has_changed_pixiv_id : false
         * can_change_pixiv_id : true
         */

        private boolean is_mail_authorized;
        private boolean has_changed_pixiv_id;
        private boolean can_change_pixiv_id;
        private boolean has_password;

        public boolean isIs_mail_authorized() {
            return is_mail_authorized;
        }

        public void setIs_mail_authorized(boolean is_mail_authorized) {
            this.is_mail_authorized = is_mail_authorized;
        }

        public boolean isHas_changed_pixiv_id() {
            return has_changed_pixiv_id;
        }

        public void setHas_changed_pixiv_id(boolean has_changed_pixiv_id) {
            this.has_changed_pixiv_id = has_changed_pixiv_id;
        }

        public boolean isCan_change_pixiv_id() {
            return can_change_pixiv_id;
        }

        public void setCan_change_pixiv_id(boolean can_change_pixiv_id) {
            this.can_change_pixiv_id = can_change_pixiv_id;
        }

        public boolean isHas_password() {
            return has_password;
        }

        public void setHas_password(boolean has_password) {
            this.has_password = has_password;
        }
    }
}
