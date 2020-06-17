package ceui.lisa.models;

public class ErrorBodyBean {

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
