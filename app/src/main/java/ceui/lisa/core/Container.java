package ceui.lisa.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local handoff for artwork pages that are too large for Intent extras.
 *
 * Entries are owned by the receiving {@code VActivity}, which removes its page
 * when the Activity finishes. A concurrent map keeps handoff safe even when a
 * producer prepares a page off the main thread.
 */
public final class Container {

    private final Map<String, PageData> pages = new ConcurrentHashMap<>();

    /**
     * 只在当前 app 进程内存储，进程结束后自然失效。
     *
     * @param pageData 一个插画列表
     */
    public void addPageToMap(PageData pageData) {
        if (pageData == null) {
            return;
        }

        if (pageData.getUUID() == null || pageData.getUUID().isEmpty()) {
            return;
        }

        pages.put(pageData.getUUID(), pageData);
    }

    public PageData getPage(String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            return null;
        }
        return pages.get(uuid);
    }

    public void removePage(String uuid) {
        if (uuid != null && !uuid.isEmpty()) {
            pages.remove(uuid);
        }
    }

    public void clear() {
        pages.clear();
    }

    private Container() {
    }

    private static class SingleTonHolder {
        private static final Container INSTANCE = new Container();
    }

    public static Container get() {
        return SingleTonHolder.INSTANCE;
    }
}
