package ceui.lisa.database;

public class Channel<Target> {

    private String receiver;
    private Target object;

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
}
