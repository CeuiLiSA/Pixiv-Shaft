package ceui.lisa.model;

public class Content {
    private String content;
    private boolean isEmoji = false;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isEmoji() {
        return isEmoji;
    }

    public void setEmoji(boolean emoji) {
        isEmoji = emoji;
    }
}
