package ceui.lisa.models;

public class ErrorResponse2 {


    /**
     * has_error : true
     * errors : {"system":{"message":"103:pixiv ID、またはメールアドレス、パスワードが正しいかチェックしてください。","code":1508}}
     */

    private boolean has_error;
    private ErrorsBean errors;
    private String error;
    private ErrorBodyBean body;

    public boolean isHas_error() {
        return has_error;
    }

    public void setHas_error(boolean has_error) {
        this.has_error = has_error;
    }

    public ErrorsBean getErrors() {
        return errors;
    }

    public void setErrors(ErrorsBean errors) {
        this.errors = errors;
    }

    public ErrorBodyBean getBody() {
        return body;
    }

    public void setBody(ErrorBodyBean body) {
        this.body = body;
    }

    public static class ErrorsBean {
        /**
         * system : {"message":"103:pixiv ID、またはメールアドレス、パスワードが正しいかチェックしてください。","code":1508}
         */

        private SystemBean system;

        public SystemBean getSystem() {
            return system;
        }

        public void setSystem(SystemBean system) {
            this.system = system;
        }

        public static class SystemBean {
            /**
             * message : 103:pixiv ID、またはメールアドレス、パスワードが正しいかチェックしてください。
             * code : 1508
             */

            private String message;
            private int code;

            public String getMessage() {
                return message;
            }

            public void setMessage(String message) {
                this.message = message;
            }

            public int getCode() {
                return code;
            }

            public void setCode(int code) {
                this.code = code;
            }
        }
    }

}
