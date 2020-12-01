package ceui.lisa.model;

public class ColorItem {

    private int index;
    private String name;
    private String color;
    private boolean isSelect;

    public ColorItem(int index, String name, String color, boolean isSelect) {
        this.index = index;
        this.name = name;
        this.color = color;
        this.isSelect = isSelect;
    }

    public boolean isSelect() {
        return isSelect;
    }

    public void setSelect(boolean select) {
        isSelect = select;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }
}
