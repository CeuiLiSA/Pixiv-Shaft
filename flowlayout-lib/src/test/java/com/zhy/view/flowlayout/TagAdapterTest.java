package com.zhy.view.flowlayout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.View;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class TagAdapterTest {

    @Test
    public void exposesItemsAndCountInSourceOrder() {
        TagAdapter<String> adapter = adapter("first", "second");

        assertEquals(2, adapter.getCount());
        assertEquals("first", adapter.getItem(0));
        assertEquals("second", adapter.getItem(1));
    }

    @SuppressWarnings("deprecation")
    @Test
    public void replacingSelectionDeduplicatesPositionsAndNotifiesOnce() {
        TagAdapter<String> adapter = adapter("a", "b", "c", "d");
        AtomicInteger changes = new AtomicInteger();
        adapter.setOnDataChangedListener(changes::incrementAndGet);

        adapter.setSelectedList(3, 1, 3);

        assertEquals(new HashSet<>(Arrays.asList(1, 3)), adapter.getPreCheckedList());
        assertEquals(1, changes.get());

        adapter.setSelectedList((java.util.Set<Integer>) null);
        assertTrue(adapter.getPreCheckedList().isEmpty());
        assertEquals(2, changes.get());
    }

    private static TagAdapter<String> adapter(String... values) {
        return new TagAdapter<String>(values) {
            @Override
            public View getView(FlowLayout parent, int position, String value) {
                return null;
            }
        };
    }
}
