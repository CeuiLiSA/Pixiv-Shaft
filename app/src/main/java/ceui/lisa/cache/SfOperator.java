package ceui.lisa.cache;

import android.content.SharedPreferences;

import ceui.lisa.activities.Shaft;

/**
 * 使用shared preference 存储
 */
public class SfOperator implements IOperate {

    @Override
    public <T> T getModel(String key, Class<T> pClass) {
        String value = Shaft.sPreferences.getString(key, "");
        return Shaft.sGson.fromJson(value, pClass);
    }

    @Override
    public <T> void saveModel(String ket, T pT) {
        SharedPreferences.Editor editor = Shaft.sPreferences.edit();
        editor.putString(ket, Shaft.sGson.toJson(pT));
        editor.apply();
    }

    @Override
    public void clearAll() {

    }

    @Override
    public void clear(String key) {
        SharedPreferences.Editor editor = Shaft.sPreferences.edit();
        editor.putString(key, "");
        editor.apply();
    }
}
