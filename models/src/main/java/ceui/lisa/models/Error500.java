package ceui.lisa.models;

import java.util.List;

public class Error500 {

    /**
     * error : true
     * message : 页面发生了错误。
     * body : []
     */

    private boolean error;
    private String message;

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

}
