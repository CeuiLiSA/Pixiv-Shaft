package ceui.lisa.interfaces;

import java.util.List;

public interface ListShow<Item> {

    List<Item> getList();

    String getNextUrl();
}
