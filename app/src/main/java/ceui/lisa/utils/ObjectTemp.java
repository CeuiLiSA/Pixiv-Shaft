package ceui.lisa.utils;

import java.util.Map;
import java.util.WeakHashMap;

public class ObjectTemp {
    private static Map<String, Object> map;

    public static Object put(String key, Object value) {
        if (map == null) {
            map = new WeakHashMap<>();
        }
        return map.put(key, value);
    }

    public static Object get(String key) {
        if (map != null) {
            return map.get(key);
        } else {
            return null;
        }
    }

    public static Object remove(String key) {
        if (map != null) {
            return map.remove(key);
        } else {
            return null;
        }
    }

    public static void removeAll() {
        if (map != null) {
            map = null;
        }
    }

    public static Map<String, Object> getMap() {
        return map;
    }
}
