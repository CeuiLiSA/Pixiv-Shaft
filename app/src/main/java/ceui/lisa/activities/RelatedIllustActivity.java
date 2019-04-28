package ceui.lisa.activities;

import android.content.Intent;

import ceui.lisa.fragments.FragmentRelatedIllust;

public class RelatedIllustActivity extends FragmentActivity<FragmentRelatedIllust> {

    @Override
    protected FragmentRelatedIllust createNewFragment() {
        Intent intent = getIntent();
        int id = intent.getIntExtra("illust id", 0);
        String title = intent.getStringExtra("illust title");
        return FragmentRelatedIllust.newInstance(id, title);
    }
}
