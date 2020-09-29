package ceui.lisa.fragments;

import android.os.Bundle;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyNovelBinding;
import ceui.lisa.model.ListNovel;
import ceui.lisa.models.NovelBean;
import ceui.lisa.repo.UserNovelRepo;
import ceui.lisa.utils.Params;

/**
 * 某人创作的小说
 */
public class FragmentUserNovel extends NetListFragment<FragmentBaseListBinding,
        ListNovel, NovelBean> {

    private int userID;

    public static FragmentUserNovel newInstance(int userID) {
        Bundle args = new Bundle();
        args.putInt(Params.USER_ID, userID);
        FragmentUserNovel fragment = new FragmentUserNovel();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        userID = bundle.getInt(Params.USER_ID);
    }

    @Override
    public RemoteRepo<ListNovel> repository() {
        return new UserNovelRepo(userID);
    }

    @Override
    public BaseAdapter<NovelBean, RecyNovelBinding> adapter() {
        return new NAdapter(allItems, mContext);
    }

    @Override
    public String getToolbarTitle() {
        return getString(R.string.string_237);
    }
}
