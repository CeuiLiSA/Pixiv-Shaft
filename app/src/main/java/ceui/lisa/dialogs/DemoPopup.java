package ceui.lisa.dialogs;

import android.content.Context;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.SearchHintAdapter;
import ceui.lisa.model.TrendingtagResponse;
import razerdp.basepopup.BasePopupWindow;

public class DemoPopup extends BasePopupWindow {

    private String key;
    private View parentView;
    private List<TrendingtagResponse.TrendTagsBean> allItems = new ArrayList<>();

    public void attachData(List<TrendingtagResponse.TrendTagsBean> datas, String paramKey){
        this.allItems = datas;
        this.key = paramKey;
        if(parentView instanceof RecyclerView){
            ((RecyclerView) parentView).setLayoutManager(new LinearLayoutManager(getContext()));
            ((RecyclerView) parentView).setAdapter(new SearchHintAdapter(allItems, getContext(), key));
        }
    }

    public DemoPopup(Context context) {
        super(context);
    }

    @Override
    public View onCreateContentView() {
        parentView = createPopupById(R.layout.single_recyclerview);
        return parentView;
    }
}
