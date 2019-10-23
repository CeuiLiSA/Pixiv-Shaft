package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;
import android.widget.Button;

import ceui.lisa.R;
import ceui.lisa.activities.UserDetailActivity;
import ceui.lisa.adapters.UAdapter;
import ceui.lisa.databinding.RecyUserPreviewBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.FullClickListener;
import ceui.lisa.model.ListUserResponse;
import ceui.lisa.model.UserPreviewsBean;
import ceui.lisa.utils.PixivOperate;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

/**
 * 搜索用户
 */
public class FragmentSearchUser extends FragmentList<ListUserResponse, UserPreviewsBean, RecyUserPreviewBinding> {

    private String word;

    public static FragmentSearchUser newInstance(String w) {
        FragmentSearchUser searchUser = new FragmentSearchUser();
        searchUser.word = w;
        return searchUser;
    }

    @Override
    public String getToolbarTitle() {
        return "搜索用户 " + word;
    }

    @Override
    public Observable<ListUserResponse> initApi() {
        return Retro.getAppApi().searchUser(sUserModel.getResponse().getAccess_token(), word);
    }

    @Override
    public Observable<ListUserResponse> initNextApi() {
        return Retro.getAppApi().getNextUser(sUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    public void initAdapter() {
        mAdapter = new UAdapter(allItems, mContext);
        ((UAdapter) mAdapter).setFullClickListener(new FullClickListener() {
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
                        PixivOperate.postFollowUser(allItems.get(position).getUser().getId(), FragmentLikeIllust.TYPE_PUBLUC);
                        Button postFollow = ((Button) v);
                        postFollow.setText(getString(R.string.post_unfollow));
                    }
                }
            }

            @Override
            public void onItemLongClick(View v, int position, int viewType) {
                if (!allItems.get(position).getUser().isIs_followed()) {
                    PixivOperate.postFollowUser(allItems.get(position).getUser().getId(), FragmentLikeIllust.TYPE_PRIVATE);
                    Button postFollow = ((Button) v);
                    postFollow.setText(getString(R.string.post_unfollow));
                }
            }
        });
    }
}
