package ceui.lisa.model;

public class MenuItem {

    private String name;
    private int imageRes;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getImageRes() {
        return imageRes;
    }

    public void setImageRes(int imageRes) {
        this.imageRes = imageRes;
    }

    public MenuItem(String name, int imageRes) {
        this.name = name;
        this.imageRes = imageRes;
    }
}
