package ceui.lisa.test;

public class Eos {

    private String tag;

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    private static class Holder{
        private static final Eos INSTANCE = new Eos();
    }

    public static Eos get() {
        return Holder.INSTANCE;
    }

    private Eos(){}
}
