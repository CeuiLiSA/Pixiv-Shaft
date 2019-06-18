package ceui.lisa.response;

public class SignResponse {


    /**
     * error : false
     * message :
     * body : {"user_account":"user_yycv5257","password":"2ye6lFEuyl","device_token":"bdae5762b79578ae59cb63f32df6da96"}
     */

    private boolean error;
    private String message;
    private BodyBean body;

    public boolean isError() {
        return error;
    }

    public void setError(boolean error) {
        this.error = error;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public BodyBean getBody() {
        return body;
    }

    public void setBody(BodyBean body) {
        this.body = body;
    }

    public static class BodyBean {
        /**
         * user_account : user_yycv5257
         * password : 2ye6lFEuyl
         * device_token : bdae5762b79578ae59cb63f32df6da96
         */

        private String user_account;
        private String password;
        private String device_token;

        public String getUser_account() {
            return user_account;
        }

        public void setUser_account(String user_account) {
            this.user_account = user_account;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDevice_token() {
            return device_token;
        }

        public void setDevice_token(String device_token) {
            this.device_token = device_token;
        }
    }
}
