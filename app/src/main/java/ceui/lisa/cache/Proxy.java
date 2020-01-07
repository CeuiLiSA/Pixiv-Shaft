package ceui.lisa.cache;

public interface Proxy<T extends IOperate> {

    T create();
}
