package ceui.lisa.cache;

public class Cache implements IOperate, Proxy{

    private IOperate mOperate;

    private Cache() {
        mOperate = create();
    }

    @Override
    public <T> T getModel(String key, Class<T> pClass) {
        return mOperate.getModel(key, pClass);
    }

    @Override
    public <T> void saveModel(String ket, T pT) {
        mOperate.saveModel(ket, pT);
    }

    @Override
    public void clearAll() {
        mOperate.clearAll();
    }

    @Override
    public void clear(String key) {
        mOperate.clear(key);
    }

    @Override
    public IOperate create() {
        return new FileOperator();
    }

    private static class Holder {
        private static Cache INSTANCE = new Cache();
    }

    public static Cache get() {
        return Holder.INSTANCE;
    }
}
