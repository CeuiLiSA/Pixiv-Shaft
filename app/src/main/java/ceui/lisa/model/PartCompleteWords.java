package ceui.lisa.model;

import java.util.List;

import ceui.lisa.interfaces.ListShow;

public class PartCompleteWords implements ListShow<String> {


    /**
     * ret : 0
     * msg : 12
     * element : ["1234","123434","123443321","12345","123456","12345654321","1234565456","12345678","123456789","1234567890","1234","1234567B"]
     */

    private int ret;
    private String msg;
    private List<String> element;

    public int getRet() {
        return ret;
    }

    public void setRet(int ret) {
        this.ret = ret;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public List<String> getElement() {
        return element;
    }

    public void setElement(List<String> element) {
        this.element = element;
    }

    @Override
    public List<String> getList() {
        return element;
    }

    @Override
    public String getNextUrl() {
        return null;
    }
}
