package ceui.lisa.core;

import android.content.Context;

import com.scwang.smartrefresh.header.MaterialHeader;
import com.scwang.smartrefresh.layout.api.RefreshFooter;
import com.scwang.smartrefresh.layout.api.RefreshHeader;
import com.scwang.smartrefresh.layout.footer.ClassicsFooter;

import ceui.lisa.activities.Shaft;

public interface DataView {

    boolean hasNext();

    boolean enableRefresh();

    RefreshHeader getHeader(Context context);

    RefreshFooter getFooter(Context context);

    boolean showNoDataHint();

    String token();
}
