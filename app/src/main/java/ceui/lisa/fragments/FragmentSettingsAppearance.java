package ceui.lisa.fragments;

import static ceui.lisa.helper.ThemeHelper.ThemeType.DARK_MODE;
import static ceui.lisa.helper.ThemeHelper.ThemeType.DEFAULT_MODE;
import static ceui.lisa.helper.ThemeHelper.ThemeType.LIGHT_MODE;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.TextUtils;
import android.widget.CompoundButton;

import androidx.appcompat.app.AppCompatActivity;

import ceui.pixiv.witstudio.dialog.WitDialog;

import java.util.Arrays;
import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.FragmentSettingsAppearanceBinding;
import ceui.lisa.helper.NavigationLocationHelper;
import ceui.lisa.helper.ThemeHelper;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.pixiv.ui.settings.CustomThemeColor;
import ceui.pixiv.ui.settings.ThemeColorCatalog;
import ceui.pixiv.ui.settings.ThemeColorFeedFragment;
import ceui.pixiv.widget.RecommendCardWidgetProvider;
import ceui.pixiv.widget.RecommendStripWidgetProvider;
import ceui.pixiv.widget.SpotlightWidgetProvider;
import ceui.pixiv.ui.navigation.TemplateRoute;

/** 设置 · 界面 */
public class FragmentSettingsAppearance extends SettingsPageFragment<FragmentSettingsAppearanceBinding> {

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_settings_appearance;
    }

    @Override
    protected void initData() {
        // 主题模式
        baseBind.themeMode.setText(Shaft.sSettings.getThemeType().toDisplayString(mContext));
        baseBind.themeModeRela.setOnClickListener(v -> {
            final int index = Shaft.sSettings.getThemeType().themeTypeIndex;
            ThemeHelper.ThemeType[] THEME_MODES = new ThemeHelper.ThemeType[]{
                    DEFAULT_MODE,
                    LIGHT_MODE,
                    DARK_MODE
            };
            String[] THEME_NAME = new String[]{
                    THEME_MODES[0].toDisplayString(mContext),
                    THEME_MODES[1].toDisplayString(mContext),
                    THEME_MODES[2].toDisplayString(mContext)
            };
            new WitDialog.CheckableDialogBuilder(mActivity)
                    .setCheckedIndex(index)
                    .addItems(THEME_NAME, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (which != index) {
                                Shaft.sSettings.setThemeType(((AppCompatActivity) mActivity), THEME_MODES[which]);
                                baseBind.themeMode.setText(THEME_NAME[which]);
                                Local.setSettings(Shaft.sSettings);
                            }
                            dialog.dismiss();
                        }
                    })
                    .show();
        });

        // 主题色彩
        setThemeName();
        baseBind.colorSelectRela.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.THEME_COLOR.key);
            startActivity(intent);
        });

        // 标签译文颜色（#1047-5）：跟随主题 or 从主题色彩页中选择
        setTagTranslationColorName();
        baseBind.tagTranslationColorRela.setOnClickListener(v -> showTagTranslationColorDialog());

        // 语言
        baseBind.appLanguage.setText(currentLanguageDisplay());
        baseBind.appLanguageRela.setOnClickListener(v -> {
            // "跟随系统" 放在首位
            java.util.List<String> labels = new java.util.ArrayList<>();
            java.util.List<String> tags = new java.util.ArrayList<>();
            labels.add(getString(R.string.language_follow_system));
            tags.add(null);
            for (String tag : ceui.pixiv.i18n.AppLocales.INSTANCE.getSupportedTags()) {
                labels.add(ceui.pixiv.i18n.AppLocales.INSTANCE.displayName(tag));
                tags.add(tag);
            }
            int checkedIndex = 0; // default: follow system
            if (!ceui.pixiv.i18n.AppLocales.INSTANCE.isFollowingSystem()) {
                String currentTag = ceui.pixiv.i18n.AppLocales.INSTANCE.currentLocale().toLanguageTag();
                int idx = tags.indexOf(currentTag);
                if (idx >= 0) checkedIndex = idx;
            }
            new WitDialog.CheckableDialogBuilder(getActivity())
                    .setCheckedIndex(checkedIndex)
                    .addItems(labels.toArray(new String[0]), (dialog, which) -> {
                        String tag = tags.get(which);
                        ceui.pixiv.i18n.AppLocales.INSTANCE.apply(tag);
                        baseBind.appLanguage.setText(labels.get(which));
                        Common.showToast(getString(R.string.string_428), 2);
                        dialog.dismiss();
                    })
                    .show();
        });

        // 列数
        baseBind.lineCount.setText(getString(R.string.string_349, Shaft.sSettings.getLineCount()));
        baseBind.lineCountRela.setOnClickListener(v -> {
            int index = 0;
            if (Shaft.sSettings.getLineCount() == 3) {
                index = 1;
            } else if (Shaft.sSettings.getLineCount() == 4) {
                index = 2;
            }
            String[] LINE_COUNT = new String[]{
                    getString(R.string.string_349, 2),
                    getString(R.string.string_349, 3),
                    getString(R.string.string_349, 4)
            };
            final int selectIndex = index;
            new WitDialog.CheckableDialogBuilder(mActivity)
                    .setCheckedIndex(selectIndex)
                    .addItems(LINE_COUNT, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (which != selectIndex) {
                                int lineCount = which + 2;
                                Shaft.sSettings.setLineCount(lineCount);
                                baseBind.lineCount.setText(getString(R.string.string_349, lineCount));
                                Local.setSettings(Shaft.sSettings);
                                Common.showToast(getString(R.string.please_restart_app), 2);
                            }
                            dialog.dismiss();
                        }
                    })
                    .show();
        });

        // 关注动态布局模式
        baseBind.layoutMode.setText(Shaft.sSettings.isUseStaggeredLayout()
                ? getString(R.string.layout_staggered) : getString(R.string.layout_linear));
        baseBind.layoutModeRela.setOnClickListener(v -> {
            String[] options = new String[]{
                    getString(R.string.layout_staggered),
                    getString(R.string.layout_linear)
            };
            int currentIndex = Shaft.sSettings.isUseStaggeredLayout() ? 0 : 1;
            new WitDialog.CheckableDialogBuilder(mActivity)
                    .setCheckedIndex(currentIndex)
                    .addItems(options, (dialog, which) -> {
                        if (which != currentIndex) {
                            Shaft.sSettings.setUseStaggeredLayout(which == 0);
                            baseBind.layoutMode.setText(options[which]);
                            Local.setSettings(Shaft.sSettings);
                        }
                        dialog.dismiss();
                    })
                    .show();
        });

        // 小说列表显示标签
        baseBind.showNovelCardTags.setChecked(Shaft.sSettings.isShowNovelCardTags());
        baseBind.showNovelCardTags.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setShowNovelCardTags(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.showNovelCardTagsRela.setOnClickListener(v ->
                baseBind.showNovelCardTags.performClick());

        // 小说列表显示标签译文（默认关闭，保留 #1038 的紧凑列表默认值）
        baseBind.showNovelCardTagTranslations.setChecked(Shaft.sSettings.isShowNovelCardTagTranslations());
        baseBind.showNovelCardTagTranslations.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setShowNovelCardTagTranslations(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.showNovelCardTagTranslationsRela.setOnClickListener(v ->
                baseBind.showNovelCardTagTranslations.performClick());

        // 小说列表卡片标签折叠
        baseBind.collapseNovelCardTags.setChecked(Shaft.sSettings.isCollapseNovelCardTags());
        baseBind.collapseNovelCardTags.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setCollapseNovelCardTags(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.collapseNovelCardTagsRela.setOnClickListener(v ->
                baseBind.collapseNovelCardTags.performClick());

        // 首页导航栏初始化位置
        String navigationInitPositionSettingValue = Shaft.sSettings.getNavigationInitPosition();
        final String navigationInitPosition = !TextUtils.isEmpty(navigationInitPositionSettingValue)
                ? navigationInitPositionSettingValue : NavigationLocationHelper.TUIJIAN;
        baseBind.navigationInitPosition.setText(NavigationLocationHelper.SETTING_NAME_MAP.get(navigationInitPosition));
        baseBind.navigationInitPositionRela.setOnClickListener(v -> {
            String[] OPTION_VALUES = NavigationLocationHelper.SETTING_NAME_MAP.keySet().toArray(new String[0]);
            String[] OPTION_NAMES = NavigationLocationHelper.SETTING_NAME_MAP.values().toArray(new String[0]);
            String currentValue = Shaft.sSettings.getNavigationInitPosition();
            final String current = !TextUtils.isEmpty(currentValue) ? currentValue : NavigationLocationHelper.TUIJIAN;
            final int index = Arrays.asList(OPTION_VALUES).indexOf(current);
            new WitDialog.CheckableDialogBuilder(mActivity)
                    .setCheckedIndex(index)
                    .addItems(OPTION_NAMES, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (which != index) {
                                Shaft.sSettings.setNavigationInitPosition(OPTION_VALUES[which]);
                                baseBind.navigationInitPosition.setText(OPTION_NAMES[which]);
                                Local.setSettings(Shaft.sSettings);
                            }
                            dialog.dismiss();
                        }
                    })
                    .show();
        });

        // 推荐页面展示内容（插画、小说共用）。与首页导航顺序一样重启后生效。
        final String[] recommendPageOrders = new String[]{
                getString(R.string.recommend_page_works_first),
                getString(R.string.recommend_page_tags_first)
        };
        baseBind.recommendPageOrder.setText(
                recommendPageOrders[Shaft.sSettings.isRecommendHotTagsFirst() ? 1 : 0]);
        baseBind.recommendPageOrderRela.setOnClickListener(v -> {
            final int index = Shaft.sSettings.isRecommendHotTagsFirst() ? 1 : 0;
            new WitDialog.CheckableDialogBuilder(mActivity)
                    .setTitle(R.string.recommend_page_content)
                    .setCheckedIndex(index)
                    .addItems(recommendPageOrders, (dialog, which) -> {
                        if (which != index) {
                            Shaft.sSettings.setRecommendHotTagsFirst(which == 1);
                            Local.setSettings(Shaft.sSettings);
                            baseBind.recommendPageOrder.setText(recommendPageOrders[which]);
                            Common.showToast(getString(R.string.please_restart_app));
                        }
                        dialog.dismiss();
                    })
                    .show();
        });

        // 首页底部页签顺序
        setOrderName();
        baseBind.orderSelect.setOnClickListener(v -> {
            final int index = Shaft.sSettings.getBottomBarOrder();
            String[] ORDER_NAME = new String[]{
                    getString(R.string.string_343),
                    getString(R.string.string_344),
                    getString(R.string.string_345),
                    getString(R.string.string_346),
                    getString(R.string.string_347),
                    getString(R.string.string_348),
            };
            new WitDialog.CheckableDialogBuilder(mActivity)
                    .setCheckedIndex(index)
                    .addItems(ORDER_NAME, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (which == index) {
                                Common.showLog("什么也不做");
                            } else {
                                Shaft.sSettings.setBottomBarOrder(which);
                                baseBind.orderSelect.setText(ORDER_NAME[which]);
                                Local.setSettings(Shaft.sSettings);
                                Common.showToast(getString(R.string.please_restart_app));
                            }
                            dialog.dismiss();
                        }
                    })
                    .show();
        });
        baseBind.bottomBarOrderRela.setOnClickListener(v -> baseBind.orderSelect.performClick());

        // Muzei 动态壁纸来源设置（issue #548）；同一个 Activity 也是 Muzei 侧「来源设置」的落点
        baseBind.muzeiSettingsRela.setOnClickListener(v ->
                startActivity(new Intent(mContext, ceui.pixiv.muzei.MuzeiSettingsActivity.class)));

        // 桌面小组件换图间隔（issue #641，只作用于推荐类小组件；日榜内容一天一变，固定 6 小时）
        final int[] INTERVAL_MINUTES = new int[]{15, 30, 60, 120, 360};
        final String[] INTERVAL_NAMES = new String[INTERVAL_MINUTES.length];
        for (int i = 0; i < INTERVAL_MINUTES.length; i++) {
            INTERVAL_NAMES[i] = intervalDisplay(INTERVAL_MINUTES[i]);
        }
        baseBind.widgetRefreshInterval.setText(
                intervalDisplay(Shaft.sSettings.getWidgetRefreshIntervalMinutes()));
        baseBind.widgetRefreshIntervalRela.setOnClickListener(v -> {
            int checked = Arrays.binarySearch(INTERVAL_MINUTES,
                    Shaft.sSettings.getWidgetRefreshIntervalMinutes());
            final int index = checked >= 0 ? checked : 1; // 非预设值按默认 30 分钟高亮
            new WitDialog.CheckableDialogBuilder(mActivity)
                    .setCheckedIndex(index)
                    .addItems(INTERVAL_NAMES, (dialog, which) -> {
                        // 按值比较而不是按 index：存量值不是预设值时 index 回退高亮在
                        // 30 分钟，此时选 30 分钟仍然要落盘
                        if (INTERVAL_MINUTES[which] != Shaft.sSettings.getWidgetRefreshIntervalMinutes()) {
                            Shaft.sSettings.setWidgetRefreshIntervalMinutes(INTERVAL_MINUTES[which]);
                            Local.setSettings(Shaft.sSettings);
                            baseBind.widgetRefreshInterval.setText(INTERVAL_NAMES[which]);
                            // UPDATE 策略：已加到桌面的小组件立即按新间隔重排。
                            // 只重排桌面上确实有实例的——周期任务的存在与否由
                            // onEnabled/onDisabled 管理，这里不能凭空创建
                            if (hasWidget(SpotlightWidgetProvider.class)) {
                                SpotlightWidgetProvider.schedulePeriodic(mContext);
                            }
                            if (hasWidget(RecommendStripWidgetProvider.class)) {
                                RecommendStripWidgetProvider.schedulePeriodic(mContext);
                            }
                            if (hasWidget(RecommendCardWidgetProvider.class)) {
                                RecommendCardWidgetProvider.schedulePeriodic(mContext);
                            }
                        }
                        dialog.dismiss();
                    })
                    .show();
        });

        // 小组件浮在封面上的两个按钮（#1013：挡画面）
        baseBind.widgetHideBookmarkButton.setChecked(Shaft.sSettings.isWidgetHideBookmarkButton());
        baseBind.widgetHideBookmarkButton.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Shaft.sSettings.setWidgetHideBookmarkButton(isChecked);
            Local.setSettings(Shaft.sSettings);
            refreshWidgets();
        });
        baseBind.widgetHideBookmarkButtonRela.setOnClickListener(
                v -> baseBind.widgetHideBookmarkButton.performClick());

        baseBind.widgetHideRefreshButton.setChecked(Shaft.sSettings.isWidgetHideRefreshButton());
        baseBind.widgetHideRefreshButton.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Shaft.sSettings.setWidgetHideRefreshButton(isChecked);
            Local.setSettings(Shaft.sSettings);
            refreshWidgets();
        });
        baseBind.widgetHideRefreshButtonRela.setOnClickListener(
                v -> baseBind.widgetHideRefreshButton.performClick());

        // APP主页显示R页面
        baseBind.mainViewR18.setChecked(Shaft.sSettings.isMainViewR18());
        baseBind.mainViewR18.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setMainViewR18(isChecked);
                Common.showToast(getString(R.string.please_restart_app), 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.mainViewR18Rela.setOnClickListener(v -> baseBind.mainViewR18.performClick());

        // 平板双栏（#931）：规则只能在进程启动时注册一次，所以改完必须重启
        baseBind.tabletSplitScreen.setChecked(Shaft.sSettings.isTabletSplitScreen());
        baseBind.tabletSplitScreen.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Shaft.sSettings.setTabletSplitScreen(isChecked);
            Local.setSettings(Shaft.sSettings);
            Common.showToast(getString(R.string.please_restart_app), 2);
        });
        baseBind.tabletSplitScreenRela.setOnClickListener(
                v -> baseBind.tabletSplitScreen.performClick());
    }

    private boolean hasWidget(Class<?> providerClass) {
        return AppWidgetManager.getInstance(mContext)
                .getAppWidgetIds(new ComponentName(mContext, providerClass)).length > 0;
    }

    /** 按钮显隐是渲染时读的设置，桌面上已有的实例要重推一次才能立刻生效 */
    private void refreshWidgets() {
        notifyWidgetUpdate(RecommendCardWidgetProvider.class);
        notifyWidgetUpdate(SpotlightWidgetProvider.class);
    }

    private void notifyWidgetUpdate(Class<?> providerClass) {
        int[] ids = AppWidgetManager.getInstance(mContext)
                .getAppWidgetIds(new ComponentName(mContext, providerClass));
        if (ids.length == 0) {
            return;
        }
        Intent intent = new Intent(mContext, providerClass);
        intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
        mContext.sendBroadcast(intent);
    }

    private String intervalDisplay(int minutes) {
        return minutes < 60
                ? getString(R.string.v3_widget_interval_minutes, minutes)
                : getString(R.string.v3_widget_interval_hours, minutes / 60);
    }

    private void setOrderName() {
        final int index = Shaft.sSettings.getBottomBarOrder();
        String[] ORDER_NAME = new String[]{
                getString(R.string.string_343),
                getString(R.string.string_344),
                getString(R.string.string_345),
                getString(R.string.string_346),
                getString(R.string.string_347),
                getString(R.string.string_348),
        };
        baseBind.orderSelect.setText(ORDER_NAME[index]);
    }

    private void setThemeName() {
        // 自定义档（issue #1014）不在预设目录里：nameResOf 拿 -1 会走越界回落，把「自定义」
        // 显示成 0 号预设「矢尹紫」。带上色值，用户一眼能对上自己设的那个色。
        if (CustomThemeColor.isActive()) {
            baseBind.colorSelect.setText(
                    getString(R.string.custom_theme_color_entry) + " " + CustomThemeColor.currentHex());
            return;
        }
        final int index = Shaft.sSettings.getThemeIndex();
        baseBind.colorSelect.setText(getString(ThemeColorCatalog.nameResOf(index)));
    }

    private void setTagTranslationColorName() {
        if (Shaft.sSettings.isTagTranslationColorFollowTheme()) {
            baseBind.tagTranslationColor.setText(getString(R.string.tag_translation_color_follow_theme));
            return;
        }
        if (Shaft.sSettings.getTagTranslationColorIndex() == CustomThemeColor.INDEX) {
            String hex = CustomThemeColor.normalize(Shaft.sSettings.getTagTranslationColorCustomHex());
            baseBind.tagTranslationColor.setText(getString(R.string.custom_theme_color_entry) + " " +
                    (hex != null ? hex : CustomThemeColor.currentHex()));
            return;
        }
        baseBind.tagTranslationColor.setText(getString(ThemeColorCatalog.nameResOf(Shaft.sSettings.getTagTranslationColorIndex())));
    }

    private void showTagTranslationColorDialog() {
        int checkedIndex = Shaft.sSettings.isTagTranslationColorFollowTheme() ? 0 : 1;
        new WitDialog.CheckableDialogBuilder(mActivity)
                .setCheckedIndex(checkedIndex)
                .addItems(new String[]{
                        getString(R.string.tag_translation_color_follow_theme),
                        getString(R.string.tag_translation_color_pick_from_theme_page)
                }, (dialog, which) -> {
                    if (which == 0) {
                        Shaft.sSettings.setTagTranslationColorFollowTheme();
                        baseBind.tagTranslationColor.setText(getString(R.string.tag_translation_color_follow_theme));
                        Local.setSettings(Shaft.sSettings);
                    } else {
                        Intent intent = new Intent(mContext, TemplateActivity.class);
                        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.THEME_COLOR.key);
                        intent.putExtra(ThemeColorFeedFragment.ARG_SELECT_TAG_TRANSLATION_COLOR, true);
                        startActivity(intent);
                    }
                    dialog.dismiss();
                })
                .show();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 从「主题色彩页」选完标签译文颜色返回时刷新这一行的展示值。
        setTagTranslationColorName();
    }

    private String currentLanguageDisplay() {
        if (ceui.pixiv.i18n.AppLocales.INSTANCE.isFollowingSystem()) {
            return getString(R.string.language_follow_system);
        }
        Locale loc = ceui.pixiv.i18n.AppLocales.INSTANCE.currentLocale();
        return ceui.pixiv.i18n.AppLocales.INSTANCE.displayName(loc.toLanguageTag());
    }
}
