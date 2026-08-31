package ceui.lisa.activities

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.ActivityNewUserBinding
import ceui.lisa.fragments.FragmentHolder.Companion.newInstance
import ceui.lisa.helper.UserIllustJumpHelper
import ceui.lisa.http.ErrorCtrl
import ceui.lisa.http.Retro
import ceui.lisa.interfaces.Display
import ceui.lisa.models.UserDetailResponse
import ceui.lisa.models.UserFollowDetail
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.SystemBarMetrics
import ceui.lisa.viewmodel.AppLevelState
import ceui.loxia.appServices
import ceui.lisa.viewmodel.UserViewModel
import ceui.loxia.Event
import ceui.loxia.ObjectPool
import ceui.loxia.ProgressIndicator
import ceui.loxia.ProgressTextButton
import ceui.loxia.User
import ceui.pixiv.actions.FollowVisibility
import ceui.pixiv.actions.PixivActions
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.user.UserShortcutHelper
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.FeedBackToTopFab
import com.bumptech.glide.Glide
import com.github.ybq.android.spinkit.style.Wave
import ceui.pixiv.witstudio.dialog.WitDialog.MenuDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ceui.pixiv.ui.navigation.TemplateRoute

class UActivity : BaseActivity<ActivityNewUserBinding>(), Display<UserDetailResponse> {
    private var userId = 0L
    private lateinit var mUserViewModel: UserViewModel
    override fun initLayout(): Int {
        return R.layout.activity_new_user
    }

    override fun initView() {
        // 列表右下角「回顶」悬浮钮(issue #1040,设置里默认关),与 V3 主页同款;
        // 装在 FragmentHolder 建 pager 之前,插画/漫画/收藏 tab 的 feeds 列表建好视图时自动挂上
        FeedBackToTopFab.installForHost(this, baseBind.appBar)
        val wave = Wave()
        baseBind.progress.indeterminateDrawable = wave
        baseBind.toolbar.setPadding(0, SystemBarMetrics.statusBarHeight(this), 0, 0)
        baseBind.toolbar.setNavigationOnClickListener { v: View? -> finish() }
        baseBind.toolbarLayout.viewTreeObserver.addOnGlobalLayoutListener(object :
            OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val offset =
                    baseBind.toolbarLayout.height - SystemBarMetrics.statusBarHeight(this@UActivity) - SystemBarMetrics.toolbarHeight(this@UActivity)
                baseBind.appBar.addOnOffsetChangedListener { appBarLayout, verticalOffset ->
                    if (Math.abs(verticalOffset) < 15) {
                        baseBind.centerHeader.alpha = 1.0f
                        baseBind.toolbarTitle.alpha = 0.0f
                    } else if (offset - Math.abs(verticalOffset) < 15) {
                        baseBind.centerHeader.alpha = 0.0f
                        baseBind.toolbarTitle.alpha = 1.0f
                    } else {
                        baseBind.centerHeader.alpha = 1 + verticalOffset.toFloat() / offset
                        baseBind.toolbarTitle.alpha = -verticalOffset.toFloat() / offset
                    }
                    Common.showLog(className + verticalOffset)
                }
                baseBind.toolbarLayout.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })
    }

    override fun initBundle(bundle: Bundle) {
        userId = Params.getUserId(bundle)
    }

    override fun initModel() {
        mUserViewModel = ViewModelProvider(this)[UserViewModel::class.java]
        mUserViewModel.user.observe(this) { userDetailResponse -> invoke(userDetailResponse) }
        // 屏蔽/拉黑标记走 Room。别在 onCreate 里同步查 —— 会阻塞主线程，一旦 Room 读连接池
        // 被后台的下载探测占满就 ANR（本页正是 ANR 现场）。挪到 IO 线程 postValue 回来。
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getAppDatabase(applicationContext).searchDao()
            val muted = dao.getUserMuteEntityByID(userId) != null
            val blocked = dao.getBlockMuteEntityByID(userId) != null
            mUserViewModel.isUserMuted.postValue(muted)
            mUserViewModel.isUserBlocked.postValue(blocked)
        }
        ObjectPool.get<User>(userId).observe(this) { user ->
            updateUser(user)
            Common.showLog("updateUser invoke ${user.is_followed}")
        }
        // 「怎么关的」是另一半事实，有自己的通知渠道。见 FollowVisibility.changes。
        FollowVisibility.changes.observe(this) { changed ->
            if (changed == userId) {
                ObjectPool.get<User>(userId).value?.let { updateUser(it) }
            }
        }
    }

    private fun updateUser(user: User) {
        if (user.is_followed == true) {
            baseBind.follow.isVisible = false
            baseBind.unfollow.isVisible = true
            baseBind.unfollow.text = getString(followedLabelRes(userId))
            baseBind.unfollow.setOnClick {
                unfollowUser(it, userId)
            }
            baseBind.unfollow.setOnLongClickListener {
                true
            }
        } else {
            baseBind.unfollow.isVisible = false
            baseBind.follow.isVisible = true
            baseBind.follow.setOnClick {
                followUser(it, userId, PixivActions.defaultFollowRestrict())
            }
            baseBind.follow.setOnLongClickListener {
                followUser(it as ProgressTextButton, userId, Params.TYPE_PRIVATE)
                true
            }
        }
    }

    /** 看的是自己：把服务端最新资料回写会话，侧边栏/“我的”头像跟着更新。 */
    private fun writeBackSelfProfile(userResponse: UserDetailResponse) {
        if (userId != SessionManager.loggedInUid) return
        SessionManager.ingestFreshUser(userResponse.user, userId)
    }

    override fun initData() {
        if (Shaft.sSettings.isUseArtworkV3) {
            val intent = Intent(mContext, UserActivityV3::class.java)
            intent.putExtra(Params.USER_ID, userId)
            startActivity(intent)
            finish()
            return
        }
        baseBind.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val userResponse = withContext(Dispatchers.IO) { Retro.getAppApi().getUserDetailV2(userId) }
                ObjectPool.updateUser(userResponse.user)
                mUserViewModel.user.value = userResponse
                writeBackSelfProfile(userResponse)
                runCatching {
                    (application as? ceui.loxia.ServicesProvider)?.entityWrapper?.visitUser(this@UActivity, userResponse.user)
                }
                appServices().appLevelState.updateFollowUserStatus(
                    userId,
                    if (userResponse.user.is_followed == true)
                        AppLevelState.FollowUserStatus.FOLLOWED
                    else
                        AppLevelState.FollowUserStatus.NOT_FOLLOW
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorCtrl.handleError(e)
            } finally {
                baseBind.progress.visibility = View.INVISIBLE
            }
        }
        lifecycleScope.launch {
            try {
                val userFollowDetail = withContext(Dispatchers.IO) { Retro.getAppApi().getFollowDetail(userId) }
                appServices().appLevelState.updateFollowUserStatus(userId, followStatusOf(userFollowDetail))
                // 本地动过的话 writeRemote 自己会丢弃；真写进去了它会发通知，重绘不用这里操心。
                FollowVisibility.writeRemote(userId, followRestrictOf(userFollowDetail))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorCtrl.handleError(e)
            }
        }
    }

    override fun hideStatusBar(): Boolean {
        return true
    }

    override operator fun invoke(data: UserDetailResponse) {
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.fragment_container, newInstance())
            .commitNowAllowingStateLoss()
        val isSelf = userId == SessionManager.loggedInUid
        if (isSelf) {
            baseBind.followLayout.visibility = View.GONE
        } else {
            baseBind.followLayout.visibility = View.VISIBLE
        }
        baseBind.moreAction.visibility = View.VISIBLE
        baseBind.moreAction.setOnClickListener { _: View? ->
            val isMuted = java.lang.Boolean.TRUE == mUserViewModel.isUserMuted.value
            val totalIllusts = data.profile.total_illusts
            val totalManga = data.profile.total_manga

            val labels = mutableListOf<String>()
            val actions = mutableListOf<() -> Unit>()

            if (totalIllusts > 0) {
                labels.add("跳转到插画…")
                actions.add { jumpTo(data.user.id, UserIllustJumpHelper.Kind.ILLUST, TemplateRoute.USER_ILLUSTS) }
            }
            if (totalManga > 0) {
                labels.add("跳转到漫画…")
                actions.add { jumpTo(data.user.id, UserIllustJumpHelper.Kind.MANGA, TemplateRoute.USER_MANGA) }
            }
            // 与 V3 的「更多」菜单对齐：自己的页面也要能进相关用户和下载管理
            labels.add(getString(R.string.string_436)) // 相关用户
            actions.add {
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(Params.USER_ID, data.user.id)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.RELATED_USERS.key)
                startActivity(intent)
            }
            labels.add(getString(R.string.bulk_user_menu_open_download_manager))
            actions.add {
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.DOWNLOAD_MANAGER.key)
                startActivity(intent)
            }
            // issue #1027:把作者主页固定成桌面图标。桌面不支持时(部分三方 launcher)不摆这一项
            if (UserShortcutHelper.isSupported(mContext)) {
                labels.add(getString(R.string.add_to_home_screen))
                actions.add { UserShortcutHelper.pin(this, data.user) }
            }
            if (!isSelf) {
                labels.add(
                    if (isMuted) getString(R.string.cancel_block_this_users_work)
                    else getString(R.string.block_this_users_work)
                )
                actions.add {
                    if (isMuted) {
                        PixivOperate.unMuteUser(data.user)
                        mUserViewModel.isUserMuted.setValue(false)
                    } else {
                        PixivOperate.muteUser(data.user)
                        mUserViewModel.isUserMuted.setValue(true)
                    }
                    mUserViewModel.refreshEvent.setValue(Event(100, 0L))
                }
                // issue #959: pixiv 账号级「拉黑」,和上面那条纯本地的「屏蔽」是两回事,菜单里并列摆着。
                labels.add(getString(R.string.pixiv_block_menu))
                actions.add {
                    ceui.pixiv.ui.user.PixivBlockOperate.showBlockDialog(
                        this, data.user.id, data.user.name.orEmpty()
                    )
                }
            }
            if (labels.isEmpty()) return@setOnClickListener

            MenuDialogBuilder(mActivity)
                .addItems(labels.toTypedArray()) { dialog: DialogInterface, which: Int ->
                    dialog.dismiss()
                    actions.getOrNull(which)?.invoke()
                }
                .show()
        }
        baseBind.centerHeader.visibility = View.VISIBLE
        val animation: Animation = AlphaAnimation(0.0f, 1.0f)
        animation.duration = 800L
        baseBind.centerHeader.startAnimation(animation)
        if (data.user.is_premium == true) {
            baseBind.vipImage.visibility = View.VISIBLE
        } else {
            baseBind.vipImage.visibility = View.GONE
        }
        val bannerUrl = data.profile.background_image_url
        if (!bannerUrl.isNullOrEmpty()) {
            Glide.with(mContext).load(GlideUtil.getUrl(bannerUrl)).into(baseBind.imageview)
            baseBind.bannerOverlay.visibility = View.VISIBLE
            baseBind.imageview.setOnClickListener {
                openImageDetail(bannerUrl, "user_${data.user.id}_profile_banner")
            }
        }
        Glide.with(mContext).load(GlideUtil.getHead(data.user)).into(baseBind.userHead)
        val avatarUrl = data.user.profile_image_urls?.findMaxSizeUrl()
        if (!avatarUrl.isNullOrEmpty()) {
            baseBind.userHead.setOnClickListener {
                openImageDetail(avatarUrl, "user_${data.user.id}_avatar")
            }
        }
        baseBind.userName.text = data.user.name
        baseBind.userName.setOnClickListener { Common.copy(mContext, data.user.id.toString()) }
        baseBind.userName.setOnLongClickListener {
            Common.copy(mContext, data.user.name)
            true
        }
        baseBind.followCount.text = data.profile.total_follow_users.toString()
        baseBind.pFriend.text = data.profile.total_mypixiv_users.toString()
        val pFriend = View.OnClickListener {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(Params.USER_ID, data.user.id)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NICE_FRIENDS.key)
            startActivity(intent)
        }
        baseBind.pFriend.setOnClickListener(pFriend)
        baseBind.pFriendS.setOnClickListener(pFriend)
        val follow = View.OnClickListener {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(Params.USER_ID, data.user.id)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.FOLLOWING.key)
            startActivity(intent)
        }
        baseBind.followCount.setOnClickListener(follow)
        baseBind.followS.setOnClickListener(follow)
    }

    private fun openImageDetail(imageUrl: String, saveName: String) {
        startActivity(Intent(mContext, TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.IMAGE_DETAIL.key)
            putExtra(Params.URL, imageUrl)
            putExtra(Params.TITLE, saveName)
        })
    }

    private fun jumpTo(userID: Long, kind: UserIllustJumpHelper.Kind, route: TemplateRoute) {
        UserIllustJumpHelper.showJumpDialog(this, userID, kind) { offset, pickedDate ->
            if (isFinishing || isDestroyed) return@showJumpDialog
            val intent = Intent(this, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, route.key)
            intent.putExtra(Params.USER_ID, userID)
            intent.putExtra(Params.INITIAL_OFFSET, offset)
            if (pickedDate != null) intent.putExtra(Params.TARGET_DATE, pickedDate)
            startActivity(intent)
        }
    }
}

fun Fragment.followUser(sender: ProgressIndicator, userId: Long, followType: String) {
    activity?.followUser(sender, userId, followType)
}

/**
 * 关注。本地状态立刻生效，真正的请求由 [PixivActionQueue] 限流后发出。
 *
 * 刻意不弹「关注成功」：这一刻请求还没发出去 —— 队列可能正在 429 冷却里，也可能因为
 * 登录态失效被闸门挡着，此时报成功就是在骗用户，而几分钟后队列打满重试还会再补一个
 * 「操作失败」的 toast 自相矛盾。反馈由按钮本身的关注态承担，失败时队列会把它拨回去。
 *
 * 埋点也由队列在服务端确认之后再发（见 PixivActionQueue.report），所以这里不再需要先
 * 把 User 取到手 —— 原先那次 getUserProfile 等待期间页面被销毁的话，关注意图会跟着丢掉。
 *
 * [sender] 已经没有可等的异步过程了，保留只为不改这个函数在六个调用点上的签名。
 */
fun FragmentActivity.followUser(sender: ProgressIndicator, userId: Long, followType: String) {
    PixivActions.setUserFollow(
        userId = userId,
        follow = true,
        restrict = followType,
    )
}

fun Fragment.unfollowUser(sender: ProgressIndicator, userId: Long) {
    activity?.unfollowUser(sender, userId)
}

/** 取关。语义与 [followUser] 完全对称，同样不弹「已取消关注」。 */
fun FragmentActivity.unfollowUser(sender: ProgressIndicator, userId: Long) {
    PixivActions.setUserFollow(
        userId = userId,
        follow = false,
    )
}

/**
 * 「已关注」按钮的文案：私人关注要能和公开关注区分开（issue #997）。
 *
 * pixiv 的 user/detail 只给 `is_followed` 这个 bool，「怎么关的」得单独拿 —— 见
 * [FollowVisibility]。开了「关注作者默认私人关注」之后短按也可能是私密的，而 4.8.4 起关注成功
 * toast 已经删掉（那一刻请求还没发出去，报成功是骗人），按钮就成了唯一的出口。
 *
 * 不知道就回落「已关注」：宁可少报私密，也不能把公开关注说成私人的。
 */
@StringRes
fun followedLabelRes(userId: Long): Int =
    if (FollowVisibility.isPrivate(userId)) {
        R.string.user_followed_private
    } else {
        R.string.user_followed
    }

/** user/follow/detail 响应 → [AppLevelState.FollowUserStatus]。V2/V3 画师主页共用。 */
fun followStatusOf(followDetail: UserFollowDetail): Int = when {
    followDetail.isPublicFollow -> AppLevelState.FollowUserStatus.FOLLOWED_PUBLIC
    followDetail.isPrivateFollow -> AppLevelState.FollowUserStatus.FOLLOWED_PRIVATE
    followDetail.isFollow -> AppLevelState.FollowUserStatus.FOLLOWED
    else -> AppLevelState.FollowUserStatus.NOT_FOLLOW
}

/** user/follow/detail 响应里的可见性；未关注时为 null。 */
fun followRestrictOf(followDetail: UserFollowDetail): String? = when {
    followDetail.isPrivateFollow -> Params.TYPE_PRIVATE
    followDetail.isFollow -> Params.TYPE_PUBLIC
    else -> null
}
