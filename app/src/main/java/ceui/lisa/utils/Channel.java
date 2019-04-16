package ceui.lisa.utils;

public final class Channel<Target> {

    private String receiver;

    public String getReceiver() {
        return receiver;
    }

    public void setReceiver(String receiver) {
        this.receiver = receiver;
    }


    private Target object;


    public Target getObject() {
        return object;
    }

    public void setObject(Target target) {
        object = target;
    }
}
