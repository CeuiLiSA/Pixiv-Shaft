package ceui.lisa.fragments

import android.content.Intent
import android.view.View
import android.widget.Button
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.UActivity
import ceui.lisa.adapters.UAdapter
import ceui.lisa.databinding.RecyUserPreviewBinding
import ceui.lisa.http.Retro
import ceui.lisa.interfaces.FullClickListener
import ceui.lisa.model.ListUserResponse
import ceui.lisa.model.UserPreviewsBean
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import io.reactivex.Observable

class FragmentNiceFriend : FragmentList<ListUserResponse, UserPreviewsBean, RecyUserPreviewBinding>() {

    override fun initApi(): Observable<ListUserResponse> {
        return Retro.getAppApi().getNiceFriend(Shaft.sUserModel.response.access_token,
                mActivity.intent.getIntExtra(Params.USER_ID, 0))
    }

    override fun initNextApi(): Observable<ListUserResponse> {
        return Retro.getAppApi().getNextUser(Shaft.sUserModel.response.access_token, nextUrl)
    }

    override fun initAdapter() {
        mAdapter = UAdapter(allItems, mContext)
        (mAdapter as UAdapter).setFullClickListener(object : FullClickListener {
            override fun onItemClick(v: View, position: Int, viewType: Int) {
                if (viewType == 0) { //普通item
                    val intent = Intent(mContext, UActivity::class.java)
                    intent.putExtra(Params.USER_ID, allItems[position].user.id)
                    startActivity(intent)
                } else if (viewType == 1) { //关注按钮
                    if (allItems[position].user.isIs_followed) {
                        PixivOperate.postUnFollowUser(allItems[position].user.id)
                        val postFollow = v as Button
                        postFollow.text = getString(R.string.post_follow)
                    } else {
                        PixivOperate.postFollowUser(allItems[position].user.id, FragmentLikeIllust.TYPE_PUBLUC)
                        val postFollow = v as Button
                        postFollow.text = getString(R.string.post_unfollow)
                    }
                }
            }

            override fun onItemLongClick(v: View, position: Int, viewType: Int) {
                if (!allItems[position].user.isIs_followed) {
                    PixivOperate.postFollowUser(allItems[position].user.id, FragmentLikeIllust.TYPE_PRIVATE)
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