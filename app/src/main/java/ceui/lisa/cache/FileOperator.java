package ceui.lisa.cache;

import android.util.Log;

import com.blankj.utilcode.util.PathUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * 使用文件系统存储对象
 */
public class FileOperator implements IOperate {

    @Override
    public <T> T getModel(String key, Class<T> pClass) {
        try {
            File file = new File(PathUtils.getInternalAppCachePath(), key);
            if (!file.exists()) {
                return null;
            }
            FileInputStream is = new FileInputStream(file);
            ObjectInputStream ois = new ObjectInputStream(is);
            T result = (T) ois.readObject();
            ois.close();
            return result;
        } catch (ClassNotFoundException | IOException pE) {
            pE.printStackTrace();
        }
        return null;
    }

    @Override
    public <T> void saveModel(String ket, T pT) {
        try {
            File file = new File(PathUtils.getInternalAppCachePath(), ket);
            Log.d("file name ", file.getPath());
            FileOutputStream fileOutputStream = new FileOutputStream(file);
            ObjectOutputStream oos = new ObjectOutputStream(fileOutputStream);
            oos.writeObject(pT);
            oos.flush();
            oos.close();
        } catch (IOException pE) {
            pE.printStackTrace();
        }
    }

    @Override
    public void clearAll() {
    }

    @Override
    public void clear(String key) {
    }
}
