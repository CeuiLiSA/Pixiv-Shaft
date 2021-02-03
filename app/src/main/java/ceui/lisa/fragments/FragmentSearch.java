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
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;
import com.zhy.view.flowlayout.FlowLayout;
import com.zhy.view.flowlayout.TagAdapter;
import com.zhy.view.flowlayout.TagFlowLayout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import ceui.lisa.R;
import ceui.lisa.activities.OutWakeActivity;
import ceui.lisa.activities.SearchActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.UserActivity;
import ceui.lisa.adapters.SearchHintAdapter;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.SearchEntity;
import ceui.lisa.databinding.FragmentSearchBinding;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.ListTrendingtag;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.activities.Shaft.sUserModel;
import static ceui.lisa.utils.Common.isNumeric;
import static ceui.lisa.utils.PixivOperate.insertSearchHistory;

public class FragmentSearch extends BaseFragment<FragmentSearchBinding> {

    private ObservableEmitter<String> fuck = null;
    private int searchType = 0;

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_search;
    }

    @Override
    protected void initData() {
        final String[] SEARCH_TYPE = new String[]{
                getString(R.string.string_149),
                getString(R.string.string_150),
                getString(R.string.string_151),
                getString(R.string.string_152),
                getString(R.string.string_153),
                getString(R.string.string_341)
        };

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
                String key = String.valueOf(charSequence);
                if (key.length() != 0 && searchType == 0) {
                    if (fuck != null) {
                        fuck.onNext(key);
                    }
                    baseBind.clear.setVisibility(View.VISIBLE);
                } else {
                    baseBind.hintList.setVisibility(View.GONE);
                    baseBind.clear.setVisibility(View.INVISIBLE);
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
                new QMUIDialog.CheckableDialogBuilder(mContext)
                        .setCheckedIndex(searchType)
                        .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                        .addItems(SEARCH_TYPE, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (searchType != which) {
                                    baseBind.inputBox.setHint(SEARCH_TYPE[which]);
                                    searchType = which;
                                }
                                dialog.dismiss();
                            }
                        })
                        .create()
                        .show();
            }
        });
        baseBind.inputBox.setHint(SEARCH_TYPE[searchType]);
        getHotTags();
    }


    private void dispatchClick(String keyWord, int searchType) {
        if (searchType == 0) {
            baseBind.hintList.setVisibility(View.INVISIBLE);
            Intent intent = new Intent(mContext, SearchActivity.class);
            intent.putExtra(Params.KEY_WORD, keyWord);
            intent.putExtra(Params.INDEX, 0);
            startActivity(intent);
        } else if (searchType == 1) {
            if (isNumeric(keyWord)) {
                insertSearchHistory(keyWord, searchType);
                PixivOperate.getIllustByID(sUserModel, Integer.valueOf(keyWord), mContext);
            } else {
                Common.showToast(getString(R.string.string_154));
            }
        } else if (searchType == 2) {
            insertSearchHistory(keyWord, searchType);
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_KEYWORD,
                    keyWord);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "搜索用户");
            startActivity(intent);
        } else if (searchType == 3) {
            if (isNumeric(keyWord)) {
                insertSearchHistory(keyWord, searchType);
                Intent intent = new Intent(mContext, UserActivity.class);
                intent.putExtra(Params.USER_ID, Integer.valueOf(keyWord));
                startActivity(intent);
            } else {
                Common.showToast(getString(R.string.string_154));
            }
        } else if (searchType == 4) {
            if (isNumeric(keyWord)) {
                insertSearchHistory(keyWord, searchType);
                PixivOperate.getNovelByID(sUserModel, Integer.valueOf(keyWord), mContext, null);
            } else {
                Common.showToast(getString(R.string.string_154));
            }
        } else if (searchType == 5) {
            final String input = baseBind.inputBox.getText().toString();
            if (!TextUtils.isEmpty(input)) {
                try {
                    Intent intent = new Intent(mContext, OutWakeActivity.class);
                    intent.setData(Uri.parse(input));
                    startActivity(intent);
                } catch (Exception e) {
                    Common.showToast(e.toString());
                    e.printStackTrace();
                }
            }
        }
    }

    private void completeWord(String key) {
        Retro.getAppApi().searchCompleteWord(sUserModel.getResponse().getAccess_token(), key)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<ListTrendingtag>() {
                    @Override
                    public void success(ListTrendingtag listTrendingtag) {
                        baseBind.hintList.setLayoutManager(new LinearLayoutManager(mContext));
                        SearchHintAdapter searchHintAdapter =
                                new SearchHintAdapter(listTrendingtag.getList(), mContext, key);
                        searchHintAdapter.setOnItemClickListener(new OnItemClickListener() {
                            @Override
                            public void onItemClick(View v, int position, int viewType) {
                                baseBind.hintList.setVisibility(View.INVISIBLE);
                                Intent intent = new Intent(mContext, SearchActivity.class);
                                intent.putExtra(Params.KEY_WORD, listTrendingtag.getList().get(position).getTag());
                                intent.putExtra(Params.INDEX, 0);
                                startActivity(intent);
                            }
                        });
                        baseBind.hintList.setAdapter(searchHintAdapter);
                        baseBind.hintList.setVisibility(View.VISIBLE);
                    }
                });
    }

    private void getHotTags() {
        Retro.getAppApi().getHotTags(sUserModel.getResponse().getAccess_token(), Params.TYPE_ILLUST)
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
                                Intent intent = new Intent(mContext, SearchActivity.class);
                                intent.putExtra(Params.KEY_WORD, listTrendingtag.getList().get(position).getTag());
                                intent.putExtra(Params.INDEX, 0);
                                startActivity(intent);
                                return false;
                            }
                        });
                    }
                });
    }

    @Override
    public void onResume() {
        super.onResume();
        List<SearchEntity> history = AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().getAll(15);
        baseBind.searchHistory.setAdapter(new TagAdapter<SearchEntity>(history) {
            @Override
            public View getView(FlowLayout parent, int position, SearchEntity searchEntity) {
                TextView tv = (TextView) LayoutInflater.from(mContext).inflate(R.layout.recy_single_line_text,
                        parent, false);
                tv.setText(searchEntity.getKeyword());
                return tv;
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
                                    AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().deleteAll();
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
                if (history.get(position).getSearchType() == 0) {
                    baseBind.hintList.setVisibility(View.INVISIBLE);
                    Intent intent = new Intent(mContext, SearchActivity.class);
                    intent.putExtra(Params.KEY_WORD, history.get(position).getKeyword());
                    intent.putExtra(Params.INDEX, 0);
                    startActivity(intent);
                } else if (history.get(position).getSearchType() == 1) {
                    history.get(position).setSearchTime(System.currentTimeMillis());
                    AppDatabase.getAppDatabase(mContext).searchDao().insert(history.get(position));
                    PixivOperate.getIllustByID(sUserModel, Integer.parseInt(history.get(position).getKeyword()), mContext);
                } else if (history.get(position).getSearchType() == 2) {
                    history.get(position).setSearchTime(System.currentTimeMillis());
                    AppDatabase.getAppDatabase(mContext).searchDao().insert(history.get(position));
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_KEYWORD,
                            history.get(position).getKeyword());
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "搜索用户");
                    startActivity(intent);
                } else if (history.get(position).getSearchType() == 3) {
                    history.get(position).setSearchTime(System.currentTimeMillis());
                    AppDatabase.getAppDatabase(mContext).searchDao().insert(history.get(position));
                    Intent intent = new Intent(mContext, UserActivity.class);
                    intent.putExtra(Params.USER_ID, Integer.valueOf(history.get(position).getKeyword()));
                    startActivity(intent);
                } else if (history.get(position).getSearchType() == 4) {
                    history.get(position).setSearchTime(System.currentTimeMillis());
                    AppDatabase.getAppDatabase(mContext).searchDao().insert(history.get(position));
                    PixivOperate.getNovelByID(sUserModel, Integer.parseInt(history.get(position).getKeyword()), mContext, null);
                }
                return false;
            }
        });
    }
}
