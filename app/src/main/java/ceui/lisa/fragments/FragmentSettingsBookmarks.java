package ceui.lisa.fragments;

import android.widget.CompoundButton;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.FragmentSettingsBookmarksBinding;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;

/** 设置 · 收藏与互动 */
public class FragmentSettingsBookmarks extends SettingsPageFragment<FragmentSettingsBookmarksBinding> {

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_settings_bookmarks;
    }

    @Override
    protected void initData() {
        baseBind.showLikeButton.setChecked(Shaft.sSettings.isPrivateStar());
        baseBind.showLikeButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setPrivateStar(isChecked);
                Common.showToast(getString(R.string.string_428), 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.showLikeButtonRela.setOnClickListener(v -> baseBind.showLikeButton.performClick());

        baseBind.privateFollow.setChecked(Shaft.sSettings.isPrivateFollow());
        baseBind.privateFollow.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setPrivateFollow(isChecked);
                Common.showToast(getString(R.string.string_428), 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.privateFollowRela.setOnClickListener(v -> baseBind.privateFollow.performClick());

        baseBind.hideStarBar.setChecked(Shaft.sSettings.isHideStarButtonAtMyCollection());
        baseBind.hideStarBar.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setHideStarButtonAtMyCollection(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.hideStarBarRela.setOnClickListener(v -> baseBind.hideStarBar.performClick());

        baseBind.filterInvalidBookmarks.setChecked(Shaft.sSettings.isFilterInvalidBookmarks());
        baseBind.filterInvalidBookmarks.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setFilterInvalidBookmarks(isChecked);
                Common.showToast(getString(R.string.string_428), 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.filterInvalidBookmarksRela.setOnClickListener(v ->
                baseBind.filterInvalidBookmarks.performClick());

        // 收藏库本地镜像。关掉是立即生效的：引擎每次 tick 都现读这个开关，
        // 不必重启 app（正在飞的那一页会跑完，之后不再发新请求）。
        baseBind.bookmarkMirror.setChecked(Shaft.sSettings.isBookmarkMirrorEnabled());
        baseBind.bookmarkMirror.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setBookmarkMirrorEnabled(isChecked);
                Common.showToast(getString(R.string.string_428), 2);
                Local.setSettings(Shaft.sSettings);
                if (isChecked) {
                    // 开回来时主动踢一脚，用户不用等下一个空闲心跳
                    ceui.loxia.ServiceProviderKt.appServices(requireContext())
                            .getBookmarkMirror().kick("settings toggled on");
                }
            }
        });
        baseBind.bookmarkMirrorRela.setOnClickListener(v -> baseBind.bookmarkMirror.performClick());

        baseBind.selectAllTag.setChecked(Shaft.sSettings.isStarWithTagSelectAll());
        baseBind.selectAllTag.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setStarWithTagSelectAll(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.selectAllTagRela.setOnClickListener(v -> baseBind.selectAllTag.performClick());

        baseBind.showRelatedWhenStar.setChecked(Shaft.sSettings.isShowRelatedWhenStar());
        baseBind.showRelatedWhenStar.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setShowRelatedWhenStar(isChecked);
                Common.showToast(getString(R.string.please_restart_app));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.showRelatedWhenStarRela.setOnClickListener(v ->
                baseBind.showRelatedWhenStar.performClick());

        baseBind.autoFollowAfterStar.setChecked(Shaft.sSettings.isAutoFollowAfterStar());
        baseBind.autoFollowAfterStar.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setAutoFollowAfterStar(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.autoFollowAfterStarRela.setOnClickListener(v ->
                baseBind.autoFollowAfterStar.performClick());

        baseBind.autoDownloadAfterStar.setChecked(Shaft.sSettings.isAutoDownloadAfterStar());
        baseBind.autoDownloadAfterStar.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setAutoDownloadAfterStar(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.autoDownloadAfterStarRela.setOnClickListener(v ->
                baseBind.autoDownloadAfterStar.performClick());

        baseBind.downloadAutoPostLike.setChecked(Shaft.sSettings.isAutoPostLikeWhenDownload());
        baseBind.downloadAutoPostLike.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setAutoPostLikeWhenDownload(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.downloadAutoPostLikeRela.setOnClickListener(v ->
                baseBind.downloadAutoPostLike.performClick());
    }
}
