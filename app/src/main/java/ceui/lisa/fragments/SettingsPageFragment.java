package ceui.lisa.fragments;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.viewbinding.ViewBinding;

import ceui.lisa.R;

/**
 * 二级设置分类页公共基类：返回键 + 搜索跳转落点高亮。
 * 每个分类页布局共享 toolbar / scroll_view / parent_linear 三个 id。
 */
public abstract class SettingsPageFragment<T extends ViewBinding> extends BaseFragment<T> {

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Toolbar toolbar = view.findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> mActivity.finish());
        }
        // 行底色跟随主题色（同 V3 搜索 sheet 卡片的隐约 tint）
        SettingsCatalog.applyThemedRowBg(view);
        SettingsCatalog.maybeHighlight(this, view);
    }
}
