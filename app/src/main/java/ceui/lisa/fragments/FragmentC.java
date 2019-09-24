package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;

import ceui.lisa.activities.UserDetailActivity;
import ceui.lisa.adapters.CAdapter;
import ceui.lisa.databinding.RecyCommentListBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.CommentsBean;
import ceui.lisa.model.IllustCommentsResponse;
import ceui.lisa.utils.Common;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentC extends FragmentList<IllustCommentsResponse, CommentsBean, RecyCommentListBinding> {

    private int illustID;
    private String title;

    public static FragmentC newInstance(int id, String title) {
        FragmentC comment = new FragmentC();
        comment.illustID = id;
        comment.title = title;
        return comment;
    }

    @Override
    public Observable<IllustCommentsResponse> initApi() {
        return Retro.getAppApi().getComment(sUserModel.getResponse().getAccess_token(), illustID);
    }

    @Override
    public Observable<IllustCommentsResponse> initNextApi() {
        return Retro.getAppApi().getNextComment(sUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    public void initAdapter() {
        mAdapter = new CAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener((v, position, viewType) -> {
            if (viewType == 0) {
                Common.copy(mContext, allItems.get(position).getComment());
            } else if (viewType == 1) {
                Intent userIntent = new Intent(mContext, UserDetailActivity.class);
                userIntent.putExtra("user id", allItems.get(position).getUser().getId());
                startActivity(userIntent);
            } else if (viewType == 2) {
                Common.copy(mContext, allItems.get(position).getParent_comment().getComment());
            } else if (viewType == 3) {
                Intent userIntent = new Intent(mContext, UserDetailActivity.class);
                userIntent.putExtra("user id", allItems.get(position).getParent_comment().getUser().getId());
                startActivity(userIntent);
            }
        });
    }

    @Override
    public String getToolbarTitle() {
        return title + "的评论";
    }

    @Override
    public void initRecyclerView() {
        baseBind.recyclerView.setLayoutManager(new LinearLayoutManager(mContext));
    }
}
