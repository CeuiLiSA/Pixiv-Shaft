package ceui.lisa.interfaces;

import android.view.View;

//支持点击，长按， OnItemClickListener只支持点击
public interface FullClickListener {

    void onItemClick(View v, int position, int viewType);

    void onItemLongClick(View v, int position, int viewType);

}
