package ceui.lisa.core;

import java.util.List;

public interface IDWithList<T> {

    String getUUID();

    List<T> getList();
}
