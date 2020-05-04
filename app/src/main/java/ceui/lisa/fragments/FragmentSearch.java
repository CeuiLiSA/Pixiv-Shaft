package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.content.Intent;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.zhy.view.flowlayout.FlowLayout;
import com.zhy.view.flowlayout.TagAdapter;
import com.zhy.view.flowlayout.TagFlowLayout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import ceui.lisa.R;
import ceui.lisa.activities.SearchActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.UActivity;
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

    public static final String[] SEARCH_TYPE = new String[]{"标签搜作品", "ID搜作品", "关键字搜画师", "ID搜画师"};

    private ObservableEmitter<String> fuck = null;
    private int searchType = 0;

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_search;
    }

    @Override
    void initData() {
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
                    public void onNext(String s) {
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
                    fuck.onNext(key);
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
                    dispatchClick(baseBind.inputBox.getText().toString(), searchType);
                } else {
                    Common.showToast("请输入搜索内容");
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
                AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                builder.setItems(SEARCH_TYPE, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (searchType != which) {
                            baseBind.inputBox.setText("");
                            baseBind.inputBox.setHint(SEARCH_TYPE[which]);
                            searchType = which;
                        }
                    }
                });
                AlertDialog alertDialog = builder.create();
                alertDialog.show();
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
                Common.showToast("ID必须为全数字");
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
                Intent intent = new Intent(mContext, UActivity.class);
                intent.putExtra(Params.USER_ID, Integer.valueOf(keyWord));
                startActivity(intent);
            } else {
                Common.showToast("ID必须为全数字");
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
                .subscribe(new ErrorCtrl<ListTrendingtag>() {
                    @Override
                    public void onNext(ListTrendingtag listTrendingtag) {
                        if (listTrendingtag != null) {
                            baseBind.hotTags.setAdapter(new TagAdapter<ListTrendingtag.TrendTagsBean>(
                                    listTrendingtag.getList().subList(0, 15)) {
                                @Override
                                public View getView(FlowLayout parent, int position, ListTrendingtag.TrendTagsBean trendTagsBean) {
                                    TextView tv = (TextView) LayoutInflater.from(mContext).inflate(R.layout.recy_single_line_text,
                                            parent, false);
                                    tv.setText(trendTagsBean.getTag());
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
                    AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                    builder.setTitle("Shaft 提示");
                    builder.setMessage("这将会删除所有的本地搜索历史");
                    builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().deleteAll();
                            Common.showToast("搜索历史删除成功");
                            onResume();
                        }
                    });
                    builder.setNegativeButton("取消", null);
                    AlertDialog alertDialog = builder.create();
                    alertDialog.show();
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
                    Intent intent = new Intent(mContext, UActivity.class);
                    intent.putExtra(Params.USER_ID, Integer.valueOf(history.get(position).getKeyword()));
                    startActivity(intent);
                }
                return false;
            }
        });
    }
}
