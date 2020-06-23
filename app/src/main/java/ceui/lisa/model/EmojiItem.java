package ceui.lisa.model;

public class EmojiItem {

    private String resource;
    private String name;

    public EmojiItem(String name, String resource) {
        this.name = name;
        this.resource = resource;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getResource() {
        return resource;
    }

    public void setResource(String pResource) {
        resource = pResource;
    }
}
