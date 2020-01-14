package ceui.lisa.models;

public class Error500Obj {


    /**
     * error : false
     * message :
     * body : {"is_succeed":false,"validation_errors":{"mail_address":"这个电邮地址已经被其他用户使用了"}}
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
         * is_succeed : false
         * validation_errors : {"mail_address":"这个电邮地址已经被其他用户使用了"}
         */

        private boolean is_succeed;
        private ValidationErrorsBean validation_errors;

        public boolean isIs_succeed() {
            return is_succeed;
        }

        public void setIs_succeed(boolean is_succeed) {
            this.is_succeed = is_succeed;
        }

        public ValidationErrorsBean getValidation_errors() {
            return validation_errors;
        }

        public void setValidation_errors(ValidationErrorsBean validation_errors) {
            this.validation_errors = validation_errors;
        }

        public static class ValidationErrorsBean {
            /**
             * mail_address : 这个电邮地址已经被其他用户使用了
             */

            private String mail_address;

            public String getMail_address() {
                return mail_address;
            }

            public void setMail_address(String mail_address) {
                this.mail_address = mail_address;
            }
        }
    }
}
