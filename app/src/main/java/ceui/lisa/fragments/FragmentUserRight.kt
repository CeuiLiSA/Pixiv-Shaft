package ceui.lisa.fragments

import android.content.Intent
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentUserRightBinding
import ceui.lisa.databinding.TagItemBinding
import ceui.lisa.utils.Params
import ceui.lisa.viewmodel.UserViewModel
import com.scwang.smartrefresh.layout.SmartRefreshLayout
import com.zhy.view.flowlayout.FlowLayout
import com.zhy.view.flowlayout.TagAdapter

class FragmentUserRight : SwipeFragment<FragmentUserRightBinding>() {

    private lateinit var mUserViewModel: UserViewModel

    override fun initLayout() {
        mLayoutID = R.layout.fragment_user_right
    }

    override fun initModel() {
        mUserViewModel = ViewModelProvider(mActivity).get(UserViewModel::class.java)
    }

    override fun getSmartRefreshLayout(): SmartRefreshLayout {
        return baseBind.refreshLayout
    }

    override fun initData() {
        val data = mUserViewModel.user.value ?: return
        val content: MutableList<String> = ArrayList()
        if (data.profile.total_illusts > 0) {
            content.add("插画作品：" + data.profile.total_illusts)
        }
        if (data.profile.total_manga > 0) {
            content.add("漫画作品：" + data.profile.total_manga)
        }
        if (data.profile.total_illust_series > 0) {
            content.add("漫画系列：" + data.profile.total_illust_series)
        }
        if (data.profile.total_novels > 0) {
            content.add("小说作品：" + data.profile.total_novels)
        }
        if (data.profile.total_novel_series > 0) {
            content.add("小说系列：" + data.profile.total_novel_series)
        }
        if (data.profile.total_illust_bookmarks_public > 0) {
            content.add("插画/漫画收藏：" + data.profile.total_illust_bookmarks_public)
        }
        content.add("小说收藏")
        baseBind.tagLayout.adapter = object : TagAdapter<String>(content) {
            override fun getView(parent: FlowLayout, position: Int, s: String?): View {
                val binding: TagItemBinding = DataBindingUtil.inflate(
                    LayoutInflater.from(mContext), R.layout.tag_item, null, false
                )
                binding.tagName.text = s
                return binding.root
            }
        }
        baseBind.tagLayout.setOnTagClickListener { view, position, parent ->
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(Params.USER_ID, data.user.userId)
            when {
                content[position].contains("插画作品") -> {
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "插画作品")
                }
                content[position].contains("漫画作品") -> {
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "漫画作品")
                }
                content[position].contains("漫画系列") -> {
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "漫画系列作品")
                }
                content[position].contains("小说作品") -> {
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说作品")
                }
                content[position].contains("小说系列") -> {
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说系列作品")
                }
                content[position].contains("插画/漫画收藏") -> {
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "插画/漫画收藏")
                }
                content[position].contains("小说收藏") -> {
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说收藏")
                }
            }
            startActivity(intent)
            true
        }
        if (!TextUtils.isEmpty(data.user.comment)) {
            baseBind.comment.visibility = View.VISIBLE
            baseBind.comment.text = data.user.comment
        } else {
            baseBind.comment.visibility = View.GONE
        }

        baseBind.showDetail.setOnClickListener {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "详细信息")
            intent.putExtra(Params.CONTENT, data)
            startActivity(intent)
        }
        if (!TextUtils.isEmpty(data.profile.webpage)) {
            baseBind.realHome.text = data.profile.webpage
        } else {
            baseBind.realHome.text = "https://www.pixiv.net/users/%d".format(data.user.id)
        }
        if (!TextUtils.isEmpty(data.profile.twitter_url)) {
            baseBind.realTwitter.text = data.profile.twitter_url
        } else {
            baseBind.realTwitter.text = getString(R.string.no_info)
        }
        if (!TextUtils.isEmpty(data.profile.region)) {
            baseBind.realAddress.text = data.profile.region
        } else {
            baseBind.realAddress.text = getString(R.string.no_info)
        }
        if (!TextUtils.isEmpty(data.profile.content)) {
            baseBind.realJob.text = data.profile.content
        } else {
            baseBind.realJob.text = getString(R.string.no_info)
        }
    }

    override fun enableLoadMore(): Boolean {
        return false
    }

    override fun enableRefresh(): Boolean {
        return false
    }
}
