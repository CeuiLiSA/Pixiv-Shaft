package ceui.lisa.activities;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.URLUtil;
import android.widget.EditText;
import android.widget.TextView;

import com.google.android.material.appbar.AppBarLayout;
import com.mxn.soul.flowingdrawer_core.ElasticDrawer;
import ceui.pixiv.witstudio.dialog.WitDialog;
import ceui.pixiv.witstudio.dialog.WitDialogAction;
import ceui.pixiv.witstudio.dialog.WitTipDialog;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewpager.widget.ViewPager;
import ceui.lisa.R;
import ceui.lisa.adapters.SearchHintAdapter;
import ceui.lisa.databinding.FragmentNewSearchBinding;
import ceui.pixiv.ui.search.SearchIllustFeedFragment;
import ceui.pixiv.ui.search.SearchNovelFeedFragment;
import ceui.pixiv.ui.search.SearchRiskPolicy;
import ceui.pixiv.ui.search.SearchUserFeedFragment;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.utils.PixivSearchParamUtil;
import ceui.lisa.utils.SearchTypeUtil;
import ceui.lisa.viewmodel.SearchModel;
import ceui.pixiv.api.model.ObjectType;
import ceui.pixiv.ui.search.SearchHintViewModel;
import ceui.pixiv.ui.search.v3.SearchFilterV3BottomSheet;
import ceui.pixiv.ui.search.v3.SearchFilterV3LegacyBridge;
import ceui.pixiv.widgets.FeedBackToTopFab;
import ceui.lisa.core.JavaAsync;

public class SearchActivity extends BaseActivity<FragmentNewSearchBinding> {

    private final Fragment[] allPages = new Fragment[]{null, null, null};
    private String keyWord = "";
    private SearchModel searchModel;
    private int index = 0;
    private int mPosition = 0;
    private long mExitTime;
    private final java.util.List<String> committedTags = new java.util.ArrayList<>();
    private SearchHintViewModel hintViewModel;
    // 动画的目标状态；淡出期间 View 仍是 VISIBLE，不能用它判断是否需要重新显示。
    private boolean mHintListShown;
    /** Toolbar 原始的 layout_scrollFlags（补全浮层钉住搜索栏时要还原）。 */
    private int mToolbarScrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL
            | AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS;

    @Override
    protected void initBundle(Bundle bundle) {
        keyWord = bundle.getString(Params.KEY_WORD);
        index = bundle.getInt(Params.INDEX);
    }

    /**
     * ViewModel 创建/播种放这里而不是 initBundle：BaseActivity 只在 intent 带 extras 时才调
     * initBundle，但 initView/initData 里的 TextWatcher、筛选菜单、翻页监听器无条件挂载。
     * 一旦 Activity 被无 extras 地重建（系统/崩溃重启重投裸 intent），searchModel 就还是 null，
     * 首个按键 afterTextChanged → pushKeywordFromChipsAndInput 直接 NPE。initModel 无条件调用，
     * 保证这两个核心 ViewModel 永远先于任何监听器就绪；keyWord/index 缺省时走字段默认值("",0)。
     */
    @Override
    public void initModel() {
        // AES/GCM 提供器与词库只在搜索页用到：进页即在后台预热，避免首次键入时冷解密卡主线程。
        JavaAsync.fireAndForget(SearchRiskPolicy::warmUp);
        searchModel = new ViewModelProvider(this).get(SearchModel.class);
        hintViewModel = new ViewModelProvider(this).get(SearchHintViewModel.class);
        searchModel.getKeyword().setValue(keyWord);
        searchModel.getIsNovel().setValue(index == 1);

        // 首搜写历史：无论从哪儿带关键字进来（输入框搜索/提示词/热标签/详情页标签/发现/深链…），
        // 都在这唯一入口收口一次。首搜走 ensureLoaded 不发 nowGo，所以和下面的重搜 observer 不重复。
        recordKeywordHistory(keyWord);
    }

    /**
     * 关键字搜索写历史的唯一收口：首搜（{@link #initModel()} 拿到的 keyWord）与重搜（nowGo）都走这里，
     * 由 {@link PixivOperate#insertSearchHistory} 按 id（keyword.hashCode()+type）去重——同词只留一条。
     * 原先写入寄生在 {@code SearchIllustRepo.initApi}，会被 trending_builtin 提前 return 跳过、
     * 且只有插画 tab 触发；上移到这里后所有入口、所有 tab、所有排序都稳定写一条。
     */
    private void recordKeywordHistory(String keyword) {
        if (keyword == null) return;
        final String trimmed = keyword.trim();
        if (trimmed.isEmpty()) return;
        // 写库甩到 IO 线程：insertSearchHistory 是主键读 + 单条插入，search_table 极小虽轻，
        // 政策判断也留在这里，首次进搜索页即使后台预热尚未完成也不阻塞主线程。
        // initModel / nowGo 都跑在主线程，统一挪开不碰主线程 Room（对齐本仓
        // insertIllustViewHistory 等既有做法）。fire-and-forget，去重靠 id REPLACE，乱序无碍；
        // 只捕获 String + 静态方法，不持有 Activity，无泄漏。
        JavaAsync.fireAndForget(() -> {
            // 被拦截的查询不持久化；结果页仍保留当前 chip 来解释为什么未显示。
            if (!SearchRiskPolicy.shouldWithhold(trimmed)) {
                PixivOperate.insertSearchHistory(trimmed, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD);
            }
        });
    }

    @Override
    protected int initLayout() {
        return R.layout.fragment_new_search;
    }

    @Override
    protected void initView() {
        // 列表右下角「回顶」悬浮钮（issue #1040，设置里默认关）：在建 pager 之前装，
        // 三个 tab 的 feeds 列表建好视图时自动挂上
        FeedBackToTopFab.installForHost(this, baseBind.appBar);
        final String[] TITLES = new String[]{
                getString(R.string.string_136),
                getString(R.string.string_138),
                getString(R.string.string_432)
        };
        // Seed committed chips from the incoming keyword (space-separated), clear
        // the input itself — the chip row represents the active query.
        if (!TextUtils.isEmpty(keyWord)) {
            for (String part : keyWord.trim().split("\\s+")) {
                if (!TextUtils.isEmpty(part)) committedTags.add(part);
            }
        }
        baseBind.searchTagsFlow.setShowRemoveIcon(true);
        refreshChipsUI();
        // 点 × 先确认，确认删除后才用剩余标签重搜。
        baseBind.searchTagsFlow.setOnTagClick(name -> {
            confirmRemoveTag(name);
            return kotlin.Unit.INSTANCE;
        });
        // 点正文（非 × 区）= 还原到输入框编辑，不立刻重搜——编辑是准备动作，回车才搜。
        baseBind.searchTagsFlow.setOnTagBodyClick(name -> {
            editTagFromChip(name);
            return kotlin.Unit.INSTANCE;
        });
        baseBind.searchTagsFlow.setOnTagLongClick(name -> {
            showTagActionMenu(name);
            return kotlin.Unit.INSTANCE;
        });
        // 三个 tab 均已迁 feeds（autoLoad=false 懒加载）。必须用 BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT，
        // 否则离屏 tab 也到 RESUMED → onResume ensureLoaded → 开屏就替用户把三个 tab 各搜一次（旧 legacy
        // 靠 setUserVisibleHint 懒加载只搜可见 tab）。改 behavior 1 后只有可见 tab 开搜，其余进 tab 才搜。
        baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager(),
                FragmentPagerAdapter.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
            @NonNull
            @Override
            public Fragment getItem(int position) {
                if (allPages[position] == null) {
                    if (position == 0) {
                        allPages[position] = SearchIllustFeedFragment.newInstance();
                    } else if(position == 1){
                        allPages[position] = SearchNovelFeedFragment.newInstance();
                    } else if(position == 2){
                        allPages[position] = SearchUserFeedFragment.newInstance();
                    }
                }

                return allPages[position];
            }

            @Override
            public int getCount() {
                return TITLES.length;
            }

            @Nullable
            @Override
            public CharSequence getPageTitle(int position) {
                return TITLES[position];
            }
        });
        baseBind.viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener(){
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) { }

            @Override
            public void onPageSelected(int position) {
                hintViewModel.hideHints();
                mPosition = position;
                // V3 filter 不再用抽屉，全程禁掉抽屉触摸；SearchModel.isNovel 保持同步给
                // V3 filter sheet 判当前 tab 类型（feeds 版搜索 fragment 已改看 searchType gate，不读 isNovel）。
                MutableLiveData<Boolean> isNovel = searchModel.getIsNovel();
                if (isNovel.getValue() != null) {
                    if ((position == 0) && isNovel.getValue()) {
                        isNovel.setValue(false);
                    } else if (position == 1 && !isNovel.getValue()) {
                        isNovel.setValue(true);
                    }
                }
            }

            @Override
            public void onPageScrollStateChanged(int state) {
            }
        });
        baseBind.viewPager.setOffscreenPageLimit(2);
        baseBind.tabLayout.setupWithViewPager(baseBind.viewPager);
        // drawer 触摸响应在 initData 末尾跟着 bridge 一并关掉——不在这里重复
        if (index != 0) {
            baseBind.viewPager.setCurrentItem(index);
        }
    }

    @Override
    protected void initData() {
        baseBind.toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mActivity.finish();
            }
        });
        baseBind.toolbar.inflateMenu(R.menu.illust_filter);
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_filter) {
                    Common.hideKeyboard(mActivity);
                    if (mPosition == 0 || mPosition == 1) {
                        // V3 filter sheet 替代老抽屉里的 FragmentFilter；状态由
                        // SearchFilterV3LegacyBridge 翻译回 SearchModel。
                        String objectType = (mPosition == 1) ? ObjectType.NOVEL : ObjectType.ILLUST;
                        SearchFilterV3BottomSheet
                                .newInstance(objectType, true)
                                .show(getSupportFragmentManager(), "SearchFilterV3LegacySheet");
                    } else {
                        Common.showToast(getString(R.string.string_435));
                    }
                    return true;
                }
                return false;
            }
        });
        baseBind.searchTagsFlow.getEditor().addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void afterTextChanged(Editable editable) {
                // Space after content commits; space on an otherwise blank input
                // is dropped so the user can't spam leading/consecutive spaces.
                String current = editable.toString();
                if (current.length() > 0 && current.charAt(current.length() - 1) == ' ') {
                    String tag = current.substring(0, current.length() - 1).trim();
                    if (tag.isEmpty()) {
                        baseBind.searchTagsFlow.getEditor().setText("");
                    } else {
                        commitTagFromInput(tag);
                    }
                    hintViewModel.hideHints();
                    return;
                }
                pushKeywordFromChipsAndInput();

                // Feed autocomplete for the current word being typed
                String typed = current.trim();
                if (!typed.isEmpty() && !Common.isNumeric(typed)) {
                    hintViewModel.onTextChanged(typed);
                } else {
                    hintViewModel.clearHints();
                }
            }
        });
        baseBind.searchTagsFlow.getEditor().setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                String trimmedKeyword = baseBind.searchTagsFlow.getEditor().getText().toString().trim();
                if (TextUtils.isEmpty(trimmedKeyword) && TextUtils.isEmpty(searchModel.getStarSize().getValue())) {
                    if (!committedTags.isEmpty()) {
                        // Enter with empty input + existing chips → just fire the search.
                        searchModel.getKeyword().setValue(joinedChips());
                        searchModel.getNowGo().setValue("search_now");
                        Common.hideKeyboard(mActivity);
                        return true;
                    }
                    Common.showToast(getString(R.string.string_139));
                    return false;
                }

                // 高风险纯数字词不能落入下面的作品 ID / 用户 ID 直达分支。
                // 先按完整查询（已有 chips + 本次输入）做政策判断，命中就像普通
                // 关键词一样进入结果态，由三个 feed 显示统一提示。
                String existingKeyword = joinedChips();
                String policyQuery = TextUtils.isEmpty(existingKeyword)
                        ? trimmedKeyword
                        : TextUtils.isEmpty(trimmedKeyword)
                            ? existingKeyword
                            : existingKeyword + " " + trimmedKeyword;
                if (SearchRiskPolicy.shouldWithhold(policyQuery)) {
                    if (!TextUtils.isEmpty(trimmedKeyword) && !committedTags.contains(trimmedKeyword)) {
                        committedTags.add(trimmedKeyword);
                        refreshChipsUI();
                    }
                    baseBind.searchTagsFlow.getEditor().setText("");
                    searchModel.getKeyword().setValue(joinedChips());
                    searchModel.getNowGo().setValue("search_now");
                    hintViewModel.hideHints();
                    Common.hideKeyboard(mActivity);
                    return true;
                }

                if (URLUtil.isValidUrl(trimmedKeyword)) {
                    try {
                        PixivOperate.insertSearchHistory(trimmedKeyword, SearchTypeUtil.SEARCH_TYPE_DB_URL);
                        Intent intent = new Intent(mContext, OutWakeActivity.class);
                        intent.setData(Uri.parse(trimmedKeyword));
                        startActivity(intent);
                        mActivity.finish();
                    } catch (Exception e) {
                        Common.showToast(e.toString());
                        e.printStackTrace();
                    }
                }
                else if(Common.isNumeric(trimmedKeyword)){
                    WitTipDialog tipDialog = new WitTipDialog.Builder(mContext)
                            .setTipWord(getString(R.string.string_429))
                            .create();
                    tipDialog.show();
                    //先假定为作品id
                    PixivOperate.getIllustByID(tryParseId(trimmedKeyword), mContext, new Callback<Void>() {
                        @Override
                        public void doSomething(Void t) {
                            PixivOperate.insertSearchHistory(trimmedKeyword, SearchTypeUtil.SEARCH_TYPE_DB_ILLUSTSID);
                            tipDialog.dismiss();
                            mActivity.finish();
                        }
                    }, new Callback<Void>() {
                        @Override
                        public void doSomething(Void t) {
                            tipDialog.dismiss();
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            PixivOperate.insertSearchHistory(trimmedKeyword, SearchTypeUtil.SEARCH_TYPE_DB_USERID);
                            Intent intent = new Intent(mContext, UActivity.class);
                            intent.putExtra(Params.USER_ID, Common.safeUserId(trimmedKeyword));
                            startActivity(intent);
                            mActivity.finish();
                        }
                    });
                }
                else{
                    // Commit the freshly-typed keyword as a chip, clear the input,
                    // re-join all chips into the search keyword, then fire search.
                    if (!committedTags.contains(trimmedKeyword)) {
                        committedTags.add(trimmedKeyword);
                        refreshChipsUI();
                    }
                    baseBind.searchTagsFlow.getEditor().setText("");
                    searchModel.getKeyword().setValue(joinedChips());
                    searchModel.getNowGo().setValue("search_now");
                    Common.hideKeyboard(mActivity);
                }

                hintViewModel.hideHints();
                return true;
            }
        });

        // 记下 Toolbar 原始的 scrollFlags —— 补全浮层可见时要临时摘掉，见 setSearchBarCollapsible
        ViewGroup.LayoutParams toolbarLp = baseBind.toolbar.getLayoutParams();
        if (toolbarLp instanceof AppBarLayout.LayoutParams) {
            mToolbarScrollFlags = ((AppBarLayout.LayoutParams) toolbarLp).getScrollFlags();
        }

        // ── Autocomplete hint list ──────────────────────────────────────
        // Position hint list right below the toolbar (above tabs + content)
        baseBind.toolbar.post(() -> {
            androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams lp =
                    (androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) baseBind.hintList.getLayoutParams();
            lp.topMargin = baseBind.toolbar.getBottom();
            baseBind.hintList.setLayoutParams(lp);
        });
        baseBind.hintList.setLayoutManager(new LinearLayoutManager(mContext));
        hintViewModel.getHints().observe(this, hints -> {
            if (hints == null || hints.isEmpty()) return;
            String keyword = hintViewModel.getCurrentKeyword().getValue();
            SearchHintAdapter adapter = new SearchHintAdapter(hints, mContext, keyword != null ? keyword : "");
            adapter.setOnItemClickListener((v, position, viewType) -> {
                hintViewModel.hideHints();
                String tag = hints.get(position).getTag();
                if (!committedTags.contains(tag)) {
                    committedTags.add(tag);
                    refreshChipsUI();
                }
                baseBind.searchTagsFlow.getEditor().setText("");
                pushKeywordFromChipsAndInput();
                triggerSearchIfNotEmpty();
                Common.hideKeyboard(mActivity);
            });
            adapter.setOnItemLongClickListener((v, position, viewType) -> {
                hintViewModel.hideHints();
                String tagName = hints.get(position).getTag();
                baseBind.searchTagsFlow.getEditor().setText(tagName);
                baseBind.searchTagsFlow.getEditor().setSelection(tagName.length());
            });
            baseBind.hintList.setAdapter(adapter);
        });
        hintViewModel.getHintsVisible().observe(this, visible -> {
            animateHintList(visible != null && visible);
        });

        // 重搜写历史：results 页每次真正发起搜索（软键盘回车 / 提交 chip / 点提示词 / 筛选应用）
        // 都会给 nowGo 一脚，这里统一收口写一条（去重）。首搜不发 nowGo（走 ensureLoaded），
        // 已在 initModel 写过，故两者不重复。读 keyword 而非 chip：nowGo 一定在 keyword 落定后才发。
        searchModel.getNowGo().observe(this, ignored ->
                recordKeywordHistory(searchModel.getKeyword().getValue()));

        // V3 filter sheet 替代老 FragmentFilter 抽屉。bridge 启动后会持续把
        // V3 SearchViewModel 的 illustFilter / novelFilter 翻译到 SearchModel，
        // 并在 sheet 触发搜索事件时 setNowGo("search_now") 让老 fragment 自动刷新。
        SearchFilterV3LegacyBridge.INSTANCE.install(this, searchModel);
        // 关掉抽屉的触摸响应——抽屉里没东西了，避免侧边盲区误触。
        baseBind.drawerlayout.setTouchMode(ElasticDrawer.TOUCH_MODE_NONE);

        // 搜索结果退出二次确认（issue #939，默认关闭）：只拦系统返回（手势/按键）——
        // 长滑之后误触退出就是从这条路来的；工具栏返回箭头是明确点击，不拦。
        // 交互对齐 MainActivity.exit()：2 秒内按两次返回才退出，第一次只 toast 提示。
        //
        // callback 只在「开关打开 && 还没按过第一次」时 enabled：常开会让系统放弃预测式返回
        // 动画（有 app 回调注册就不播）。第一次按下 toast 后把自己关掉 2 秒，第二次返回直接
        // 交给系统 → 跟手的预测式退出动画照常播；2 秒后再重新接管。开关关闭时整段不拦。
        // 开关状态在 onResume 重读，从设置页改完回来立即生效，无需重建 Activity。
        // 不带 owner 注册（与 TemplateActivity 同理）：垫在所有 Fragment callback 之下，
        // 避免 Activity ON_START 晚于子 Fragment 导致的「兜底压在上面」。
        mExitConfirmCallback = new androidx.activity.OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                Common.showToast(getString(R.string.double_click_finish));
                mExitTime = System.currentTimeMillis();
                refreshExitConfirmCallback();
                baseBind.getRoot().postDelayed(SearchActivity.this::refreshExitConfirmCallback, EXIT_CONFIRM_WINDOW_MS + 100);
            }
        };
        getOnBackPressedDispatcher().addCallback(mExitConfirmCallback);
        refreshExitConfirmCallback();
    }

    private static final long EXIT_CONFIRM_WINDOW_MS = 2000;
    private androidx.activity.OnBackPressedCallback mExitConfirmCallback;

    private void refreshExitConfirmCallback() {
        if (mExitConfirmCallback == null) return;
        boolean armed = System.currentTimeMillis() - mExitTime <= EXIT_CONFIRM_WINDOW_MS;
        mExitConfirmCallback.setEnabled(Shaft.sSettings.isSearchExitConfirm() && !armed);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshExitConfirmCallback();
    }

    /**
     * Commit the typed text as a new chip (dedupe, clear input, sync keyword).
     * Space-triggered commits do NOT auto-search — Enter is still the "go" key.
     */
    private void commitTagFromInput(String tag) {
        if (!committedTags.contains(tag)) {
            committedTags.add(tag);
            refreshChipsUI();
        }
        baseBind.searchTagsFlow.getEditor().setText("");
        pushKeywordFromChipsAndInput();
    }

    private void refreshChipsUI() {
        baseBind.searchTagsFlow.setTagNames(new java.util.ArrayList<>(committedTags));
    }

    private String joinedChips() {
        return android.text.TextUtils.join(" ", committedTags);
    }

    private void pushKeywordFromChipsAndInput() {
        String typed = baseBind.searchTagsFlow.getEditor().getText().toString();
        String joined = joinedChips();
        String combined;
        if (TextUtils.isEmpty(joined)) {
            combined = typed;
        } else if (TextUtils.isEmpty(typed.trim())) {
            combined = joined;
        } else {
            combined = joined + " " + typed;
        }
        searchModel.getKeyword().setValue(combined);
    }

    private void triggerSearchIfNotEmpty() {
        if (!committedTags.isEmpty()) {
            searchModel.getNowGo().setValue("search_now");
        }
    }

    private void confirmRemoveTag(String name) {
        new WitDialog.MessageDialogBuilder(mContext)
                .setTitle(R.string.action_delete)
                .setMessage(getString(R.string.search_tag_delete_confirm, name))
                .addAction(R.string.string_142, (dialog, which) -> dialog.dismiss())
                .addAction(0, R.string.action_delete, WitDialogAction.ACTION_PROP_NEGATIVE, (dialog, which) -> {
                    dialog.dismiss();
                    if (committedTags.remove(name)) {
                        refreshChipsUI();
                        pushKeywordFromChipsAndInput();
                        triggerSearchIfNotEmpty();
                    }
                })
                .show();
    }

    private void animateHintList(boolean show) {
        if (mHintListShown == show) return;
        mHintListShown = show;
        // 取消旧动画及其结束回调，避免快速切换显隐时旧回调隐藏新提示或释放搜索栏。
        baseBind.hintList.animate().cancel();
        if (show) {
            // 浮层马上要露出来：把搜索栏钉住。浮层的 topMargin 是 toolbar.getBottom() 的静态
            // 快照，搜索栏一被滚动收起，锚点就落空、浮层与搜索栏脱钩。
            setSearchBarCollapsible(false);
            if (baseBind.hintList.getVisibility() != View.VISIBLE) {
                baseBind.hintList.setAlpha(0f);
                baseBind.hintList.setTranslationY(-24f);
            }
            baseBind.hintList.setVisibility(View.VISIBLE);
            baseBind.hintList.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        } else {
            // 淡出期间浮层仍可见，直到动画结束才释放锚点。
            baseBind.hintList.animate()
                    .alpha(0f)
                    .translationY(-16f)
                    .setDuration(160)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        if (mHintListShown) return;
                        baseBind.hintList.setVisibility(View.GONE);
                        baseBind.hintList.setTranslationY(0f);
                        setSearchBarCollapsible(true);
                    })
                    .start();
        }
    }

    /**
     * 把一个 chip 还原回输入框：移除该 chip、文本回填、聚焦并唤起键盘，最后同步 keyword。
     *
     * 两个入口共用：胶囊正文点击（initView 里的 onTagBodyClick）与长按菜单的「编辑」。
     * 刻意**不**发 nowGo —— 编辑是准备动作，等用户改完回车再搜。
     */
    private void editTagFromChip(String name) {
        commitPendingInputAsChip();
        committedTags.remove(name);
        refreshChipsUI();
        EditText ed = baseBind.searchTagsFlow.getEditor();
        if (ed != null) {
            ed.setText(name);
            ed.setSelection(name.length());
            ed.requestFocus();
            InputMethodManager imm = (InputMethodManager) mContext
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(ed, InputMethodManager.SHOW_IMPLICIT);
            }
        }
        pushKeywordFromChipsAndInput();
    }

    /**
     * 把输入框里未提交的文本收口成一个 chip，避免被随后的 {@code setText} 静默吃掉。
     *
     * 触发场景：输入框里已经有内容（正在编辑某个标签，或者正在打字），此时又去编辑另一个
     * 标签 —— 直接 {@code setText(name)} 会覆盖它，那段文本连同它在 keyword 里的那一份一起
     * 消失（keyword 是「chips + 输入框文本」拼出来的），用户只看到结果变了却不知道原因。
     *
     * 为什么静默保留、不弹窗询问：触发频率高（输入框非空是常态）、代价低（丢的是搜索词、
     * 不是草稿）、而且那段文本本来就已经在 keyword 里。弹窗会逼用户现场理解「清空 / 保留」
     * 的语义，为一个随手一点的动作付这份认知成本不划算。
     *
     * 先归位成 chip 之后，keyword 的术语集合不变，所以不会出现「点一下标签，搜索结果莫名
     * 变了」。内容为空、或内容已是既有 chip 时什么都不做（后者避免造出重复 chip）。
     */
    private void commitPendingInputAsChip() {
        EditText ed = baseBind.searchTagsFlow.getEditor();
        if (ed == null) return;
        String pending = ed.getText().toString().trim();
        if (pending.isEmpty() || committedTags.contains(pending)) return;
        committedTags.add(pending);
    }

    /**
     * 补全浮层可见时把搜索栏钉住（不可收起），收起后恢复。
     *
     * 浮层的位置是 {@code toolbar.getBottom()} 的静态快照（见 initData 里那段 post 定位），
     * 所以搜索栏一旦被滚动收起，锚点就落空 —— 浮层悬在原处、与搜索栏脱钩，而浮层下方露出的
     * 内容还在继续滚。
     *
     * 做法是把 Toolbar 的 {@code layout_scrollFlags} 摘成 0：AppBarLayout 的
     * {@code getTotalScrollRange()} 扫到第一个不带 SCROLL 的子项就 break，于是 range 归零、
     * {@code hasScrollableChildren()} 为 false、Behavior 的 {@code canScrollChildren()} 不成立，
     * nested scroll 一律不消费 —— AppBar 收不起来，而内容照常滚动（不会出现「滚了却被吃掉」
     * 的抖动）。range 缓存在 onMeasure / onLayout 里失效，所以下面这次 setLayoutParams 触发的
     * 重新布局足以让新 flags 生效。
     *
     * {@code setExpanded} 请求在下一次布局中展开，确保已有折叠偏移也归零。
     * 内容区随 range 同步重新测量；FeedBackToTopFab 的底距补偿也随之更新。
     */
    private void setSearchBarCollapsible(boolean collapsible) {
        ViewGroup.LayoutParams lp = baseBind.toolbar.getLayoutParams();
        if (!(lp instanceof AppBarLayout.LayoutParams)) return;
        AppBarLayout.LayoutParams alp = (AppBarLayout.LayoutParams) lp;
        int target = collapsible ? mToolbarScrollFlags : 0;
        // 幂等：值没变就不白折腾一次布局
        if (alp.getScrollFlags() == target) return;
        if (!collapsible) {
            baseBind.appBar.setExpanded(true, false);
        }
        alp.setScrollFlags(target);
        baseBind.toolbar.setLayoutParams(alp);
    }

    /**
     * 长按 chip 弹出的居中菜单：复制文本 / 删除 / 编辑。
     * 编辑＝把 chip 还原回输入框、移除该 chip、聚焦输入框唤起键盘，让用户改完再回车提交。
     */
    private void showTagActionMenu(String name) {
        String[] items = new String[]{
                getString(R.string.tag_action_copy),
                getString(R.string.tag_action_delete),
                getString(R.string.tag_action_edit)
        };
        new WitDialog.MenuDialogBuilder(mContext)
                .addItems(items, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) {
                        Common.copy(mContext, name);
                    } else if (which == 1) {
                        confirmRemoveTag(name);
                    } else if (which == 2) {
                        editTagFromChip(name);
                    }
                })
                .show();
    }
}
