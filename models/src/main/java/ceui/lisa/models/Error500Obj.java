package ceui.lisa.models;

public class Error500Obj extends Error500{


    /**
     * error : false
     * message :
     * body : {"is_succeed":false,"validation_errors":{"mail_address":"这个电邮地址已经被其他用户使用了"}}
     */

    private ErrorBodyBean body;

    public ErrorBodyBean getBody() {
        return body;
    }

    public void setBody(ErrorBodyBean body) {
        this.body = body;
    }

}
