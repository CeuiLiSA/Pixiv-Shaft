package ceui.lisa.fragments

import android.content.Intent
import android.view.View
import android.widget.Button
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.UserDetailActivity
import ceui.lisa.adapters.UAdapter
import ceui.lisa.databinding.RecyUserPreviewBinding
import ceui.lisa.http.Retro
import ceui.lisa.interfaces.FullClickListener
import ceui.lisa.model.ListUserResponse
import ceui.lisa.model.UserPreviewsBean
import ceui.lisa.utils.DensityUtil
import ceui.lisa.utils.PixivOperate
import ceui.lisa.view.LinearItemDecoration
import io.reactivex.Observable

class FragmentNiceFriend: FragmentList<ListUserResponse, UserPreviewsBean, RecyUserPreviewBinding>() {

    override fun initApi(): Observable<ListUserResponse> {
        return Retro.getAppApi().getNiceFriend(Shaft.sUserModel.response.access_token,
                mActivity.intent.getIntExtra("user id", 0))
    }

    override fun initNextApi(): Observable<ListUserResponse> {
        return Retro.getAppApi().getNextUser(Shaft.sUserModel.response.access_token, nextUrl)
    }

    override fun initAdapter() {
        mAdapter = UAdapter(allItems, mContext)
        (mAdapter as UAdapter).setFullClickListener(object : FullClickListener {
            override fun onItemClick(v: View, position: Int, viewType: Int) {
                if (viewType == 0) { //普通item
                    val intent = Intent(mContext, UserDetailActivity::class.java)
                    intent.putExtra("user id", allItems[position].user.id)
                    startActivity(intent)
                } else if (viewType == 1) { //关注按钮
                    if (allItems[position].user.isIs_followed) {
                        PixivOperate.postUnFollowUser(allItems[position].user.id)
                        val postFollow = v as Button
                        postFollow.text = getString(R.string.post_follow)
                    } else {
                        PixivOperate.postFollowUser(allItems[position].user.id, "public")
                        val postFollow = v as Button
                        postFollow.text = getString(R.string.post_unfollow)
                    }
                }
            }

            override fun onItemLongClick(v: View, position: Int, viewType: Int) {
                if (!allItems[position].user.isIs_followed) {
                    PixivOperate.postFollowUser(allItems[position].user.id, "private")
                    val postFollow = v as Button
                    postFollow.text = getString(R.string.post_unfollow)
                }
            }
        })
    }

    override fun getToolbarTitle(): String {
        return "好P友"
    }
}