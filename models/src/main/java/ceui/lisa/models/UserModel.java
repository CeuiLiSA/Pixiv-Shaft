package ceui.lisa.models;

import java.io.Serializable;

public class UserModel implements Serializable {


    /**
     * response : {"access_token":"ECBLcZ9bp3xQ3IFK353yjn-Es6B4fNSPub21PCKx5x8","expires_in":3600,"token_type":"bearer","scope":"","refresh_token":"A5e2RuD2NcXwJ5t56D8ltuy_CCFG4UWyhmYCgeLq2kw","user":{"profile_image_urls":{"px_16x16":"https://i.pximg.net/user-profile/img/2018/06/20/23/27/47/14384932_69771f95cafdac1a1d3da88fcfe4ecab_16.jpg","px_50x50":"https://i.pximg.net/user-profile/img/2018/06/20/23/27/47/14384932_69771f95cafdac1a1d3da88fcfe4ecab_50.jpg","px_170x170":"https://i.pximg.net/user-profile/img/2018/06/20/23/27/47/14384932_69771f95cafdac1a1d3da88fcfe4ecab_170.jpg"},"id":"31655571","name":"details","account":"mercisbv","mail_address":"290071582@qq.com","is_premium":false,"x_restrict":2,"is_mail_authorized":true,"require_policy_agreement":false},"device_token":"520c73a6b5f7b93d5499878435fcb255"}
     */

    private ResponseBean response;

    public ResponseBean getResponse() {
        return response;
    }

    public void setResponse(ResponseBean response) {
        this.response = response;
    }

    public static class ResponseBean implements Serializable {
        /**
         * access_token : ECBLcZ9bp3xQ3IFK353yjn-Es6B4fNSPub21PCKx5x8
         * expires_in : 3600
         * token_type : bearer
         * scope :
         * refresh_token : A5e2RuD2NcXwJ5t56D8ltuy_CCFG4UWyhmYCgeLq2kw
         * user : {"profile_image_urls":{"px_16x16":"https://i.pximg.net/user-profile/img/2018/06/20/23/27/47/14384932_69771f95cafdac1a1d3da88fcfe4ecab_16.jpg","px_50x50":"https://i.pximg.net/user-profile/img/2018/06/20/23/27/47/14384932_69771f95cafdac1a1d3da88fcfe4ecab_50.jpg","px_170x170":"https://i.pximg.net/user-profile/img/2018/06/20/23/27/47/14384932_69771f95cafdac1a1d3da88fcfe4ecab_170.jpg"},"id":"31655571","name":"details","account":"mercisbv","mail_address":"290071582@qq.com","is_premium":false,"x_restrict":2,"is_mail_authorized":true,"require_policy_agreement":false}
         * device_token : 520c73a6b5f7b93d5499878435fcb255
         */

        private String access_token;
        private int expires_in;
        private String token_type;
        private String scope;
        private String refresh_token;
        private UserBean user;
        private String device_token;

        public String getAccess_token() {
            return access_token;
        }

        public void setAccess_token(String access_token) {
            this.access_token = access_token;
        }

        public int getExpires_in() {
            return expires_in;
        }

        public void setExpires_in(int expires_in) {
            this.expires_in = expires_in;
        }

        public String getToken_type() {
            return token_type;
        }

        public void setToken_type(String token_type) {
            this.token_type = token_type;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }

        public String getRefresh_token() {
            return refresh_token;
        }

        public void setRefresh_token(String refresh_token) {
            this.refresh_token = refresh_token;
        }

        public UserBean getUser() {
            return user;
        }

        public void setUser(UserBean user) {
            this.user = user;
        }

        public String getDevice_token() {
            return device_token;
        }

        public void setDevice_token(String device_token) {
            this.device_token = device_token;
        }
    }
}
