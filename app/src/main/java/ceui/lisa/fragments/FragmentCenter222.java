package ceui.lisa.fragments;

import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentTransaction;
import androidx.recyclerview.widget.RecyclerView;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.mancj.materialsearchbar.MaterialSearchBar;
import com.mancj.materialsearchbar.adapter.SuggestionsAdapter;

import java.util.concurrent.TimeUnit;

import ceui.lisa.R;
import ceui.lisa.activities.RankActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateFragmentActivity;
import ceui.lisa.activities.UserDetailActivity;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.PartCompleteWords;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.PixivOperate;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentCenter222 extends BaseFragment {

    private boolean isLoad = false;
    private MaterialSearchBar mSearchBar;
    private int searchType = 0;
    private ObservableEmitter<String> fuck = null;
    private SuggestionsAdapter<String, TagHolder> mAdapter = null;


    public FragmentCenter222() {
    }

    public static boolean isNumeric(String str) {
        for (int i = str.length(); --i >= 0; ) {
            if (!Character.isDigit(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_center;
    }

    @Override
    View initView(View v) {
        ImageView head = v.findViewById(R.id.head);
        ViewGroup.LayoutParams headParams = head.getLayoutParams();
        headParams.height = Shaft.statusHeight;
        head.setLayoutParams(headParams);

        mSearchBar = v.findViewById(R.id.searchBar);
        mSearchBar.setMaxSuggestionCount(5);
        mSearchBar.inflateMenu(R.menu.search_menu);
        mSearchBar.getMenu().setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case R.id.action_1:
                    if (searchType != 0) {
                        mSearchBar.setPlaceHolder("标签搜作品");
                        searchType = 0;
                    }
                    break;
                case R.id.action_2:
                    if (searchType != 1) {
                        mSearchBar.setPlaceHolder("ID搜作品");
                        searchType = 1;
                    }
                    break;
                case R.id.action_3:
                    if (searchType != 2) {
                        mSearchBar.setPlaceHolder("关键字搜画师");
                        searchType = 2;
                    }
                    break;
                case R.id.action_4:
                    if (searchType != 3) {
                        mSearchBar.setPlaceHolder("ID搜画师");
                        searchType = 3;
                    }
                    break;
                default:
                    break;
            }
            return true;
        });
        mSearchBar.setOnSearchActionListener(new MaterialSearchBar.OnSearchActionListener() {
            @Override
            public void onSearchStateChanged(boolean enabled) {
            }

            @Override
            public void onSearchConfirmed(CharSequence text) {
                String keyWord = String.valueOf(text);
                if (!TextUtils.isEmpty(keyWord)) {

                    if (searchType == 0) {
                        Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                        intent.putExtra(TemplateFragmentActivity.EXTRA_KEYWORD, keyWord);
                        intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT,
                                "搜索配件");
                        startActivity(intent);
                    } else if (searchType == 1) {
                        if (isNumeric(keyWord)) {
                            PixivOperate.getIllustByID(sUserModel, Integer.valueOf(keyWord), mContext);
                        } else {
                            Common.showToast("ID必须为全数字");
                        }
                    } else if (searchType == 2) {
                        Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                        intent.putExtra(TemplateFragmentActivity.EXTRA_KEYWORD,
                                keyWord);
                        intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT,
                                "搜索用户");
                        startActivity(intent);
                    } else if (searchType == 3) {
                        if (isNumeric(keyWord)) {
                            Intent intent = new Intent(mContext, UserDetailActivity.class);
                            intent.putExtra("user id", Integer.valueOf(keyWord));
                            startActivity(intent);
                        } else {
                            Common.showToast("ID必须为全数字");
                        }
                    }
                } else {
                    Common.showToast("请输入关键字");
                }
            }

            @Override
            public void onButtonClicked(int buttonCode) {

            }
        });
        TextView textView = v.findViewById(R.id.see_more);
        textView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, RankActivity.class);
                startActivity(intent);
            }
        });

        FragmentRankHorizontal fragmentRankHorizontal = new FragmentRankHorizontal();
        FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
        transaction.add(R.id.fragment_container, fragmentRankHorizontal).commit();
        return v;
    }

    @Override
    void initData() {
        Observable.create(new ObservableOnSubscribe<String>() {
            @Override
            public void subscribe(ObservableEmitter<String> emitter) throws Exception {
                fuck = emitter;
            }
        }).subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .debounce(2000, TimeUnit.MILLISECONDS)
                .subscribe(new Observer<String>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(String s) {
                        completeWord(String.valueOf(s));
                    }

                    @Override
                    public void onError(Throwable e) {

                    }

                    @Override
                    public void onComplete() {

                    }
                });
        mSearchBar.getSearchEditText().addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (searchType == 0) {
                    String key = String.valueOf(s);
                    if (key.length() != 0) {
                        fuck.onNext(key);
                    } else {
                        mSearchBar.hideSuggestionsList();
                    }
                }
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });
    }

    private void completeWord(String key) {
        Retro.getPartApi().inputHelp(key)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ErrorCtrl<PartCompleteWords>() {
                    @Override
                    public void onNext(PartCompleteWords trendingtagResponse) {
                        if (trendingtagResponse != null) {
                            if (trendingtagResponse.getList() != null && trendingtagResponse.getList().size() != 0) {

                                mAdapter = new SuggestionsAdapter<String, TagHolder>(LayoutInflater.from(mContext)) {

                                    @Override
                                    public void onBindSuggestionHolder(String suggestion, TagHolder holder, int position) {
                                        holder.tag.setText(TextUtils.isEmpty(suggestion) ?
                                                " " : suggestion);
                                        Common.showLog("onBindSuggestionHolder " + position);
                                        holder.itemView.setOnClickListener(new View.OnClickListener() {
                                            @Override
                                            public void onClick(View v) {
                                                Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                                                intent.putExtra(TemplateFragmentActivity.EXTRA_KEYWORD, suggestion);
                                                intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT,
                                                        "搜索配件");
                                                startActivity(intent);
                                            }
                                        });
                                    }

                                    @Override
                                    public int getSingleViewHeight() {
                                        return 55;
                                    }

                                    @NonNull
                                    @Override
                                    public TagHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int i) {
                                        View view = this.getLayoutInflater().inflate(R.layout.recy_tag_item, viewGroup, false);
                                        return new TagHolder(view);
                                    }
                                };
                                mSearchBar.setMaxSuggestionCount(5);
                                mAdapter.setSuggestions(trendingtagResponse.getList());
                                mSearchBar.setMaxSuggestionCount(5);
                                mSearchBar.setCustomSuggestionAdapter(mAdapter);
                                mSearchBar.setMaxSuggestionCount(5);
                                mSearchBar.showSuggestionsList();
                                mSearchBar.setMaxSuggestionCount(5);
                            } else {
                                Common.showLog("无自动填充列表");
                            }
                        } else {
                            Common.showToast("解析返回值错误");
                        }
                    }
                });
    }

    @Override
    public void onStop() {
        super.onStop();

    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);

        if (isVisibleToUser && !isLoad) {
            FragmentPivisionHorizontal fragmentPivision = new FragmentPivisionHorizontal();
            FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
            transaction.add(R.id.fragment_pivision, fragmentPivision).commit();
            isLoad = true;
        }
    }

    private static class TagHolder extends RecyclerView.ViewHolder {

        private TextView tag;


        public TagHolder(@NonNull View itemView) {
            super(itemView);
            tag = itemView.findViewById(R.id.tag);
        }
    }
}
