package ceui.lisa.cache;

import com.blankj.utilcode.util.PathUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import timber.log.Timber;

/**
 * 用 Java 序列化把对象存到内部 cache 目录的 key 文件里。
 * 原本是 IOperate/Proxy/FileOperator 三层抽象包着唯一一个实现，已合并。
 */
public class Cache {

    private Cache() {
    }

    @SuppressWarnings("unchecked")
    public <T> T getModel(String key, Class<T> pClass) {
        File file = new File(PathUtils.getInternalAppCachePath(), key);
        if (!file.exists()) {
            return null;
        }
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            return (T) ois.readObject();
        } catch (ClassNotFoundException | IOException e) {
            Timber.w(e, "Cache.getModel failed key=%s", key);
            return null;
        }
    }

    public <T> void saveModel(String key, T value) {
        File file = new File(PathUtils.getInternalAppCachePath(), key);
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
            oos.writeObject(value);
            oos.flush();
        } catch (IOException e) {
            Timber.w(e, "Cache.saveModel failed key=%s", key);
        }
    }

    private static class Holder {
        private static final Cache INSTANCE = new Cache();
    }

    public static Cache get() {
        return Holder.INSTANCE;
    }
}
