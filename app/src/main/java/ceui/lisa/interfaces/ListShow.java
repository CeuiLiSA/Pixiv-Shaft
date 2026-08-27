package ceui.lisa.interfaces;

import java.util.Collections;
import java.util.List;

import ceui.loxia.KListShow;

/**
 * legacy 列表响应契约。顺带实现 {@link KListShow}，让这些 Java 响应模型能直接交给
 * {@code PixivFeedSource}（feeds 框架的 nextUrl 翻页协议），不必再为每个列表手写一遍
 * 「repo + FeedSource 二次包装」。
 */
public interface ListShow<Item> extends KListShow<Item> {

    List<Item> getList();

    String getNextUrl();

    @Override
    default List<Item> getDisplayList() {
        List<Item> list = getList();
        return list != null ? list : Collections.emptyList();
    }

    @Override
    default String getNextPageUrl() {
        return getNextUrl();
    }
}
