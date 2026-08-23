package ceui.lisa.model;

import java.util.List;

import ceui.lisa.interfaces.ListShow;
import ceui.loxia.User;

public class ListSimpleUser implements ListShow<User> {

    private String next_url;
    private List<User> users;

    public String getNext_url() {
        return next_url;
    }

    public void setNext_url(String pNext_url) {
        next_url = pNext_url;
    }

    public List<User> getUsers() {
        return users;
    }

    public void setUsers(List<User> pUsers) {
        users = pUsers;
    }

    @Override
    public List<User> getList() {
        return users;
    }

    @Override
    public String getNextUrl() {
        return next_url;
    }
}
