package ceui.lisa.interfaces;

import android.content.Context;

import com.scwang.smartrefresh.header.MaterialHeader;
import com.scwang.smartrefresh.layout.api.RefreshFooter;
import com.scwang.smartrefresh.layout.api.RefreshHeader;
import com.scwang.smartrefresh.layout.footer.ClassicsFooter;

public class BaseCtrl {

    public boolean hasNext(){
        return true;
    }

    public boolean enableRefresh(){
        return true;
    }

    public RefreshHeader getHeader(Context context){
        return new MaterialHeader(context);
    }

    public RefreshFooter getFooter(Context context){
        return new ClassicsFooter(context);
    }

    public boolean showNoDataHint() {
        return true;
    }
}
