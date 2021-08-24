package ceui.lisa.helper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import ceui.lisa.models.Deduplicatable;

/**
 * 自动去重的 ArrayList
 * @param <E>
 */
public class DeduplicateArrayList<E extends Deduplicatable> extends ArrayList<E> {

    private Set<Object> innerSet = null;

    public DeduplicateArrayList(int initialCapacity) {
        super(initialCapacity);
        innerSet = new HashSet<>();
    }

    public DeduplicateArrayList() {
        innerSet = new HashSet<>();
    }

    public DeduplicateArrayList(@NonNull Collection<? extends E> c) {
        super(c);
        innerSet = new HashSet<>();
        List<E> removeList = new ArrayList<>();
        for (int i = 0; i < size(); i++) {
            E e = get(i);
            Object obj = e.getDuplicateKey();
            if (innerSet.contains(obj)) {
                removeList.add(e);
            } else {
                innerSet.add(obj);
            }
        }
        removeAll(removeList);
    }

    @Override
    public boolean add(E e) {
        Object obj = e.getDuplicateKey();
        if (innerSet.contains(obj)) {
            return true;
        } else {
            innerSet.add(obj);
            return super.add(e);
        }
    }

    @Override
    public void add(int index, E element) {
        Object obj = element.getDuplicateKey();
        if (!innerSet.contains(obj)) {
            innerSet.add(obj);
            super.add(index, element);
        }
    }

    @Override
    public boolean addAll(@NonNull Collection<? extends E> c) {
        synchronized (this) {
            List<E> cc = new ArrayList<>(c);
            List<E> removeList = new ArrayList<>();
            for (int i = 0; i < cc.size(); i++) {
                E e = cc.get(i);
                Object obj = e.getDuplicateKey();
                if (innerSet.contains(obj)) {
                    removeList.add(e);
                } else {
                    innerSet.add(obj);
                }
            }
            cc.removeAll(removeList);
            return super.addAll(cc);
        }
    }

    @Override
    public boolean addAll(int index, @NonNull Collection<? extends E> c) {
        synchronized (this) {
            List<E> cc = new ArrayList<>(c);
            List<E> removeList = new ArrayList<>();
            for (int i = 0; i < cc.size(); i++) {
                E e = cc.get(i);
                Object obj = e.getDuplicateKey();
                if (innerSet.contains(obj)) {
                    removeList.add(e);
                } else {
                    innerSet.add(obj);
                }
            }
            cc.removeAll(removeList);
            return super.addAll(index, cc);
        }
    }

    @Override
    public boolean remove(@Nullable @org.jetbrains.annotations.Nullable Object o) {
        if (o instanceof Deduplicatable) {
            Object key = ((Deduplicatable) o).getDuplicateKey();
            innerSet.remove(key);
        }
        return super.remove(o);
    }

    @Override
    public E remove(int index) {
        E e = super.remove(index);
        Object obj = e.getDuplicateKey();
        innerSet.remove(obj);
        return e;
    }

    @Override
    public void clear() {
        super.clear();
        innerSet.clear();
    }

    /**
     *
     * @param dist 目标集合
     * @param src 数据来源集合
     * @param <T> Deduplicatable
     */
    public static <T extends Deduplicatable> void addAllWithNoRepeat(Collection<T> dist, Collection<T> src) {
        final Set<Object> set = new HashSet<>();
        for (T element : dist) {
            set.add(element.getDuplicateKey());
        }
        for (T element : src) {
            if (!set.contains(element.getDuplicateKey())) {
                dist.add(element);
            }
        }
    }
}
