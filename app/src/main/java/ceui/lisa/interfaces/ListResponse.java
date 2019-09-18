package ceui.lisa.interfaces;

import java.util.List;

import ceui.lisa.model.IllustsBean;

public class ListResponse<Item> {

    private List<Item> illusts;
    private String next_url;

    public List<Item> getIllusts() {
        return illusts;
    }

    public void setIllusts(List<Item> illusts) {
        this.illusts = illusts;
    }

    public String getNext_url() {
        return next_url;
    }

    public void setNext_url(String next_url) {
        this.next_url = next_url;
    }
}
