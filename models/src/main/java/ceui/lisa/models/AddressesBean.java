package ceui.lisa.models;

import androidx.annotation.NonNull;

public class AddressesBean {

    private int id;
    private String name;
    private Boolean is_global;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Boolean getIs_global() {
        return is_global;
    }

    public void setIs_global(Boolean is_global) {
        this.is_global = is_global;
    }

    @NonNull
    @Override
    public String toString() {
        return name;
    }
}
