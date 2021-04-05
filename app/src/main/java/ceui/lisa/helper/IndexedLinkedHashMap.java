package ceui.lisa.helper;

import java.util.HashMap;
import java.util.LinkedHashMap;

import androidx.annotation.Nullable;

public class IndexedLinkedHashMap<K, V> extends LinkedHashMap<K, V> {

    HashMap<Integer, K> index;
    int curr = 0;

    public IndexedLinkedHashMap() {
        super();
        index = new HashMap<>();
    }

    @Nullable
    @Override
    public V put(K key, V value) {
        V v = super.put(key, value);
        index.put(curr++, key);
        return v;
    }

    // Bad implementation, should override "merge" function but it has api version requirements
    public IndexedLinkedHashMap<K, V> tidyIndexes() {
        curr = 0;
        if (index == null) {
            index = new HashMap<>();
        }
        index.clear();
        for (K key : keySet()) {
            index.put(curr++, key);
        }
        return this;
    }

    public V getIndexed(int i) {
        return super.get(index.get(i));
    }
}
