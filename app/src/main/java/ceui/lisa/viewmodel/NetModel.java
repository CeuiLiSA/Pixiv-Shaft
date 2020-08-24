package ceui.lisa.viewmodel;

import android.text.TextUtils;
import android.view.View;

import com.scwang.smartrefresh.layout.footer.ClassicsFooter;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.core.NetView;
import ceui.lisa.http.NullCtrl;

public class NetModel<T> extends BaseModel<T> implements NetView {

    @Override
    public void fresh() {

    }

    @Override
    public void loadMore() {

    }
}
