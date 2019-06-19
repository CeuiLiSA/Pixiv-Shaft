package ceui.lisa.fragments;

import android.content.Intent;
import android.support.v7.widget.LinearLayoutManager;
import android.view.View;
import android.widget.Button;

import com.scwang.smartrefresh.layout.util.DensityUtil;

import ceui.lisa.R;
import ceui.lisa.activities.UserDetailActivity;
import ceui.lisa.adapters.UserAdapter;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.FullClickListener;
import ceui.lisa.response.ListUserResponse;
import ceui.lisa.response.UserPreviewsBean;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.view.LinearItemDecoration;
import io.reactivex.Observable;

/**
 * 搜索用户
 */
public class FragmentSearchUser extends BaseListFragment<ListUserResponse, UserAdapter, UserPreviewsBean> {

    private String word;

    public static FragmentSearchUser newInstance(String w){
        FragmentSearchUser searchUser = new FragmentSearchUser();
        searchUser.word = w;
        return searchUser;
    }

    @Override
    Observable<ListUserResponse> initApi() {
        return Retro.getAppApi().searchUser(mUserModel.getResponse().getAccess_token(), word);
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_illust_list;
    }

    @Override
    String getToolbarTitle() {
        return "搜索用户 " + word;
    }

    @Override
    void initRecyclerView() {
        super.initRecyclerView();
        mRecyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(8.0f)));
        LinearLayoutManager manager = new LinearLayoutManager(mContext);
        mRecyclerView.setLayoutManager(manager);
    }

    @Override
    Observable<ListUserResponse> initNextApi() {
        return Retro.getAppApi().getNextUser(mUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    void initAdapter() {
        mAdapter = new UserAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener(new FullClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                if (viewType == 0) { //普通item
                    Intent intent = new Intent(mContext, UserDetailActivity.class);
                    intent.putExtra("user id", allItems.get(position).getUser().getId());
                    startActivity(intent);
                } else if (viewType == 1) { //关注按钮
                    if (allItems.get(position).getUser().isIs_followed()) {
                        PixivOperate.postUnFollowUser(allItems.get(position).getUser().getId());
                        Button postFollow = ((Button) v);
                        postFollow.setText(getString(R.string.post_follow));
                    } else {
                        PixivOperate.postFollowUser(allItems.get(position).getUser().getId(), "public");
                        Button postFollow = ((Button) v);
                        postFollow.setText(getString(R.string.post_unfollow));
                    }
                }
            }

            @Override
            public void onItemLongClick(View v, int position, int viewType) {
                if(!allItems.get(position).getUser().isIs_followed()){
                    PixivOperate.postFollowUser(allItems.get(position).getUser().getId(), "private");
                    Button postFollow = ((Button) v);
                    postFollow.setText(getString(R.string.post_unfollow));
                }
            }
        });
    }
}
