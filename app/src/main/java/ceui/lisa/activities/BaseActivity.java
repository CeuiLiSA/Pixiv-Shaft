package ceui.lisa.activities;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.widget.ImageView;

public abstract class BaseActivity extends AppCompatActivity {

    protected Context mContext;
    protected Activity mActivity;
    protected int mLayoutID;

    protected String className = this.getClass().getSimpleName() + " ";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initLayout();
        setContentView(mLayoutID);

        mContext = this;
        mActivity = this;


        initView();
        initData();
    }



    protected abstract void initLayout();

    protected abstract void initView();

    protected abstract void initData();
}
