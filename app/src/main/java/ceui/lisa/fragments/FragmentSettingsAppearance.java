package ceui.lisa.fragments;

import static ceui.lisa.helper.ThemeHelper.ThemeType.DARK_MODE;
import static ceui.lisa.helper.ThemeHelper.ThemeType.DEFAULT_MODE;
import static ceui.lisa.helper.ThemeHelper.ThemeType.LIGHT_MODE;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import ceui.pixiv.witstudio.dialog.WitDialog;
import ceui.pixiv.witstudio.dialog.WitDialogAction;
import ceui.pixiv.witstudio.dialog.WitDialogView;
import ceui.pixiv.witstudio.theme.V3Palette;
import ceui.pixiv.witstudio.widget.WitTagStyle;

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
import ceui.pixiv.ui.settings.TagLegibilityPrefs;
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

        // 提升标签原文辨识度：白天 / 黑暗各一条滑条，各自独立、互不影响
        setTagLegibilityBoostName();
        baseBind.tagLegibilityBoostRela.setOnClickListener(v -> showTagLegibilityBoostDialog());

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

    /**
     * 右侧值**同时显示两条**（白天在前、黑暗在后）。只显示当前模式那条的话，翻深浅时文案会变，
     * 用户容易误以为设置丢了；而且弹窗里另一模式的样板会露出差异，与"当前那条是 0"自相矛盾。
     *
     * 两条用**不同动词**：白天是「压暗」、黑暗是「提亮」—— 同一个滑条在两种模式下把原文推向
     * 相反方向（浅色模式压深、深色模式提亮），共用一个动词会让人以为滑条方向反了。
     */
    private void setTagLegibilityBoostName() {
        baseBind.tagLegibilityBoost.setText(getString(
                R.string.tag_legibility_boost_summary,
                tagLegibilityBoostLabel(mActivity, false, TagLegibilityPrefs.light()),
                tagLegibilityBoostLabel(mActivity, true, TagLegibilityPrefs.dark())));
    }

    /**
     * 值文案按模式分流：白天「不压暗 / 压暗 XX%」，黑暗「不提亮 / 提亮 XX%」。
     * 声明成静态是为了让弹窗里那个私有 Builder 也能直接用。
     */
    private static String tagLegibilityBoostLabel(Context context, boolean dark, int value) {
        if (value <= 0) {
            return context.getString(dark
                    ? R.string.tag_legibility_boost_none_dark
                    : R.string.tag_legibility_boost_none_light);
        }
        return context.getString(dark
                ? R.string.tag_legibility_boost_percent_dark
                : R.string.tag_legibility_boost_percent_light, value);
    }

    private void showTagLegibilityBoostDialog() {
        TagLegibilityBoostDialogBuilder builder = new TagLegibilityBoostDialogBuilder(mActivity);
        builder.setTitle(R.string.tag_legibility_boost);
        builder.addAction(R.string.string_cancel, (dialog, which) -> dialog.dismiss());
        builder.addAction(0, R.string.sure, WitDialogAction.ACTION_PROP_POSITIVE, (dialog, which) -> {
            // save() 直接写设备本地的 MMKV，没有单独的「落盘」步骤；随后推给 witstudio。
            // 已经渲染出来的标签胶囊要等视图重建才换色，与「标签译文颜色」同款取舍 ——
            // 不为一次滑条调整重启进程。
            TagLegibilityPrefs.save(builder.lightBoost(), builder.darkBoost());
            TagLegibilityPrefs.applyToWitStudio();
            setTagLegibilityBoostName();
            dialog.dismiss();
        });
        builder.show();
    }

    /**
     * 「提升标签原文辨识度」弹窗：白天 / 黑暗两条滑条，各自带一组实时预览胶囊。
     *
     * 每行固定渲染**两颗**胶囊 —— 左边永远是 k=0 的原始效果，右边随滑条变化，"提升前 / 后"
     * 同时可见，不用来回拖对比。两行各自按自己的模式渲染（用 `V3Palette(primary, isDark,
     * boost)` 显式指定），所以在深色模式下打开弹窗也能看到白天的效果。
     *
     * 拖动只改右侧胶囊的文字色，不重建视图 —— 每 tick 重建会掉帧。
     */
    private static final class TagLegibilityBoostDialogBuilder extends WitDialog.CustomDialogBuilder {

        private SeekBar lightSlider;
        private SeekBar darkSlider;
        private TextView lightValue;
        private TextView darkValue;
        private TextView lightLive;
        private TextView darkLive;
        private int initialLight;
        private int initialDark;

        private TagLegibilityBoostDialogBuilder(Context context) {
            super(context);
        }

        /** 弹窗内容没建起来时（onCreateContent 未跑）回落到进入时的值，避免误写 0。 */
        int lightBoost() {
            return lightSlider != null ? lightSlider.getProgress() : initialLight;
        }

        int darkBoost() {
            return darkSlider != null ? darkSlider.getProgress() : initialDark;
        }

        @Override
        protected View onCreateContent(WitDialog dialog, WitDialogView parent, Context context) {
            View content = LayoutInflater.from(context)
                    .inflate(R.layout.dialog_tag_legibility_boost, parent, false);
            final int primary = V3Palette.from(context).getPrimary();

            // 预览胶囊的底是半透明染色，实际颜色由身后的面决定，所以样板必须铺**真实页面底**
            // （`fragment_center`）—— 铺 cardFill 的话，样板和 App 里坐在页面底上的实物渲染
            // 出来不是一回事，样板就失去参照意义。两行各按自己的模式取日夜资源，浅色模式下也
            // 能看到"黑暗"那行的真实底色。
            final int previewPad = dp(context, 10);
            LinearLayout lightPreview = content.findViewById(R.id.tag_legibility_light_preview);
            LinearLayout darkPreview = content.findViewById(R.id.tag_legibility_dark_preview);
            lightPreview.setBackground(previewSurface(context, pageSurface(context, false)));
            darkPreview.setBackground(previewSurface(context, pageSurface(context, true)));
            lightPreview.setPadding(previewPad, previewPad, previewPad, previewPad);
            darkPreview.setPadding(previewPad, previewPad, previewPad, previewPad);

            lightSlider = content.findViewById(R.id.tag_legibility_light_slider);
            darkSlider = content.findViewById(R.id.tag_legibility_dark_slider);
            lightValue = content.findViewById(R.id.tag_legibility_light_value);
            darkValue = content.findViewById(R.id.tag_legibility_dark_value);
            lightLive = bindPreviewRow(
                    content.findViewById(R.id.tag_legibility_light_preview), context, primary, false);
            darkLive = bindPreviewRow(
                    content.findViewById(R.id.tag_legibility_dark_preview), context, primary, true);

            initialLight = TagLegibilityPrefs.light();
            initialDark = TagLegibilityPrefs.dark();
            lightSlider.setProgress(initialLight);
            darkSlider.setProgress(initialDark);
            renderRow(false, initialLight, lightValue, lightLive, primary);
            renderRow(true, initialDark, darkValue, darkLive, primary);

            lightSlider.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    renderRow(false, progress, lightValue, lightLive, primary);
                }
            });
            darkSlider.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    renderRow(true, progress, darkValue, darkLive, primary);
                }
            });
            return content;
        }

        /** 放两颗胶囊：左边永远 k=0，右边是当前值；返回右边那颗供拖动时改色。 */
        private TextView bindPreviewRow(LinearLayout row, Context context, int primary, boolean isDark) {
            row.removeAllViews();
            row.addView(buildChip(context, new V3Palette(primary, isDark, 0f)));
            TextView live = buildChip(context, new V3Palette(primary, isDark, 0f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(context, 12));
            row.addView(live, lp);
            return live;
        }

        private TextView buildChip(Context context, V3Palette palette) {
            TextView chip = new TextView(context);
            // 与真实胶囊（showHashPrefix）同样带 "# " 前缀。
            chip.setText("# " + context.getString(R.string.tag_legibility_boost_preview_tag));
            chip.setTextSize(13f);
            chip.setPadding(dp(context, 14), dp(context, 7), dp(context, 14), dp(context, 7));
            WitTagStyle.applyText(chip, palette);
            // applyText 设的是 textAccent；真实胶囊（WitTagFlowView.newText）紧接着会用
            // textTag 覆盖它。样板必须走同一条路 —— 否则左样板是 textAccent、右样板是
            // textTag，两颗样板在 k=0 时就长得不一样（实测 #8183E3 vs #9395E7）。
            chip.setTextColor(palette.getTextTag());
            chip.setBackground(WitTagStyle.background(
                    palette, context.getResources().getDisplayMetrics().density));
            return chip;
        }

        /** 滑条、数值文案、右侧胶囊三者同步。 */
        private void renderRow(boolean isDark, int progress, TextView value, TextView live, int primary) {
            Context context = value.getContext();
            value.setText(tagLegibilityBoostLabel(context, isDark, progress));
            live.setTextColor(new V3Palette(primary, isDark, progress / 100f).getTextTag());
        }

        private static int dp(Context context, int value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }

        /**
         * 预览胶囊脚下的那块底 —— 用**真实页面底**（`fragment_center`）而不是 cardFill。
         *
         * 胶囊的染色底是半透明的，实际颜色由身后的面决定：App 里标签坐在页面底（深色
         * `#2A2A2A`）上，比 cardFill（深色 `#1F1F26`）更亮，铺 cardFill 的样板会渲染成另一种
         * 样子。这里按行的模式取对应的日夜资源，样板即所见。
         */
        private static int pageSurface(Context context, boolean isDark) {
            Configuration config = new Configuration(context.getResources().getConfiguration());
            config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                    | (isDark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
            return ContextCompat.getColor(
                    context.createConfigurationContext(config), R.color.fragment_center);
        }

        /** 预览胶囊脚下的那块底，圆角与卡片一致。 */
        private static GradientDrawable previewSurface(Context context, int color) {
            GradientDrawable surface = new GradientDrawable();
            surface.setShape(GradientDrawable.RECTANGLE);
            surface.setCornerRadius(dp(context, 12));
            surface.setColor(color);
            return surface;
        }

        /** 只关心 onProgressChanged，起止回调留空。 */
        private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        }
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
