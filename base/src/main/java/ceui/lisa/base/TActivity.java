package ceui.lisa.base;

import android.content.Intent;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import ceui.lisa.base.databinding.ActivityTemplateBinding;

public abstract class TActivity extends BaseActivity<ActivityTemplateBinding> {

    protected Fragment childFragment;

    @Override
    protected int initLayout() {
        return R.layout.activity_template;
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

    public abstract Fragment createFragment(@NonNull String name,
                                            @NonNull Intent intent);

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (childFragment != null) {
            childFragment.onActivityResult(requestCode, resultCode, data);
        }
    }
}
