package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.content.Intent;
import android.view.View;
import android.widget.CompoundButton;

import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.FragmentSettingsBrowsingBinding;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.PixivSearchParamUtil;
import ceui.loxia.CloudHistoryConsent;
import ceui.pixiv.session.SessionManager;

/** 设置 · 浏览与搜索 */
public class FragmentSettingsBrowsing extends SettingsPageFragment<FragmentSettingsBrowsingBinding> {

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_settings_browsing;
    }

    @Override
    protected void initData() {
        // 启动时自动刷新首页推荐（issue #955）。关掉后冷启动直接展示上次的推荐快照，
        // 下次冷启才生效，所以提示语用「重启后生效」而不是通用的「设置成功」。
        baseBind.autoRefreshHomeFeed.setChecked(Shaft.sSettings.isAutoRefreshHomeFeed());
        baseBind.autoRefreshHomeFeed.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setAutoRefreshHomeFeed(isChecked);
                Common.showToast(getString(R.string.please_restart_app), 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.autoRefreshHomeFeedRela.setOnClickListener(v ->
                baseBind.autoRefreshHomeFeed.performClick());

        baseBind.saveHistory.setChecked(Shaft.sSettings.isSaveViewHistory());
        baseBind.saveHistory.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setSaveViewHistory(isChecked);
                Common.showToast(getString(R.string.string_428), 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.saveHistoryRela.setOnClickListener(v -> baseBind.saveHistory.performClick());

        // 浏览记录云同步开关 + 清除云端记录 (issue #889)
        baseBind.cloudHistorySync.setChecked(Shaft.sSettings.isCloudHistorySync());
        baseBind.cloudHistorySync.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                CloudHistoryConsent.setEnabled(isChecked);
                Common.showToast(getString(R.string.string_428), 2);
            }
        });
        baseBind.cloudHistorySyncRela.setOnClickListener(v -> baseBind.cloudHistorySync.performClick());
        baseBind.clearCloudHistoryRela.setOnClickListener(v ->
                CloudHistoryConsent.clearCloudHistory(mActivity, SessionManager.INSTANCE.getLoggedInUid()));

        // 过滤垃圾评论
        baseBind.filterComment.setChecked(Shaft.sSettings.isFilterComment());
        baseBind.filterComment.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setFilterComment(isChecked);
                Common.showToast(getString(R.string.string_428), 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.filterCommentRela.setOnClickListener(v -> baseBind.filterComment.performClick());

        // 默认开启R18内容过滤
        baseBind.r18FilterDefaultEnable.setChecked(Shaft.sSettings.isR18FilterDefaultEnable());
        baseBind.r18FilterDefaultEnable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setR18FilterDefaultEnable(isChecked);
                Common.showToast(getString(R.string.string_428), 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.r18FilterDefaultEnableRela.setOnClickListener(v ->
                baseBind.r18FilterDefaultEnable.performClick());

        baseBind.deleteAiIllust.setChecked(Shaft.sSettings.isDeleteAIIllust());
        baseBind.deleteAiIllust.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setDeleteAIIllust(isChecked);
                Common.showToast(getString(R.string.string_428), 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.deleteAiIllustRela.setOnClickListener(v -> baseBind.deleteAiIllust.performClick());

        baseBind.filterRankBookmarked.setChecked(Shaft.sSettings.isFilterRankBookmarked());
        baseBind.filterRankBookmarked.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setFilterRankBookmarked(isChecked);
                Common.showToast(getString(R.string.string_428), 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.filterRankBookmarkedRela.setOnClickListener(v ->
                baseBind.filterRankBookmarked.performClick());

        baseBind.deleteStarIllust.setChecked(Shaft.sSettings.isDeleteStarIllust());
        baseBind.deleteStarIllust.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setDeleteStarIllust(isChecked);
                Common.showToast(getString(R.string.string_428), 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.deleteStarIllustRela.setOnClickListener(v -> baseBind.deleteStarIllust.performClick());

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

        // 搜索结果收藏量筛选
        final String searchFilter = Shaft.sSettings.getSearchFilter();
        baseBind.searchFilter.setText(PixivSearchParamUtil.getSizeName(searchFilter));
        baseBind.searchFilterRela.setOnClickListener(v ->
                new QMUIDialog.CheckableDialogBuilder(mContext)
                        .setCheckedIndex(PixivSearchParamUtil.getSizeIndex(Shaft.sSettings.getSearchFilter()))
                        .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                        .addItems(PixivSearchParamUtil.ALL_SIZE_NAME, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Shaft.sSettings.setSearchFilter(PixivSearchParamUtil.ALL_SIZE_VALUE[which]);
                                Common.showToast(getString(R.string.string_428), 2);
                                Local.setSettings(Shaft.sSettings);
                                baseBind.searchFilter.setText(PixivSearchParamUtil.ALL_SIZE_NAME[which]);
                                dialog.dismiss();
                            }
                        })
                        .create()
                        .show());

        // 搜索结果默认排序方式
        final String searchDefaultSortType = Shaft.sSettings.getSearchDefaultSortType();
        baseBind.searchDefaultSortType.setText(PixivSearchParamUtil.getSortTypeName(searchDefaultSortType));
        baseBind.searchDefaultSortTypeRela.setOnClickListener(v ->
                new QMUIDialog.CheckableDialogBuilder(mContext)
                        .setCheckedIndex(PixivSearchParamUtil.getSortTypeIndex(Shaft.sSettings.getSearchDefaultSortType()))
                        .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                        .addItems(PixivSearchParamUtil.SORT_TYPE_NAME, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Shaft.sSettings.setSearchDefaultSortType(PixivSearchParamUtil.SORT_TYPE_VALUE[which]);
                                Common.showToast(getString(R.string.string_428), 2);
                                Local.setSettings(Shaft.sSettings);
                                baseBind.searchDefaultSortType.setText(PixivSearchParamUtil.SORT_TYPE_NAME[which]);
                                dialog.dismiss();
                            }
                        })
                        .create()
                        .show());

        // 同义词词典功能总开关（issue #904），默认关闭。
        // 关闭时所有相关 UI（详情页匹配框/长按菜单项/管理页入口/自动导入/自动勾选）完全隐藏。
        baseBind.synonymDictEnable.setChecked(Shaft.sSettings.isSynonymDictEnabled());
        baseBind.synonymDictEntryContainer.setVisibility(
                Shaft.sSettings.isSynonymDictEnabled() ? View.VISIBLE : View.GONE);
        baseBind.synonymDictEnable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setSynonymDictEnabled(isChecked);
                Local.setSettings(Shaft.sSettings);
                baseBind.synonymDictEntryContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                if (isChecked) {
                    // 首次打开：后台静默导入内置词典（已导入过的设备跳过）
                    final android.content.Context appContext = mContext.getApplicationContext();
                    new Thread(() ->
                            ceui.pixiv.ui.synonym.SynonymBuiltinDict.autoImportIfNeeded(appContext)
                    ).start();
                }
            }
        });
        baseBind.synonymDictEnableRela.setOnClickListener(v ->
                baseBind.synonymDictEnable.performClick());

        // 同义词词典管理入口（仅开关打开时可见）
        baseBind.synonymDictRela.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "同义词词典");
            startActivity(intent);
        });
    }
}
