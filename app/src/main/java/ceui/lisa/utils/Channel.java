package ceui.lisa.utils;

public final class Channel<Target> {

    private String receiver;
    private Target object;
    private int value;

    public String getReceiver() {
        return receiver;
    }

    public void setReceiver(String receiver) {
        this.receiver = receiver;
    }

    public Target getObject() {
        return object;
    }

    public void setObject(Target target) {
        object = target;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }
}
