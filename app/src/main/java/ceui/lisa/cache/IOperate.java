package ceui.lisa.cache;

public interface IOperate {

    <T> T getModel(String key, Class<T> pClass);

    <T> void saveModel(String ket, T pT);

    void clearAll();

    void clear(String key);
}
