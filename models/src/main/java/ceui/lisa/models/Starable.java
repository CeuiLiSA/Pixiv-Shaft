package ceui.lisa.models;

public interface Starable {

    int getItemID();

    void setItemID(int id);

    boolean isItemStared();

    void setItemStared(boolean isLiked);
}
