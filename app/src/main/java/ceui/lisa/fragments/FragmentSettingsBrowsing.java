package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.content.Intent;
import android.text.InputType;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;

import ceui.pixiv.witstudio.dialog.WitDialog;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.FragmentSettingsBrowsingBinding;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.PixivSearchParamUtil;
import ceui.pixiv.ui.search.SortType;
import ceui.pixiv.ui.settings.AiBlockExemptAuthorsSheet;
import ceui.loxia.CloudHistoryConsent;
import ceui.pixiv.session.SessionManager;

/** 设置 · 浏览与搜索 */
public class FragmentSettingsBrowsing extends SettingsPageFragment<FragmentSettingsBrowsingBinding> {

    /**
     * 打开「小说超长标签屏蔽」时预填的建议值。日文正圈 tag 本来就能到十几字
     * （如「ぼくたちは勉強ができない」12 字），阈值压太低会误伤正常作品，取 16。
     */
    private static final int NOVEL_FILTER_SUGGESTED_MAX_TAG_NAME_LENGTH = 16;

    /** 打开「小说正文字数下限」时预填的建议值——广告位小说普遍只有几十到几百字。 */
    private static final int NOVEL_FILTER_SUGGESTED_MIN_TEXT_LENGTH = 500;

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
        renderAiBlockSubOptions();
        baseBind.deleteAiIllust.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setDeleteAIIllust(isChecked);
                Common.showToast(getString(R.string.string_428), 2);
                Local.setSettings(Shaft.sSettings);
                renderAiBlockSubOptions();
            }
        });
        baseBind.deleteAiIllustRela.setOnClickListener(v -> baseBind.deleteAiIllust.performClick());
        baseBind.aiBlockStrengthRela.setOnClickListener(v -> showAiBlockStrengthPicker());
        baseBind.aiBlockExemptRela.setOnClickListener(v -> showAiBlockExemptAuthorEditor());
        getChildFragmentManager().setFragmentResultListener(
                AiBlockExemptAuthorsSheet.REQUEST_KEY,
                this,
                (requestKey, result) -> renderAiBlockSubOptions());

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

        // 小说列表自动屏蔽：正文字数下限/上限 + 超长标签名（issue #743）。
        // 三项默认 0（关闭）——升级上来的用户列表不会无声变短；点开时按建议值预填。
        bindNovelFilterRow(
                baseBind.novelFilterMinTextLengthRela,
                baseBind.novelFilterMinTextLength,
                R.string.novel_filter_min_text_length,
                NOVEL_FILTER_SUGGESTED_MIN_TEXT_LENGTH,
                () -> Shaft.sSettings.getNovelFilterMinTextLength(),
                value -> Shaft.sSettings.setNovelFilterMinTextLength(value),
                value -> {
                    int max = Shaft.sSettings.getNovelFilterMaxTextLength();
                    return (value > 0 && max > 0 && value > max)
                            ? getString(R.string.novel_filter_range_invalid) : null;
                });
        bindNovelFilterRow(
                baseBind.novelFilterMaxTextLengthRela,
                baseBind.novelFilterMaxTextLength,
                R.string.novel_filter_max_text_length,
                0,
                () -> Shaft.sSettings.getNovelFilterMaxTextLength(),
                value -> Shaft.sSettings.setNovelFilterMaxTextLength(value),
                value -> {
                    int min = Shaft.sSettings.getNovelFilterMinTextLength();
                    return (value > 0 && min > 0 && value < min)
                            ? getString(R.string.novel_filter_range_invalid) : null;
                });
        bindNovelFilterRow(
                baseBind.novelFilterMaxTagNameLengthRela,
                baseBind.novelFilterMaxTagNameLength,
                R.string.novel_filter_max_tag_name_length,
                NOVEL_FILTER_SUGGESTED_MAX_TAG_NAME_LENGTH,
                () -> Shaft.sSettings.getNovelFilterMaxTagNameLength(),
                value -> Shaft.sSettings.setNovelFilterMaxTagNameLength(value),
                null);

        // 搜索结果收藏量筛选
        final String searchFilter = Shaft.sSettings.getSearchFilter();
        baseBind.searchFilter.setText(PixivSearchParamUtil.getSizeName(searchFilter));
        baseBind.searchFilterRela.setOnClickListener(v ->
                new WitDialog.CheckableDialogBuilder(mContext)
                        .setCheckedIndex(PixivSearchParamUtil.getSizeIndex(Shaft.sSettings.getSearchFilter()))
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

        // 默认按热度排序搜索 —— 下面「搜索结果排序方式」的开关视图，不另存一份状态：
        // 开 = popular_desc，关 = date_desc。Free 用户的诉求是「平时别替我花借号额度，
        // 需要时我自己在筛选里选热度」，那是一下开关的事；选了热度之后额度不够会自动
        // 降到热度预览（SearchIllustRepo / SearchNovelRepo 里的 rate_limited 回落），这里不管。
        // 两行互相回写：翻开关要刷新下面那行的值，在下面选了档也要刷新开关。
        bindSearchPopularDefaultSwitch();
        baseBind.searchPopularDefault.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (syncingPopularSwitch) return; // 代码回写（下面那行选完刷新）不重复落盘、不弹 toast
            Shaft.sSettings.setSearchDefaultSortType(isChecked
                    ? PixivSearchParamUtil.POPULAR_SORT_VALUE
                    : SortType.DATE_DESC);
            Common.showToast(getString(R.string.string_428), 2);
            Local.setSettings(Shaft.sSettings);
            baseBind.searchDefaultSortType.setText(
                    PixivSearchParamUtil.getSortTypeName(Shaft.sSettings.getSearchDefaultSortType()));
        });
        baseBind.searchPopularDefaultRela.setOnClickListener(v ->
                baseBind.searchPopularDefault.performClick());

        // 搜索结果默认排序方式
        final String searchDefaultSortType = Shaft.sSettings.getSearchDefaultSortType();
        baseBind.searchDefaultSortType.setText(PixivSearchParamUtil.getSortTypeName(searchDefaultSortType));
        baseBind.searchDefaultSortTypeRela.setOnClickListener(v ->
                new WitDialog.CheckableDialogBuilder(mContext)
                        .setCheckedIndex(PixivSearchParamUtil.getSortTypeIndex(Shaft.sSettings.getSearchDefaultSortType()))
                        .addItems(PixivSearchParamUtil.SORT_TYPE_NAME, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Shaft.sSettings.setSearchDefaultSortType(PixivSearchParamUtil.SORT_TYPE_VALUE[which]);
                                Common.showToast(getString(R.string.string_428), 2);
                                Local.setSettings(Shaft.sSettings);
                                baseBind.searchDefaultSortType.setText(PixivSearchParamUtil.SORT_TYPE_NAME[which]);
                                bindSearchPopularDefaultSwitch();
                                dialog.dismiss();
                            }
                        })
                        .create()
                        .show());

        // 搜索结果页退出二次确认（issue #939），默认关闭
        baseBind.searchExitConfirm.setChecked(Shaft.sSettings.isSearchExitConfirm());
        baseBind.searchExitConfirm.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setSearchExitConfirm(isChecked);
                Common.showToast(getString(R.string.string_428), 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.searchExitConfirmRela.setOnClickListener(v ->
                baseBind.searchExitConfirm.performClick());

        // 搜索结果页 / 画师主页列表右下角「回顶」悬浮钮（issue #1040），默认关闭
        baseBind.feedBackToTopFab.setChecked(Shaft.sSettings.isFeedBackToTopFab());
        baseBind.feedBackToTopFab.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setFeedBackToTopFab(isChecked);
                Common.showToast(getString(R.string.string_428), 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.feedBackToTopFabRela.setOnClickListener(v ->
                baseBind.feedBackToTopFab.performClick());

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

    private void renderAiBlockSubOptions() {
        boolean enabled = Shaft.sSettings.isDeleteAIIllust();
        baseBind.aiBlockStrengthRela.setVisibility(enabled ? View.VISIBLE : View.GONE);
        baseBind.aiBlockExemptRela.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (!enabled) return;
        baseBind.aiBlockStrength.setText(Shaft.sSettings.getAiBlockStrength() == 0
                ? getString(R.string.ai_block_strength_hide)
                : getString(R.string.ai_block_strength_blur));
        int count = Shaft.sSettings.getAiBlockExemptAuthorIds().size();
        baseBind.aiBlockExempt.setText(count == 0
                ? getString(R.string.ai_block_exempt_none)
                : getString(R.string.ai_block_exempt_count, count));
    }

    private void showAiBlockStrengthPicker() {
        WitDialog.CheckableDialogBuilder builder = new WitDialog.CheckableDialogBuilder(mContext);
        builder.setTitle(getString(R.string.ai_block_strength))
                .addItems(new CharSequence[]{
                        getString(R.string.ai_block_strength_hide),
                        getString(R.string.ai_block_strength_blur),
                }, null)
                .setCheckedIndex(Shaft.sSettings.getAiBlockStrength())
                .addAction(android.R.string.cancel, (dialog, index) -> dialog.dismiss())
                .addAction(android.R.string.ok, (dialog, index) -> {
                    Shaft.sSettings.setAiBlockStrength(builder.getCheckedIndex());
                    Local.setSettings(Shaft.sSettings);
                    renderAiBlockSubOptions();
                    dialog.dismiss();
                })
                .show();
    }

    private void showAiBlockExemptAuthorEditor() {
        new AiBlockExemptAuthorsSheet().show(getChildFragmentManager(), "ai_block_exempt_authors");
    }

    /**
     * 小说自动屏蔽阈值行：点开数字输入框，0 = 关闭（值列显示「不限」）。
     * 三行只差 title / 建议值 / getter / setter / 校验，共用这一份。
     *
     * [validator] 返回非 null 的错误文案时 toast 并留在对话框里不落盘——用来挡「下限 > 上限」
     * 这种不可能区间（那会把每个小说列表都清空，而用户完全看不出是自己设出来的）。
     */
    private void bindNovelFilterRow(View row, TextView valueText, int titleRes,
                                    int suggested, IntSupplier getter, IntConsumer setter,
                                    IntFunction<String> validator) {
        valueText.setText(formatNovelFilterValue(getter.getAsInt()));
        row.setOnClickListener(v -> {
            WitDialog.EditTextDialogBuilder builder =
                    new WitDialog.EditTextDialogBuilder(mContext);
            int current = getter.getAsInt();
            // 当前关着就把建议值填进去，用户直接点确定即可开启（仍然可以清空/填 0 关掉）。
            builder.setTitle(getString(titleRes))
                    .setPlaceholder(getString(R.string.novel_filter_input_hint))
                    .setDefaultText(current > 0 ? String.valueOf(current)
                            : (suggested > 0 ? String.valueOf(suggested) : ""))
                    .setInputType(InputType.TYPE_CLASS_NUMBER)
                    .addAction(android.R.string.cancel, (dialog, index) -> dialog.dismiss())
                    .addAction(android.R.string.ok, (dialog, index) -> {
                        CharSequence entered = builder.getEditText().getText();
                        // 空输入 = 0 = 关闭；填不下的超大数同样归零（值列会显示「不限」，看得见），
                        // 不让脏值落盘。负数也要归零——TYPE_CLASS_NUMBER 挡不住第三方输入法，
                        // 搜狗在数字键盘上照样给 `-` 键（真机实测），所以 Math.max 这道不能省。
                        int parsed = 0;
                        try {
                            parsed = Integer.parseInt(entered == null ? "" : entered.toString().trim());
                        } catch (NumberFormatException ignored) {
                        }
                        parsed = Math.max(parsed, 0);
                        String error = validator == null ? null : validator.apply(parsed);
                        if (error != null) {
                            Common.showToast(error, 3);
                            return;
                        }
                        setter.accept(parsed);
                        Local.setSettings(Shaft.sSettings);
                        valueText.setText(formatNovelFilterValue(parsed));
                        Common.showToast(getString(R.string.string_428), 2);
                        dialog.dismiss();
                    })
                    .create()
                    .show();
        });
    }

    private String formatNovelFilterValue(int value) {
        return value > 0
                ? getString(R.string.novel_filter_chars, value)
                : getString(R.string.novel_filter_unlimited);
    }

    /** 代码回写开关期间为 true，让 OnCheckedChangeListener 认出这不是用户的手。 */
    private boolean syncingPopularSwitch = false;

    /**
     * 开关 = 「默认排序是不是热度」。只刷显示，不落盘。
     *
     * 先过 [SortType.sanitize]：老配置里可能还存着已下线的「机内自带热度排序」，运行时它就是
     * 按热度（搜索和下面那行的显示都这么认），开关不能独自显示成关。
     */
    private void bindSearchPopularDefaultSwitch() {
        // 男/女性向人气与总热度同属「花借号额度的热度类」，开关一并算开；
        // 用户翻关再翻开只会写回 popular_desc（开关是二值快捷方式，丢失方向档可接受）。
        String sanitized = SortType.INSTANCE.sanitize(Shaft.sSettings.getSearchDefaultSortType());
        boolean popular = PixivSearchParamUtil.POPULAR_SORT_VALUE.equals(sanitized)
                || SortType.POPULAR_MALE_DESC.equals(sanitized)
                || SortType.POPULAR_FEMALE_DESC.equals(sanitized);
        syncingPopularSwitch = true;
        baseBind.searchPopularDefault.setChecked(popular);
        syncingPopularSwitch = false;
    }
}
