package ceui.lisa.cache;

public class MMKVCache implements IOperate, Proxy<IOperate>{

    private final IOperate mOperate;

    private MMKVCache() {
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
        return new MMKVOperator();
    }

    private static class Holder {
        private static final MMKVCache INSTANCE = new MMKVCache();
    }

    public static MMKVCache get() {
        return Holder.INSTANCE;
    }
}
