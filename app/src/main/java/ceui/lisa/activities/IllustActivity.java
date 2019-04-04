package ceui.lisa.activities;

import ceui.lisa.fragments.FragmentIllustList;

public class IllustActivity extends FragmentActivity<FragmentIllustList>
{

    @Override
    protected FragmentIllustList createNewFragment() {
        return new FragmentIllustList();
    }

    @Override
    protected void initData() {

    }
}
