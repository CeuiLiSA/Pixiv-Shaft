package ceui.lisa.model;

import java.util.List;

import ceui.lisa.interfaces.ListShow;
import ceui.lisa.models.UserPreviewsBean;

public class ListUser implements ListShow<UserPreviewsBean> {

    private String next_url;
    private List<UserPreviewsBean> user_previews;

    public String getNext_url() {
        return next_url;
    }

    public void setNext_url(String next_url) {
        this.next_url = next_url;
    }

    public List<UserPreviewsBean> getUser_previews() {
        return user_previews;
    }

    public void setUser_previews(List<UserPreviewsBean> user_previews) {
        this.user_previews = user_previews;
    }

    @Override
    public List<UserPreviewsBean> getList() {
        return user_previews;
    }

    @Override
    public String getNextUrl() {
        return next_url;
    }
}
