package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.URLUtil;
import android.widget.TextView;

import androidx.databinding.DataBindingUtil;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import ceui.pixiv.witstudio.dialog.WitDialog;
import ceui.pixiv.witstudio.dialog.WitDialogAction;
import ceui.pixiv.witstudio.dialog.WitTipDialog;
import com.zhy.view.flowlayout.FlowLayout;
import com.zhy.view.flowlayout.TagAdapter;
import com.zhy.view.flowlayout.TagFlowLayout;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import ceui.lisa.R;
import ceui.lisa.activities.OutWakeActivity;
import ceui.lisa.activities.SearchActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.utils.SystemBarMetrics;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.UActivity;
import ceui.lisa.adapters.SearchHintAdapter;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.SearchEntity;
import ceui.lisa.databinding.FragmentSearchBinding;
import ceui.lisa.databinding.RecySingleLineTextWithDeleteBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.model.ListTrendingtag;
import ceui.lisa.utils.ClipBoardUtils;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.utils.SearchTypeUtil;
import ceui.pixiv.ui.search.SearchHintViewModel;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;


public class FragmentSearch extends BaseFragment<FragmentSearchBinding> {

    private SearchHintViewModel hintViewModel;
    private int searchType = SearchTypeUtil.defaultSearchType;
    private boolean hasSwitchSearchType = false;

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_search;
    }

    @Override
    protected void initData() {
        final String[] SEARCH_TYPE = SearchTypeUtil.SEARCH_TYPE_NAME;

        ViewGroup.LayoutParams headParams = baseBind.head.getLayoutParams();
        headParams.height = SystemBarMetrics.statusBarHeight(mContext);
        baseBind.head.setLayoutParams(headParams);
        hintViewModel = new ViewModelProvider(this).get(SearchHintViewModel.class);
        setupHintObservers();
        baseBind.inputBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                String inputs = String.valueOf(charSequence);
                baseBind.clear.setVisibility(inputs.length() > 0 ? View.VISIBLE : View.INVISIBLE);

                boolean shouldAutocomplete = inputs.length() > 0 && !inputs.endsWith(" ")
                        && (searchType == 0 || (searchType == 5 && !Common.isNumeric(inputs)));
                if (shouldAutocomplete) {
                    List<String> keys = Arrays.stream(inputs.split(" "))
                            .filter(s -> !TextUtils.isEmpty(s)).collect(Collectors.toList());
                    if (keys.size() > 0) {
                        hintViewModel.onTextChanged(keys.get(keys.size() - 1));
                    }
                } else {
                    hintViewModel.clearHints();
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {

            }
        });
        baseBind.inputBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView textView, int i, KeyEvent keyEvent) {
                if (!TextUtils.isEmpty(baseBind.inputBox.getText().toString())) {
                    Common.hideKeyboard(mActivity);
                    dispatchClick(baseBind.inputBox.getText().toString(), searchType);
                    return true;
                } else {
                    Common.showToast(getString(R.string.string_148));
                }
                return false;
            }
        });
        baseBind.inputBox.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                hintViewModel.showHintsIfAvailable();
            }
        });
        baseBind.clear.setOnClickListener(view -> baseBind.inputBox.setText(""));
        baseBind.inputBox.setOnTouchListener((view, motionEvent) -> {
            hintViewModel.showHintsIfAvailable();
            return false;
        });
        baseBind.container.setOnClickListener(view -> hintViewModel.hideHints());
        baseBind.more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                popUpSearchTypeSwitcher();
            }
        });
        baseBind.inputBox.setHint(SEARCH_TYPE[searchType]);
        getHotTags();
    }

    private void dispatchClick(String keyWord, int searchType) {
        String trimmedKeyword = keyWord.trim();
        if (searchType == 0) {
            hintViewModel.hideHints();
            //PixivOperate.insertSearchHistory(trimmedKeyword, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD);
            Intent intent = new Intent(mContext, SearchActivity.class);
            intent.putExtra(Params.KEY_WORD, trimmedKeyword);
            intent.putExtra(Params.INDEX, 0);
            startActivity(intent);
        } else if (searchType == 1) {
            if (Common.isNumeric(trimmedKeyword)) {
                PixivOperate.insertSearchHistory(trimmedKeyword, SearchTypeUtil.SEARCH_TYPE_DB_ILLUSTSID);
                PixivOperate.getIllustByID(tryParseId(trimmedKeyword), mContext);
            } else {
                Common.showToast(getString(R.string.string_154));
            }
        }  else if (searchType == 2) {
            if (Common.isNumeric(trimmedKeyword)) {
                PixivOperate.insertSearchHistory(trimmedKeyword, SearchTypeUtil.SEARCH_TYPE_DB_USERID);
                Intent intent = new Intent(mContext, UActivity.class);
                intent.putExtra(Params.USER_ID, Common.safeUserId(trimmedKeyword));
                startActivity(intent);
            } else {
                Common.showToast(getString(R.string.string_154));
            }
        } else if (searchType == 3) {
            if (Common.isNumeric(trimmedKeyword)) {
                PixivOperate.insertSearchHistory(trimmedKeyword, SearchTypeUtil.SEARCH_TYPE_DB_NOVELID);
                PixivOperate.getNovelByID(tryParseId(trimmedKeyword), mContext, null);
            } else {
                Common.showToast(getString(R.string.string_154));
            }
        } else if (searchType == 4) {
            if (!TextUtils.isEmpty(trimmedKeyword) && URLUtil.isValidUrl(trimmedKeyword)) {
                try {
                    PixivOperate.insertSearchHistory(trimmedKeyword, SearchTypeUtil.SEARCH_TYPE_DB_URL);
                    Intent intent = new Intent(mContext, OutWakeActivity.class);
                    intent.setData(Uri.parse(trimmedKeyword));
                    startActivity(intent);
                } catch (Exception e) {
                    Common.showToast(e.toString());
                    e.printStackTrace();
                }
            } else {
                Common.showToast(getString(R.string.string_408));
            }
        } else if (searchType == 5) {
            if (URLUtil.isValidUrl(trimmedKeyword)) {
                try {
                    PixivOperate.insertSearchHistory(trimmedKeyword, SearchTypeUtil.SEARCH_TYPE_DB_URL);
                    Intent intent = new Intent(mContext, OutWakeActivity.class);
                    intent.setData(Uri.parse(trimmedKeyword));
                    startActivity(intent);
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
                    }
                }, new Callback<Void>() {
                    @Override
                    public void doSomething(Void t) {
                        tipDialog.dismiss();
                        PixivOperate.insertSearchHistory(trimmedKeyword, SearchTypeUtil.SEARCH_TYPE_DB_USERID);
                        Intent intent = new Intent(mContext, UActivity.class);
                        intent.putExtra(Params.USER_ID, Common.safeUserId(trimmedKeyword));
                        startActivity(intent);
                    }
                });
            }
            else{
                hintViewModel.hideHints();
                //PixivOperate.insertSearchHistory(trimmedKeyword, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD);
                Intent intent = new Intent(mContext, SearchActivity.class);
                intent.putExtra(Params.KEY_WORD, trimmedKeyword);
                intent.putExtra(Params.INDEX, 0);
                startActivity(intent);
            }
        }
    }

    private void setupHintObservers() {
        baseBind.hintList.setLayoutManager(new LinearLayoutManager(mContext));
        hintViewModel.getHints().observe(getViewLifecycleOwner(), hints -> {
            if (hints == null || hints.isEmpty()) return;
            String keyword = hintViewModel.getCurrentKeyword().getValue();
            SearchHintAdapter adapter = new SearchHintAdapter(hints, mContext, keyword != null ? keyword : "");
            adapter.setOnItemClickListener((v, position, viewType) -> {
                hintViewModel.hideHints();
                String tag = hints.get(position).getTag();
                Intent intent = new Intent(mContext, SearchActivity.class);
                intent.putExtra(Params.KEY_WORD, tag);
                intent.putExtra(Params.INDEX, 0);
                startActivity(intent);
            });
            adapter.setOnItemLongClickListener((v, position, viewType) -> {
                hintViewModel.hideHints();
                String currentInput = baseBind.inputBox.getText().toString();
                List<String> keys = Arrays.stream(currentInput.split(" "))
                        .filter(s -> !TextUtils.isEmpty(s)).collect(Collectors.toList());
                String tagName = hints.get(position).getTag();
                StringBuilder sb = new StringBuilder();
                if (keys.size() > 0) {
                    keys.set(keys.size() - 1, tagName);
                    sb.append(TextUtils.join(" ", keys));
                } else {
                    sb.append(tagName);
                }
                baseBind.inputBox.setText(sb.append(" ").toString());
                baseBind.inputBox.setSelection(baseBind.inputBox.getText().length());
            });
            baseBind.hintList.setAdapter(adapter);
        });
        hintViewModel.getHintsVisible().observe(getViewLifecycleOwner(), visible -> {
            animateHintList(visible != null && visible);
        });
    }

    private void animateHintList(boolean show) {
        if (show) {
            if (baseBind.hintList.getVisibility() == View.VISIBLE) return;
            baseBind.hintList.setAlpha(0f);
            baseBind.hintList.setTranslationY(-24f);
            baseBind.hintList.setVisibility(View.VISIBLE);
            baseBind.hintList.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        } else {
            if (baseBind.hintList.getVisibility() != View.VISIBLE) return;
            baseBind.hintList.animate()
                    .alpha(0f)
                    .translationY(-16f)
                    .setDuration(160)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        baseBind.hintList.setVisibility(View.GONE);
                        baseBind.hintList.setTranslationY(0f);
                    })
                    .start();
        }
    }

    private void getHotTags() {
        Retro.getAppApi().getHotTags(Params.TYPE_ILLUST)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<ListTrendingtag>() {
                    @Override
                    public void success(ListTrendingtag listTrendingtag) {
                        baseBind.hotTags.setAdapter(new TagAdapter<ListTrendingtag.TrendTagsBean>(
                                listTrendingtag.getList().subList(0, 15)) {
                            @Override
                            public View getView(FlowLayout parent, int position, ListTrendingtag.TrendTagsBean trendTagsBean) {
                                TextView tv = (TextView) LayoutInflater.from(mContext).inflate(R.layout.recy_single_line_text,
                                        parent, false);
                                if (!TextUtils.isEmpty(trendTagsBean.getTranslated_name())) {
                                    tv.setText(String.format("%s/%s", trendTagsBean.getTag(), trendTagsBean.getTranslated_name()));
                                } else {
                                    tv.setText(trendTagsBean.getTag());
                                }
                                return tv;
                            }
                        });
                        baseBind.hotTags.setOnTagClickListener(new TagFlowLayout.OnTagClickListener() {
                            @Override
                            public boolean onTagClick(View view, int position, FlowLayout parent) {
                                hintViewModel.hideHints();
                                String keyword = listTrendingtag.getList().get(position).getTag();
                                //PixivOperate.insertSearchHistory(keyword, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD);
                                Intent intent = new Intent(mContext, SearchActivity.class);
                                intent.putExtra(Params.KEY_WORD, keyword);
                                intent.putExtra(Params.INDEX, 0);
                                startActivity(intent);
                                return false;
                            }
                        });
                        baseBind.hotTags.setOnTagLongClickListener(new TagFlowLayout.OnTagLongClickListener() {
                            @Override
                            public boolean onTagLongClick(View view, int position, FlowLayout parent) {
                                Common.copy(mContext, listTrendingtag.getList().get(position).getTag());
                                return true;
                            }
                        });
                    }
                });
    }

    @Override
    public void onResume() {
        super.onResume();
        loadHistory();
        predictSearchType();
    }

    /**
     * Load the search history
     * */
    private void loadHistory() {
        // 固定标签全量取，最近搜索另取上限；不能把两者塞进同一个 LIMIT，否则
        // 固定数量超出上限时会被静默挤掉（issue #524）
        List<SearchEntity> pinned = AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().getAllPinned();
        List<SearchEntity> recent = AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().getRecentUnpinned(50);
        bindPinnedSection(pinned);
        bindHistorySection(recent);
    }

    private void bindPinnedSection(final List<SearchEntity> pinned) {
        if (pinned.isEmpty()) {
            baseBind.pinnedSection.setVisibility(View.GONE);
            return;
        }
        baseBind.pinnedSection.setVisibility(View.VISIBLE);
        baseBind.pinnedTagsFlow.setAdapter(buildTagAdapter(pinned));
        baseBind.pinnedTagsFlow.setOnTagClickListener((view, position, parent) -> {
            handleHistoryClick(pinned.get(position));
            return false;
        });
        baseBind.pinnedTagsFlow.setOnTagLongClickListener((view, position, parent) ->
                showHistoryActionDialog(pinned.get(position)));
        baseBind.clearPinned.setOnClickListener(v -> new WitDialog.MessageDialogBuilder(getActivity())
                .setTitle(getString(R.string.string_143))
                .setMessage(getString(R.string.clear_pinned_tags_msg))
                .addAction(getString(R.string.string_142), (dialog, index) -> dialog.dismiss())
                .addAction(0, getString(R.string.string_141), WitDialogAction.ACTION_PROP_NEGATIVE, (dialog, index) -> {
                    AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().deleteAllPinned();
                    Common.showToast(getString(R.string.pinned_tags_cleared));
                    dialog.dismiss();
                    onResume();
                })
                .show());
        baseBind.viewAllPinned.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "PinnedTagsList");
            startActivity(intent);
        });
    }

    private void bindHistorySection(final List<SearchEntity> history) {
        if (history.isEmpty()) {
            baseBind.historySection.setVisibility(View.GONE);
            return;
        }
        baseBind.historySection.setVisibility(View.VISIBLE);
        baseBind.searchHistory.setAdapter(buildTagAdapter(history));
        baseBind.searchHistory.setOnTagClickListener((view, position, parent) -> {
            handleHistoryClick(history.get(position));
            return false;
        });
        baseBind.searchHistory.setOnTagLongClickListener((view, position, parent) ->
                showHistoryActionDialog(history.get(position)));
        baseBind.clearHistory.setOnClickListener(v -> new WitDialog.MessageDialogBuilder(getActivity())
                .setTitle(getString(R.string.string_143))
                .setMessage(getString(R.string.string_144))
                .addAction(getString(R.string.string_142), (dialog, index) -> dialog.dismiss())
                .addAction(0, getString(R.string.string_141), WitDialogAction.ACTION_PROP_NEGATIVE, (dialog, index) -> {
                    AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().deleteAllUnpinned();
                    Common.showToast(getString(R.string.string_140));
                    dialog.dismiss();
                    onResume();
                })
                .show());
    }

    private TagAdapter<SearchEntity> buildTagAdapter(final List<SearchEntity> data) {
        return new TagAdapter<SearchEntity>(data) {
            @Override
            public View getView(FlowLayout parent, int position, SearchEntity searchEntity) {
                RecySingleLineTextWithDeleteBinding binding = DataBindingUtil.inflate(
                        LayoutInflater.from(mContext), R.layout.recy_single_line_text_with_delete,
                        parent, false);
                if (searchEntity.isPinned()) {
                    binding.fixed.setVisibility(View.VISIBLE);
                    binding.deleteItem.setVisibility(View.GONE);
                } else {
                    binding.fixed.setVisibility(View.GONE);
                    binding.deleteItem.setVisibility(View.VISIBLE);
                }
                binding.tagTitle.setText(searchEntity.getKeyword());
                binding.deleteItem.setOnClickListener(view -> {
                    AppDatabase.getAppDatabase(mContext).searchDao().deleteSearchEntity(searchEntity);
                    Common.showToast("删除成功");
                    loadHistory();
                });
                return binding.getRoot();
            }
        };
    }

    private void handleHistoryClick(SearchEntity entity) {
        int type = entity.getSearchType();
        if (type == SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD) {
            hintViewModel.hideHints();
            Intent intent = new Intent(mContext, SearchActivity.class);
            intent.putExtra(Params.KEY_WORD, entity.getKeyword());
            intent.putExtra(Params.INDEX, 0);
            startActivity(intent);
        } else if (type == SearchTypeUtil.SEARCH_TYPE_DB_ILLUSTSID) {
            entity.setSearchTime(System.currentTimeMillis());
            AppDatabase.getAppDatabase(mContext).searchDao().insert(entity);
            PixivOperate.getIllustByID(tryParseId(entity.getKeyword()), mContext);
        } else if (type == SearchTypeUtil.SEARCH_TYPE_DB_USERKEYWORD) {
            hintViewModel.hideHints();
            Intent intent = new Intent(mContext, SearchActivity.class);
            intent.putExtra(Params.KEY_WORD, entity.getKeyword());
            intent.putExtra(Params.INDEX, 0);
            startActivity(intent);
        } else if (type == SearchTypeUtil.SEARCH_TYPE_DB_USERID) {
            entity.setSearchTime(System.currentTimeMillis());
            AppDatabase.getAppDatabase(mContext).searchDao().insert(entity);
            Intent intent = new Intent(mContext, UActivity.class);
            intent.putExtra(Params.USER_ID, Common.safeUserId(entity.getKeyword()));
            startActivity(intent);
        } else if (type == SearchTypeUtil.SEARCH_TYPE_DB_NOVELID) {
            entity.setSearchTime(System.currentTimeMillis());
            AppDatabase.getAppDatabase(mContext).searchDao().insert(entity);
            PixivOperate.getNovelByID(tryParseId(entity.getKeyword()), mContext, null);
        } else if (type == SearchTypeUtil.SEARCH_TYPE_DB_URL) {
            entity.setSearchTime(System.currentTimeMillis());
            AppDatabase.getAppDatabase(mContext).searchDao().insert(entity);
            Intent intent = new Intent(mContext, OutWakeActivity.class);
            intent.setData(Uri.parse(entity.getKeyword()));
            startActivity(intent);
        }
    }

    private boolean showHistoryActionDialog(final SearchEntity searchEntity) {
        new WitDialog.MessageDialogBuilder(mContext)
                .setTitle(R.string.string_87)
                .setMessage(searchEntity.getKeyword())
                .addAction(getString(R.string.string_142), (dialog, index) -> dialog.dismiss())
                .addAction(searchEntity.isPinned() ? getString(R.string.string_443) : getString(R.string.string_442), (dialog, index) -> {
                    searchEntity.setPinned(!searchEntity.isPinned());
                    AppDatabase.getAppDatabase(mContext).searchDao().insert(searchEntity);
                    dialog.dismiss();
                    // 切换 pinned 后该条目需要在两个 section 间挪动,简单粗暴重新加载两段
                    loadHistory();
                })
                .addAction(getString(R.string.string_120), (dialog, index) -> {
                    Common.copy(mContext, searchEntity.getKeyword());
                    dialog.dismiss();
                })
                .show();
        return true;
    }

    private void predictSearchType(){
        // 当前搜索过程，手动切换后 或 输入框里有值时，不再根据剪贴板内容预测
        if(hasSwitchSearchType){
            return;
        }
        if(!TextUtils.isEmpty(baseBind.inputBox.getText().toString())){
            return;
        }
        mActivity.getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                String content = ClipBoardUtils.getClipboardContent(mContext);
                String previousClipboardValue = Shaft.getMMKV().getString(Params.FRAGMENT_SEARCH_CLIPBOARD_VALUE, "");
                // 如果之前确认过的剪贴板值和本次相同，不进行预测
                if (!TextUtils.isEmpty(previousClipboardValue) && previousClipboardValue.equals(content)) {
                    return;
                }
                int suggestSearchType = SearchTypeUtil.getSuggestSearchType(content);
                // 预测类型和现在不同，进行切换并提示确认
                if (suggestSearchType != searchType) {
                    searchType = suggestSearchType;
                    baseBind.inputBox.setHint(SearchTypeUtil.SEARCH_TYPE_NAME[searchType]);
                    popUpSearchTypeSwitcher(true, content);
                }
            }
        });
    }

    private void popUpSearchTypeSwitcher(){
        popUpSearchTypeSwitcher(false, null);
    }

    private void popUpSearchTypeSwitcher(boolean fromClipboard, String clipboardContent) {
        final String[] SEARCH_TYPE = SearchTypeUtil.SEARCH_TYPE_NAME;
        new WitDialog.CheckableDialogBuilder(mContext)
                .setTitle(fromClipboard ? R.string.string_425 : R.string.string_424)
                .setCheckedIndex(searchType)
                .addItems(SEARCH_TYPE, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (searchType != which) {
                            searchType = which;
                            baseBind.inputBox.setHint(SEARCH_TYPE[which]);
                        }
                        // 只要选中过任何选项，就不再进行相同内容的剪贴板预测
                        if (fromClipboard) {
                            Shaft.getMMKV().putString(Params.FRAGMENT_SEARCH_CLIPBOARD_VALUE, clipboardContent);
                        }
                        // 对非标签搜索且非综合搜索的，进行填充
                        if (fromClipboard && (searchType != SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD && searchType != SearchTypeUtil.defaultSearchType)) {
                            baseBind.inputBox.setText(clipboardContent);
                        }
                        dialog.dismiss();
                    }
                })
                .create()
                .show();
        // 开启一次即不再开启
        if (fromClipboard) {
            hasSwitchSearchType = true;
        }
    }
}
