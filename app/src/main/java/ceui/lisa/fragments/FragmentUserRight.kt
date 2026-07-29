package ceui.lisa.fragments

import android.content.Intent
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentUserRightBinding
import ceui.lisa.databinding.TagItemBinding
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.viewmodel.UserViewModel
import com.zhy.view.flowlayout.FlowLayout
import com.zhy.view.flowlayout.TagAdapter

class FragmentUserRight : BaseLazyFragment<FragmentUserRightBinding>() {

    private lateinit var mUserViewModel: UserViewModel

    override fun initLayout() {
        mLayoutID = R.layout.fragment_user_right
    }

    override fun initModel() {
        mUserViewModel = ViewModelProvider(mActivity).get(UserViewModel::class.java)
    }


    override fun initData() {
        val data = mUserViewModel.user.value ?: return
        val content: MutableList<String> = ArrayList()
        if (data.profile.total_illusts > 0) {
            content.add(getString(R.string.string_246) + ": " + data.profile.total_illusts)
        }
        if (data.profile.total_manga > 0) {
            content.add(getString(R.string.string_233) + ": " + data.profile.total_manga)
        }
        if (data.profile.total_illust_series > 0) {
            content.add(getString(R.string.string_230) +": " + data.profile.total_illust_series) //漫画系列
        }
        if (data.profile.total_novels > 0) {
            content.add(getString(R.string.string_237) + ": " + data.profile.total_novels)
        }
        if (data.profile.total_novel_series > 0) {
            content.add(getString(R.string.string_257)+ ": " + data.profile.total_novel_series)
        }
        // 插画/漫画收藏。不能拿 total_illust_bookmarks_public 当显示门控:pixiv 的
        // /v2/user/detail 只对「自己」返回这个字段,看别人时它整个不在 profile 里
        // (实测别人的 profile 字段止于 badge,压根没有这一项),Gson 落到 Models.kt 的默认 0,
        // 于是别人的主页永远看不到收藏入口。而收藏页走的是 /v1/user/bookmarks/illust,
        // 跟这个计数毫无关系,对任意 userId 都拉得到。
        // 跟同组的「小说收藏」「相关用户」以及 V3 的收藏 tab(UserActivityV3 里无条件 add)
        // 保持一致:入口恒显,计数只在拿得到(即看自己)时作为后缀补上。
        content.add(
            getString(R.string.string_164) +
                if (data.profile.total_illust_bookmarks_public > 0) {
                    ": " + data.profile.total_illust_bookmarks_public
                } else {
                    ""
                }
        )
        content.add(getString(R.string.string_192)) //小说收藏
        content.add(getString(R.string.string_436)) //相关用户
        baseBind.tagLayout.adapter = object : TagAdapter<String>(content) {
            override fun getView(parent: FlowLayout, position: Int, s: String?): View {
                val binding: TagItemBinding = DataBindingUtil.inflate(
                    LayoutInflater.from(mContext), R.layout.tag_item, null, false
                )
                binding.tagName.text = s
                return binding.root
            }
        }
//        baseBind.banUser.setOnCheckedChangeListener { buttonView, isChecked ->
//            if (isChecked) {
//                PixivOperate.muteUser(data.user)
//                mUserViewModel.isUserMuted.postValue(true)
//            } else {
//                PixivOperate.unMuteUser(data.user)
//                mUserViewModel.isUserMuted.postValue(false)
//            }
//        }
//        mUserViewModel.isUserMuted.observe(viewLifecycleOwner) { isMuted ->
//            baseBind.banUser.isChecked = isMuted == true
//        }
//        baseBind.banUserRela.setOnClickListener { baseBind.banUser.performClick() }
        baseBind.tagLayout.setOnTagClickListener { _, position, _ ->
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(Params.USER_ID, data.user.userId)
            when {
                content[position].contains(getString(R.string.string_246)) -> {
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "插画作品")
                }
                content[position].contains(getString(R.string.string_233)) -> {
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "漫画作品")
                }
                content[position].contains(getString(R.string.string_230)) -> {
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "漫画系列作品")
                }
                content[position].contains(getString(R.string.string_237)) -> {
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说作品")
                }
                content[position].contains(getString(R.string.string_257)) -> {
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说系列作品")
                }
                content[position].contains(getString(R.string.string_164)) -> {
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "插画/漫画收藏")
                }
                content[position].contains(getString(R.string.string_192)) -> {
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说收藏")
                }
                content[position].contains(getString(R.string.string_436)) -> {
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关用户")
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


}
