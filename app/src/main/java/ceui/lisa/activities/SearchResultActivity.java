package ceui.lisa.activities;

import android.content.Intent;

import ceui.lisa.fragments.FragmentSearchResult;

public class SearchResultActivity extends FragmentActivity<FragmentSearchResult> {

    @Override
    protected FragmentSearchResult createNewFragment() {
        Intent intent = getIntent();
        String keyWord = intent.getStringExtra("key word");
        return FragmentSearchResult.newInstance(keyWord);
    }

    @Override
    protected void initData() {

    }
}
