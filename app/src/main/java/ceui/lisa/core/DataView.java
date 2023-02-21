package ceui.lisa.core;

import android.content.Context;

import com.scwang.smart.refresh.layout.api.RefreshFooter;
import com.scwang.smart.refresh.layout.api.RefreshHeader;

public interface DataView {

    boolean hasNext();

    boolean enableRefresh();

    RefreshHeader getHeader(Context context);

    RefreshFooter getFooter(Context context);

    boolean showNoDataHint();

    String token();

    boolean localData();
}
