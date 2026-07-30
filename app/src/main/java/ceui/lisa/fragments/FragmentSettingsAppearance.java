package ceui.lisa.fragments;

import static ceui.lisa.helper.ThemeHelper.ThemeType.DARK_MODE;
import static ceui.lisa.helper.ThemeHelper.ThemeType.DEFAULT_MODE;
import static ceui.lisa.helper.ThemeHelper.ThemeType.LIGHT_MODE;

import android.content.DialogInterface;
import android.content.Intent;
import android.text.TextUtils;
import android.widget.CompoundButton;

import androidx.appcompat.app.AppCompatActivity;

import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;

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
import ceui.pixiv.ui.settings.ThemeColorCatalog;
import ceui.pixiv.widget.RecommendCardWidgetProvider;
import ceui.pixiv.widget.RecommendStripWidgetProvider;
import ceui.pixiv.widget.SpotlightWidgetProvider;

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
            new QMUIDialog.CheckableDialogBuilder(mActivity)
                    .setCheckedIndex(index)
                    .setSkinManager(QMUISkinManager.defaultInstance(mContext))
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
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "主题颜色");
            startActivity(intent);
        });

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
            new QMUIDialog.CheckableDialogBuilder(getActivity())
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
            new QMUIDialog.CheckableDialogBuilder(mActivity)
                    .setCheckedIndex(selectIndex)
                    .setSkinManager(QMUISkinManager.defaultInstance(mContext))
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
            new QMUIDialog.CheckableDialogBuilder(mActivity)
                    .setCheckedIndex(currentIndex)
                    .setSkinManager(QMUISkinManager.defaultInstance(mContext))
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
            new QMUIDialog.CheckableDialogBuilder(mActivity)
                    .setCheckedIndex(index)
                    .setSkinManager(QMUISkinManager.defaultInstance(mContext))
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
            new QMUIDialog.CheckableDialogBuilder(mActivity)
                    .setCheckedIndex(index)
                    .setSkinManager(QMUISkinManager.defaultInstance(mContext))
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
            new QMUIDialog.CheckableDialogBuilder(mActivity)
                    .setCheckedIndex(index)
                    .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                    .addItems(INTERVAL_NAMES, (dialog, which) -> {
                        if (which != index) {
                            Shaft.sSettings.setWidgetRefreshIntervalMinutes(INTERVAL_MINUTES[which]);
                            Local.setSettings(Shaft.sSettings);
                            baseBind.widgetRefreshInterval.setText(INTERVAL_NAMES[which]);
                            // UPDATE 策略：已加到桌面的小组件立即按新间隔重排
                            SpotlightWidgetProvider.schedulePeriodic(mContext);
                            RecommendStripWidgetProvider.schedulePeriodic(mContext);
                            RecommendCardWidgetProvider.schedulePeriodic(mContext);
                        }
                        dialog.dismiss();
                    })
                    .show();
        });

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
        final int index = Shaft.sSettings.getThemeIndex();
        baseBind.colorSelect.setText(getString(ThemeColorCatalog.nameResOf(index)));
    }

    private String currentLanguageDisplay() {
        if (ceui.pixiv.i18n.AppLocales.INSTANCE.isFollowingSystem()) {
            return getString(R.string.language_follow_system);
        }
        Locale loc = ceui.pixiv.i18n.AppLocales.INSTANCE.currentLocale();
        return ceui.pixiv.i18n.AppLocales.INSTANCE.displayName(loc.toLanguageTag());
    }
}
