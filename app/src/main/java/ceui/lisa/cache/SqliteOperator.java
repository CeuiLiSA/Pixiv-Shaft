package ceui.lisa.cache;

/**
 * 使用数据库系统存储对象
 */
public class SqliteOperator implements IOperate {

    @Override
    public <T> T getModel(String key, Class<T> pClass) {
        return null;
    }

    @Override
    public <T> void saveModel(String ket, T pT) {

    }

    @Override
    public void clearAll() {

    }

    @Override
    public void clear(String key) {

    }
}
