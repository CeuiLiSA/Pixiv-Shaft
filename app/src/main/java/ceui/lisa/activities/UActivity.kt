package ceui.lisa.activities

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
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
import ceui.lisa.http.NullCtrl
import ceui.lisa.http.Retro
import ceui.lisa.interfaces.Display
import ceui.lisa.models.UserBean
import ceui.lisa.models.UserDetailResponse
import ceui.lisa.models.UserFollowDetail
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.viewmodel.AppLevelViewModel
import ceui.lisa.viewmodel.UserViewModel
import ceui.loxia.Client
import ceui.loxia.Event
import ceui.loxia.ObjectPool
import ceui.loxia.ProgressIndicator
import ceui.loxia.ProgressTextButton
import ceui.pixiv.session.SessionManager
import ceui.pixiv.widgets.RateAppManager
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.github.ybq.android.spinkit.style.Wave
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog.MenuDialogBuilder
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

class UActivity : BaseActivity<ActivityNewUserBinding>(), Display<UserDetailResponse> {
    private var userId = 0
    private lateinit var mUserViewModel: UserViewModel
    override fun initLayout(): Int {
        return R.layout.activity_new_user
    }

    override fun initView() {
        val wave = Wave()
        baseBind.progress.indeterminateDrawable = wave
        baseBind.toolbar.setPadding(0, Shaft.statusHeight, 0, 0)
        baseBind.toolbar.setNavigationOnClickListener { v: View? -> finish() }
        baseBind.toolbarLayout.viewTreeObserver.addOnGlobalLayoutListener(object :
            OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val offset =
                    baseBind.toolbarLayout.height - Shaft.statusHeight - Shaft.toolbarHeight
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
        userId = bundle.getInt(Params.USER_ID)
    }

    override fun initModel() {
        mUserViewModel = ViewModelProvider(this)[UserViewModel::class.java]
        mUserViewModel.user.observe(this) { userDetailResponse -> invoke(userDetailResponse) }
        val entity = AppDatabase.getAppDatabase(this).searchDao().getUserMuteEntityByID(userId)
        mUserViewModel.isUserMuted.value = entity != null
        val block = AppDatabase.getAppDatabase(this).searchDao().getBlockMuteEntityByID(userId)
        mUserViewModel.isUserBlocked.value = block != null
        ObjectPool.get<UserBean>(userId.toLong()).observe(this) { user ->
            updateUser(user)
            Common.showLog("updateUser invoke ${user.isIs_followed}")
        }
    }

    private fun updateUser(user: UserBean) {
        if (user.isIs_followed) {
            baseBind.follow.isVisible = false
            baseBind.unfollow.isVisible = true
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
                followUser(it, userId, Params.TYPE_PUBLIC)
            }
            baseBind.follow.setOnLongClickListener {
                followUser(it as ProgressTextButton, userId, Params.TYPE_PRIVATE)
                true
            }
        }
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
        Retro.getAppApi().getUserDetail(userId)
            .subscribeOn(Schedulers.newThread())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : NullCtrl<UserDetailResponse>() {
                override fun success(userResponse: UserDetailResponse) {
                    ObjectPool.updateUser(userResponse.user)
                    mUserViewModel.user.value = userResponse
                    Shaft.appViewModel.updateFollowUserStatus(
                        userId,
                        if (userResponse.user.isIs_followed)
                            AppLevelViewModel.FollowUserStatus.FOLLOWED
                        else
                            AppLevelViewModel.FollowUserStatus.NOT_FOLLOW
                    )
                }

                override fun must() {
                    baseBind.progress.visibility = View.INVISIBLE
                }
            })
        Retro.getAppApi().getFollowDetail(userId)
            .subscribeOn(Schedulers.newThread())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : NullCtrl<UserFollowDetail>() {
                override fun success(userFollowDetail: UserFollowDetail) {
                    //mUserViewModel.getUserFollowDetail().setValue(userFollowDetail);
                    var followStatus = AppLevelViewModel.FollowUserStatus.NOT_FOLLOW
                    if (userFollowDetail.isPublicFollow) {
                        followStatus = AppLevelViewModel.FollowUserStatus.FOLLOWED_PUBLIC
                    } else if (userFollowDetail.isPrivateFollow) {
                        followStatus = AppLevelViewModel.FollowUserStatus.FOLLOWED_PRIVATE
                    } else if (userFollowDetail.isFollow) {
                        followStatus = AppLevelViewModel.FollowUserStatus.FOLLOWED
                    }
                    Shaft.appViewModel.updateFollowUserStatus(userId, followStatus)
                }
            })
    }

    override fun hideStatusBar(): Boolean {
        return true
    }

    override operator fun invoke(data: UserDetailResponse) {
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.fragment_container, newInstance())
            .commitNowAllowingStateLoss()
        val isSelf = userId.toLong() == SessionManager.loggedInUid
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
                actions.add { jumpTo(data.user.id, UserIllustJumpHelper.Kind.ILLUST, "插画作品") }
            }
            if (totalManga > 0) {
                labels.add("跳转到漫画…")
                actions.add { jumpTo(data.user.id, UserIllustJumpHelper.Kind.MANGA, "漫画作品") }
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
            }
            if (labels.isEmpty()) return@setOnClickListener

            MenuDialogBuilder(mActivity)
                .setSkinManager(QMUISkinManager.defaultInstance(mActivity))
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
        if (data.user.isIs_premium) {
            baseBind.vipImage.visibility = View.VISIBLE
        } else {
            baseBind.vipImage.visibility = View.GONE
        }
        val bannerUrl = data.profile.background_image_url
        if (!bannerUrl.isNullOrEmpty()) {
            Glide.with(mContext).load(GlideUtil.getUrl(bannerUrl)).into(baseBind.imageview)
            baseBind.bannerOverlay.visibility = View.VISIBLE
        }
        Glide.with(mContext).load(GlideUtil.getHead(data.user)).into(baseBind.userHead)
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
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "好P友")
            startActivity(intent)
        }
        baseBind.pFriend.setOnClickListener(pFriend)
        baseBind.pFriendS.setOnClickListener(pFriend)
        val follow = View.OnClickListener {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(Params.USER_ID, data.user.id)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "正在关注")
            startActivity(intent)
        }
        baseBind.followCount.setOnClickListener(follow)
        baseBind.followS.setOnClickListener(follow)
    }

    private fun jumpTo(userID: Int, kind: UserIllustJumpHelper.Kind, fragmentTag: String) {
        UserIllustJumpHelper.showJumpDialog(this, userID, kind) { offset, pickedDate ->
            if (isFinishing || isDestroyed) return@showJumpDialog
            val intent = Intent(this, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, fragmentTag)
            intent.putExtra(Params.USER_ID, userID)
            intent.putExtra(Params.INITIAL_OFFSET, offset)
            if (pickedDate != null) intent.putExtra(Params.TARGET_DATE, pickedDate)
            startActivity(intent)
        }
    }
}

fun Fragment.followUser(sender: ProgressIndicator, userId: Int, followType: String) {
    activity?.followUser(sender, userId, followType)
}

fun FragmentActivity.followUser(sender: ProgressIndicator, userId: Int, followType: String) {
    lifecycleScope.launch {
        try {
            val pendingFollowType = if (Shaft.sSettings.isPrivateStar) {
                Params.TYPE_PRIVATE
            } else {
                followType
            }
            sender.showProgress()
            Client.appApi.postFollow(userId.toLong(), pendingFollowType)
            RateAppManager.onUserEngaged()
            delay(500L)
            ObjectPool.followUser(userId.toLong())
            if (pendingFollowType == Params.TYPE_PUBLIC) {
                Shaft.appViewModel.updateFollowUserStatus(
                    userId,
                    AppLevelViewModel.FollowUserStatus.FOLLOWED_PUBLIC
                )
                Common.showToast(getString(R.string.like_success_public))
            } else {
                Shaft.appViewModel.updateFollowUserStatus(
                    userId,
                    AppLevelViewModel.FollowUserStatus.FOLLOWED_PRIVATE
                )
                Common.showToast(getString(R.string.like_success_private))
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            Common.showToast(ex.message)
        } finally {
            sender.hideProgress()
        }
    }
}

fun Fragment.unfollowUser(sender: ProgressIndicator, userId: Int) {
    activity?.unfollowUser(sender, userId)
}

fun FragmentActivity.unfollowUser(sender: ProgressIndicator, userId: Int) {
    lifecycleScope.launch {
        try {
            sender.showProgress()
            Client.appApi.postUnFollow(userId.toLong())
            delay(500L)
            Shaft.appViewModel.updateFollowUserStatus(
                userId,
                AppLevelViewModel.FollowUserStatus.NOT_FOLLOW
            )
            ObjectPool.unFollowUser(userId.toLong())
            Common.showToast(getString(R.string.cancel_like))
        } catch (ex: Exception) {
            Timber.e(ex)
            Common.showToast(ex.message)
        } finally {
            sender.hideProgress()
        }
    }
}