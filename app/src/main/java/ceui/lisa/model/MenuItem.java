package ceui.lisa.model;

import android.view.View;

public class MenuItem {

    private String name;
    private int imageRes;
    private View.OnClickListener mListener;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getImageRes() {
        return imageRes;
    }

    public void setImageRes(int imageRes) {
        this.imageRes = imageRes;
    }

    public MenuItem(String name, int imageRes, View.OnClickListener listener) {
        this.name = name;
        this.imageRes = imageRes;
        mListener = listener;
    }

    public View.OnClickListener getListener() {
        return mListener;
    }

    public void setListener(View.OnClickListener listener) {
        mListener = listener;
    }
}
