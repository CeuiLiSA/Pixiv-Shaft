package ceui.lisa.base;

import android.content.Intent;
import android.text.TextUtils;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import ceui.lisa.base.databinding.ActivityTemplateBinding;

public abstract class TActivity extends BaseActivity<ActivityTemplateBinding> {

    protected Fragment childFragment;

    @Override
    protected void initLayout() {
        mLayoutID = R.layout.activity_template;
    }

    @Override
    protected void initView() {
        Intent intent = getIntent();

        if (intent == null) {
            return;
        }

        //Determine what fragment is generated based on fragmentType
        String name = intent.getStringExtra(Params.FRAG_TYPE);

        if (TextUtils.isEmpty(name)) {
            return;
        }

        childFragment = createFragment(name, intent);
        if (childFragment != null) {
            FragmentManager fragmentManager = getSupportFragmentManager();
            fragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, childFragment)
                    .commitNow();
        }
    }

    public abstract Fragment createFragment(String name,
                                            Intent intent);
}
