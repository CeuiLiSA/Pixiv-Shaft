package ceui.lisa.fragments;

import android.support.v7.widget.LinearLayoutManager;

import com.scwang.smartrefresh.layout.util.DensityUtil;

import ceui.lisa.R;
import ceui.lisa.adapters.CommentAdapter;
import ceui.lisa.http.Retro;
import ceui.lisa.response.CommentsBean;
import ceui.lisa.response.IllustCommentsResponse;
import ceui.lisa.view.LinearItemDecoration;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.mUserModel;

public class FragmentComment extends BaseListFragment<IllustCommentsResponse, CommentAdapter, CommentsBean> {

    private int illustID;
    private String title;

    public static FragmentComment newInstance(int id, String title){
        FragmentComment comment = new FragmentComment();
        comment.illustID = id;
        comment.title = title;
        return comment;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_illust_list;
    }

    @Override
    void initRecyclerView() {
        super.initRecyclerView();
        LinearLayoutManager manager = new LinearLayoutManager(mContext);
        mRecyclerView.setLayoutManager(manager);
    }

    @Override
    String getToolbarTitle() {
        return title + "的评论";
    }

    @Override
    Observable<IllustCommentsResponse> initApi() {
        return Retro.getAppApi().getComment(mUserModel.getResponse().getAccess_token(), illustID);
    }

    @Override
    Observable<IllustCommentsResponse> initNextApi() {
        return Retro.getAppApi().getNextComment(mUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    void initAdapter() {
        mAdapter = new CommentAdapter(allItems, mContext);
    }
}
