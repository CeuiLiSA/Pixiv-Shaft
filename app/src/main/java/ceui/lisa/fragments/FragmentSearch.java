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
import androidx.recyclerview.widget.LinearLayoutManager;

import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;
import com.qmuiteam.qmui.widget.dialog.QMUITipDialog;
import com.zhy.view.flowlayout.FlowLayout;
import com.zhy.view.flowlayout.TagAdapter;
import com.zhy.view.flowlayout.TagFlowLayout;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import ceui.lisa.R;
import ceui.lisa.activities.OutWakeActivity;
import ceui.lisa.activities.SearchActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.UserActivity;
import ceui.lisa.adapters.SearchHintAdapter;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.SearchEntity;
import ceui.lisa.databinding.FragmentSearchBinding;
import ceui.lisa.databinding.RecySingleLineTextWithDeleteBinding;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.interfaces.OnItemLongClickListener;
import ceui.lisa.model.ListTrendingtag;
import ceui.lisa.utils.ClipBoardUtils;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.utils.SearchTypeUtil;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentSearch extends BaseFragment<FragmentSearchBinding> {

    private ObservableEmitter<String> fuck = null;
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
        headParams.height = Shaft.statusHeight;
        baseBind.head.setLayoutParams(headParams);
        Observable.create(new ObservableOnSubscribe<String>() {
            @Override
            public void subscribe(ObservableEmitter<String> emitter) throws Exception {
                fuck = emitter;
            }
        }).subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .debounce(800, TimeUnit.MILLISECONDS)
                .subscribe(new ErrorCtrl<String>() {
                    @Override
                    public void next(String s) {
                        completeWord(s);
                    }
                });
        baseBind.inputBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                String inputs = String.valueOf(charSequence);
                baseBind.clear.setVisibility(inputs.length() > 0 ? View.VISIBLE : View.INVISIBLE);

                if (inputs.length() > 0 && searchType == 0 && !inputs.endsWith(" ") && fuck != null) {
                    List<String> keys = Arrays.stream(inputs.split(" "))
                            .filter(s -> !TextUtils.isEmpty(s)).collect(Collectors.toList());
                    if (keys.size() > 0) {
                        fuck.onNext(keys.get(keys.size() - 1));
                    }
                } else if(inputs.length() > 0 && searchType == 5 && !inputs.endsWith(" ") && fuck != null && !Common.isNumeric(inputs)){
                    List<String> keys = Arrays.stream(inputs.split(" "))
                            .filter(s -> !TextUtils.isEmpty(s)).collect(Collectors.toList());
                    if (keys.size() > 0) {
                        fuck.onNext(keys.get(keys.size() - 1));
                    }
                }  else {
                    baseBind.hintList.setVisibility(View.GONE);
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
        baseBind.inputBox.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    if (baseBind.hintList.getAdapter() != null) {
                        baseBind.hintList.setVisibility(View.VISIBLE);
                    }
                }
            }
        });
        baseBind.clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                baseBind.inputBox.setText("");
            }
        });

        baseBind.inputBox.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if (baseBind.hintList.getAdapter() != null &&
                        ((SearchHintAdapter) baseBind.hintList.getAdapter())
                                .getKeyword().equals(baseBind.inputBox.getText().toString())) {
                    baseBind.hintList.setVisibility(View.VISIBLE);
                }
                return false;
            }
        });

        baseBind.container.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (baseBind.hintList.getVisibility() == View.VISIBLE) {
                    baseBind.hintList.setVisibility(View.INVISIBLE);
                }
            }
        });
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
            baseBind.hintList.setVisibility(View.INVISIBLE);
            //PixivOperate.insertSearchHistory(trimmedKeyword, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD);
            Intent intent = new Intent(mContext, SearchActivity.class);
            intent.putExtra(Params.KEY_WORD, trimmedKeyword);
            intent.putExtra(Params.INDEX, 0);
            startActivity(intent);
        } else if (searchType == 1) {
            if (Common.isNumeric(trimmedKeyword)) {
                PixivOperate.insertSearchHistory(trimmedKeyword, SearchTypeUtil.SEARCH_TYPE_DB_ILLUSTSID);
                PixivOperate.getIllustByID(sUserModel, tryParseId(trimmedKeyword), mContext);
            } else {
                Common.showToast(getString(R.string.string_154));
            }
        }  else if (searchType == 2) {
            if (Common.isNumeric(trimmedKeyword)) {
                PixivOperate.insertSearchHistory(trimmedKeyword, SearchTypeUtil.SEARCH_TYPE_DB_USERID);
                Intent intent = new Intent(mContext, UserActivity.class);
                intent.putExtra(Params.USER_ID, Integer.valueOf(trimmedKeyword));
                startActivity(intent);
            } else {
                Common.showToast(getString(R.string.string_154));
            }
        } else if (searchType == 3) {
            if (Common.isNumeric(trimmedKeyword)) {
                PixivOperate.insertSearchHistory(trimmedKeyword, SearchTypeUtil.SEARCH_TYPE_DB_NOVELID);
                PixivOperate.getNovelByID(sUserModel, tryParseId(trimmedKeyword), mContext, null);
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
                QMUITipDialog tipDialog = new QMUITipDialog.Builder(mContext)
                        .setIconType(QMUITipDialog.Builder.ICON_TYPE_LOADING)
                        .setTipWord(getString(R.string.string_429))
                        .create();
                tipDialog.show();
                //先假定为作品id
                PixivOperate.getIllustByID(sUserModel, tryParseId(trimmedKeyword), mContext, new Callback<Void>() {
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
                        Intent intent = new Intent(mContext, UserActivity.class);
                        intent.putExtra(Params.USER_ID, Integer.valueOf(trimmedKeyword));
                        startActivity(intent);
                    }
                });
            }
            else{
                baseBind.hintList.setVisibility(View.INVISIBLE);
                //PixivOperate.insertSearchHistory(trimmedKeyword, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD);
                Intent intent = new Intent(mContext, SearchActivity.class);
                intent.putExtra(Params.KEY_WORD, trimmedKeyword);
                intent.putExtra(Params.INDEX, 0);
                startActivity(intent);
            }
        }
    }

    private void completeWord(String key) {
        Retro.getAppApi().searchCompleteWord(sUserModel.getAccess_token(), key)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<ListTrendingtag>() {
                    @Override
                    public void success(ListTrendingtag listTrendingtag) {
                        baseBind.hintList.setLayoutManager(new LinearLayoutManager(mContext));
                        SearchHintAdapter searchHintAdapter =
                                new SearchHintAdapter(listTrendingtag.getList(), mContext, key);
                        // 点击直接搜索条目
                        searchHintAdapter.setOnItemClickListener(new OnItemClickListener() {
                            @Override
                            public void onItemClick(View v, int position, int viewType) {
                                baseBind.hintList.setVisibility(View.INVISIBLE);
                                String keyword = listTrendingtag.getList().get(position).getTag();
                                //PixivOperate.insertSearchHistory(keyword, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD);
                                Intent intent = new Intent(mContext, SearchActivity.class);
                                intent.putExtra(Params.KEY_WORD, keyword);
                                intent.putExtra(Params.INDEX, 0);
                                startActivity(intent);
                            }
                        });
                        // 长按将条目填写入搜索框
                        searchHintAdapter.setOnItemLongClickListener(new OnItemLongClickListener() {
                            @Override
                            public void onItemLongClick(View v, int position, int viewType) {
                                baseBind.hintList.setVisibility(View.INVISIBLE);
                                // 将现有文字最后方的非空部分替换为 hint
                                String currentInput = baseBind.inputBox.getText().toString();
                                List<String> keys = Arrays.stream(currentInput.split(" "))
                                        .filter(s -> !TextUtils.isEmpty(s)).collect(Collectors.toList());
                                String tagName = listTrendingtag.getList().get(position).getTag();
                                StringBuilder sb = new StringBuilder();
                                if (keys.size() > 0) {
                                    keys.set(keys.size() - 1, tagName);
                                    sb.append(TextUtils.join(" ", keys));
                                } else {
                                    sb.append(tagName);
                                }
                                baseBind.inputBox.setText(sb.append(" ").toString());
                                baseBind.inputBox.setSelection(baseBind.inputBox.getText().length());
                            }
                        });
                        baseBind.hintList.setAdapter(searchHintAdapter);
                        baseBind.hintList.setVisibility(View.VISIBLE);
                    }
                });
    }

    private void getHotTags() {
        Retro.getAppApi().getHotTags(sUserModel.getAccess_token(), Params.TYPE_ILLUST)
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
                                baseBind.hintList.setVisibility(View.INVISIBLE);
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

    private void loadHistory() {
        List<SearchEntity> history = AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().getAll(50);
        baseBind.searchHistory.setAdapter(new TagAdapter<SearchEntity>(history) {
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
                binding.deleteItem.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        AppDatabase.getAppDatabase(mContext).searchDao().deleteSearchEntity(searchEntity);
                        Common.showToast("删除成功");
                        loadHistory();
                    }
                });
                return binding.getRoot();
            }
        });
        if (history != null && history.size() != 0) {
            baseBind.clearHistory.setVisibility(View.VISIBLE);
            baseBind.clearHistory.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new QMUIDialog.MessageDialogBuilder(getActivity())
                            .setTitle(getString(R.string.string_143))
                            .setMessage(getString(R.string.string_144))
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addAction(getString(R.string.string_142), new QMUIDialogAction.ActionListener() {
                                @Override
                                public void onClick(QMUIDialog dialog, int index) {
                                    dialog.dismiss();
                                }
                            })
                            .addAction(0, getString(R.string.string_141), QMUIDialogAction.ACTION_PROP_NEGATIVE, new QMUIDialogAction.ActionListener() {
                                @Override
                                public void onClick(QMUIDialog dialog, int index) {
                                    AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().deleteAllUnpinned();
                                    Common.showToast(getString(R.string.string_140));
                                    dialog.dismiss();
                                    onResume();
                                }
                            })
                            .show();
                }
            });
        } else {
            baseBind.clearHistory.setVisibility(View.INVISIBLE);
        }
        baseBind.searchHistory.setOnTagClickListener(new TagFlowLayout.OnTagClickListener() {
            @Override
            public boolean onTagClick(View view, int position, FlowLayout parent) {
                if (history.get(position).getSearchType() == SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD) {
                    baseBind.hintList.setVisibility(View.INVISIBLE);
                    Intent intent = new Intent(mContext, SearchActivity.class);
                    intent.putExtra(Params.KEY_WORD, history.get(position).getKeyword());
                    intent.putExtra(Params.INDEX, 0);
                    startActivity(intent);
                } else if (history.get(position).getSearchType() == SearchTypeUtil.SEARCH_TYPE_DB_ILLUSTSID) {
                    history.get(position).setSearchTime(System.currentTimeMillis());
                    AppDatabase.getAppDatabase(mContext).searchDao().insert(history.get(position));
                    PixivOperate.getIllustByID(sUserModel, tryParseId(history.get(position).getKeyword()), mContext);
                } else if (history.get(position).getSearchType() == SearchTypeUtil.SEARCH_TYPE_DB_USERKEYWORD) {
                    baseBind.hintList.setVisibility(View.INVISIBLE);
                    Intent intent = new Intent(mContext, SearchActivity.class);
                    intent.putExtra(Params.KEY_WORD, history.get(position).getKeyword());
                    intent.putExtra(Params.INDEX, 0);
                    startActivity(intent);
                } else if (history.get(position).getSearchType() == SearchTypeUtil.SEARCH_TYPE_DB_USERID) {
                    history.get(position).setSearchTime(System.currentTimeMillis());
                    AppDatabase.getAppDatabase(mContext).searchDao().insert(history.get(position));
                    Intent intent = new Intent(mContext, UserActivity.class);
                    intent.putExtra(Params.USER_ID, Integer.valueOf(history.get(position).getKeyword()));
                    startActivity(intent);
                } else if (history.get(position).getSearchType() == SearchTypeUtil.SEARCH_TYPE_DB_NOVELID) {
                    history.get(position).setSearchTime(System.currentTimeMillis());
                    AppDatabase.getAppDatabase(mContext).searchDao().insert(history.get(position));
                    PixivOperate.getNovelByID(sUserModel, tryParseId(history.get(position).getKeyword()), mContext, null);
                } else if (history.get(position).getSearchType() == SearchTypeUtil.SEARCH_TYPE_DB_URL) {
                    history.get(position).setSearchTime(System.currentTimeMillis());
                    AppDatabase.getAppDatabase(mContext).searchDao().insert(history.get(position));
                    Intent intent = new Intent(mContext, OutWakeActivity.class);
                    intent.setData(Uri.parse(history.get(position).getKeyword()));
                    startActivity(intent);
                }
                return false;
            }
        });
        baseBind.searchHistory.setOnTagLongClickListener(new TagFlowLayout.OnTagLongClickListener() {
            @Override
            public boolean onTagLongClick(View view, int position, FlowLayout parent) {
                final SearchEntity searchEntity = history.get(position);
                new QMUIDialog.MessageDialogBuilder(mContext)
                        .setTitle(R.string.string_87)
                        .setMessage(searchEntity.getKeyword())
                        .setSkinManager(QMUISkinManager.defaultInstance(mActivity))
                        .addAction(getString(R.string.string_142), new QMUIDialogAction.ActionListener() {
                            @Override
                            public void onClick(QMUIDialog dialog, int index) {
                                dialog.dismiss();
                            }
                        })
                        .addAction(searchEntity.isPinned() ? getString(R.string.string_443) : getString(R.string.string_442), new QMUIDialogAction.ActionListener() {
                            @Override
                            public void onClick(QMUIDialog dialog, int index) {
                                searchEntity.setPinned(!searchEntity.isPinned());
                                AppDatabase.getAppDatabase(mContext).searchDao().insert(searchEntity);
                                baseBind.searchHistory.getAdapter().notifyDataChanged();
                                dialog.dismiss();
                            }
                        })
                        .addAction(getString(R.string.string_120), new QMUIDialogAction.ActionListener() {
                            @Override
                            public void onClick(QMUIDialog dialog, int index) {
                                Common.copy(mContext, searchEntity.getKeyword());
                                dialog.dismiss();
                            }
                        })
                        .show();
                return true;
            }
        });
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
        new QMUIDialog.CheckableDialogBuilder(mContext)
                .setTitle(fromClipboard ? R.string.string_425 : R.string.string_424)
                .setCheckedIndex(searchType)
                .setSkinManager(QMUISkinManager.defaultInstance(mContext))
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
