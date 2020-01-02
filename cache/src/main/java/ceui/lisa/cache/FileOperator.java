package ceui.lisa.cache;

import android.util.Log;


import com.blankj.utilcode.util.FileUtils;
import com.blankj.utilcode.util.PathUtils;
import com.blankj.utilcode.util.ToastUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;


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
            ToastUtils.showShort("保存成功！");
        } catch (IOException pE) {
            pE.printStackTrace();
        }
    }

    @Override
    public void clearAll() {
        if (FileUtils.delete(PathUtils.getInternalAppCachePath())) {
            ToastUtils.showShort("清除成功！");
        } else {
            ToastUtils.showShort("清除失败！");
        }
    }

    @Override
    public void clear(String key) {
        File file = new File(PathUtils.getInternalAppCachePath(), key);
        if (!file.exists()) {
            ToastUtils.showShort("文件不存在！");
        } else {
            if (file.delete()) {
                ToastUtils.showShort("清除成功！");
            } else {
                ToastUtils.showShort("清除失败！");
            }
        }
    }
}
