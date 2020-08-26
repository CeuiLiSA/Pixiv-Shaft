package ceui.lisa.core;

import android.content.Context;

import com.scwang.smartrefresh.layout.api.RefreshFooter;
import com.scwang.smartrefresh.layout.api.RefreshHeader;

public interface DataView {

    boolean hasNext();

    boolean enableRefresh();

    RefreshHeader getHeader(Context context);

    RefreshFooter getFooter(Context context);

    boolean showNoDataHint();

    String token();
}
