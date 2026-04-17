package ceui.lisa.activities

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import ceui.lisa.R
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.ActivityUserV3Binding
import ceui.lisa.databinding.ItemV3NavTagBinding
import ceui.lisa.databinding.ItemV3ProfileChipBinding
import ceui.lisa.helper.UserIllustJumpHelper
import ceui.lisa.http.NullCtrl
import ceui.lisa.http.Retro
import ceui.lisa.models.UserBean
import ceui.lisa.models.UserDetailResponse
import ceui.lisa.models.UserFollowDetail
import ceui.lisa.models.WorkspaceBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.V3Palette
import android.net.Uri
import ceui.lisa.viewmodel.AppLevelViewModel
import ceui.lisa.viewmodel.UserViewModel
import ceui.loxia.Event
import ceui.loxia.ObjectPool
import ceui.loxia.ProgressTextButton
import ceui.pixiv.session.SessionManager
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog.MenuDialogBuilder
import com.zhy.view.flowlayout.FlowLayout
import com.zhy.view.flowlayout.TagAdapter
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import java.text.NumberFormat

class UserActivityV3 : BaseActivity<ActivityUserV3Binding>() {

    private var userId = 0
    private lateinit var mUserViewModel: UserViewModel
    private lateinit var palette: V3Palette

    override fun initLayout(): Int = R.layout.activity_user_v3

    override fun initBundle(bundle: Bundle) {
        userId = bundle.getInt(Params.USER_ID)
    }

    override fun initModel() {
        mUserViewModel = ViewModelProvider(this)[UserViewModel::class.java]
        mUserViewModel.user.observe(this) { data -> displayUser(data) }

        val entity = AppDatabase.getAppDatabase(this).searchDao().getUserMuteEntityByID(userId)
        mUserViewModel.isUserMuted.value = entity != null
        val block = AppDatabase.getAppDatabase(this).searchDao().getBlockMuteEntityByID(userId)
        mUserViewModel.isUserBlocked.value = block != null

        ObjectPool.get<UserBean>(userId.toLong()).observe(this) { user ->
            updateFollowState(user)
        }
    }

    override fun initView() {
        palette = V3Palette.from(this)
        baseBind.toolbar.setPadding(0, Shaft.statusHeight, 0, 0)
        baseBind.toolbar.setNavigationOnClickListener { finish() }

        // Apply theme-colored drawables to follow/unfollow buttons
        val density = resources.displayMetrics.density
        baseBind.follow.background = palette.pillPrimary(999f * density)
        baseBind.unfollow.background = palette.pillSecondary(999f * density, (1 * density).toInt())
        baseBind.unfollow.setTextColor(palette.textSecondary)

        // Toolbar alpha transition on scroll
        baseBind.toolbarLayout.viewTreeObserver.addOnGlobalLayoutListener(object :
            OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val offset = baseBind.toolbarLayout.height - Shaft.statusHeight - Shaft.toolbarHeight
                baseBind.appBar.addOnOffsetChangedListener { _, verticalOffset ->
                    val abs = Math.abs(verticalOffset)
                    when {
                        abs < 15 -> {
                            baseBind.profileHeader.alpha = 1.0f
                            baseBind.toolbarTitle.alpha = 0.0f
                        }
                        offset - abs < 15 -> {
                            baseBind.profileHeader.alpha = 0.0f
                            baseBind.toolbarTitle.alpha = 1.0f
                        }
                        else -> {
                            baseBind.profileHeader.alpha = 1 + verticalOffset.toFloat() / offset
                            baseBind.toolbarTitle.alpha = -verticalOffset.toFloat() / offset
                        }
                    }
                }
                baseBind.toolbarLayout.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })
    }

    override fun initData() {
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

    override fun hideStatusBar(): Boolean = true

    private fun updateFollowState(user: UserBean) {
        if (user.isIs_followed) {
            baseBind.follow.isVisible = false
            baseBind.unfollow.isVisible = true
            baseBind.unfollow.setOnClick { unfollowUser(it, userId) }
            baseBind.unfollow.setOnLongClickListener { true }
        } else {
            baseBind.unfollow.isVisible = false
            baseBind.follow.isVisible = true
            baseBind.follow.setOnClick { followUser(it, userId, Params.TYPE_PUBLIC) }
            baseBind.follow.setOnLongClickListener {
                followUser(it as ProgressTextButton, userId, Params.TYPE_PRIVATE)
                true
            }
        }
    }

    private fun displayUser(data: UserDetailResponse) {
        val isSelf = userId.toLong() == SessionManager.loggedInUid
        val profile = data.profile
        val user = data.user
        val workspace = data.workspace

        // Banner
        val bannerUrl = profile.background_image_url
        if (!bannerUrl.isNullOrEmpty()) {
            baseBind.bannerImage.visibility = View.VISIBLE
            Glide.with(mContext).load(GlideUtil.getUrl(bannerUrl)).into(baseBind.bannerImage)
        }

        // Avatar
        Glide.with(mContext).load(GlideUtil.getHead(user)).into(baseBind.userAvatar)

        // Premium
        if (user.isIs_premium) {
            baseBind.premiumRing.visibility = View.VISIBLE
            baseBind.premiumBadge.visibility = View.VISIBLE
        }

        // Name, handle
        baseBind.userName.text = user.name
        baseBind.userHandle.text = "@${user.account} · ID: ${user.id}"
        baseBind.toolbarTitle.text = user.name

        baseBind.userName.setOnClickListener { Common.copy(mContext, user.id.toString()) }
        baseBind.userName.setOnLongClickListener {
            Common.copy(mContext, user.name)
            true
        }

        // Follow layout
        if (isSelf) {
            baseBind.followLayout.visibility = View.GONE
        }

        // More menu
        baseBind.moreAction.visibility = View.VISIBLE
        baseBind.moreAction.setOnClickListener { showMoreMenu(data, isSelf) }

        // Bio (render HTML — comment may contain <a>, <br> etc.)
        if (!TextUtils.isEmpty(user.comment)) {
            baseBind.bio.visibility = View.VISIBLE
            baseBind.bio.text = androidx.core.text.HtmlCompat.fromHtml(
                user.comment, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT
            )
            baseBind.bio.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }

        // Stats row (following + mypixiv)
        val fmt = NumberFormat.getInstance()
        baseBind.statFollowingNum.text = fmt.format(profile.total_follow_users)
        baseBind.statMypixivNum.text = fmt.format(profile.total_mypixiv_users)

        baseBind.statFollowing.setOnClickListener {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(Params.USER_ID, user.id)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "正在关注")
            startActivity(intent)
        }
        baseBind.statMypixiv.setOnClickListener {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(Params.USER_ID, user.id)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "好P友")
            startActivity(intent)
        }

        // Social links
        setupSocialChips(profile)

        // Navigation tags
        setupNavTags(data, isSelf)

        // Profile details card
        setupProfileCard(data)

        // Workspace card
        setupWorkspaceCard(workspace)

        // Mute strip (hide for self)
        if (!isSelf) {
            baseBind.muteStrip.visibility = View.VISIBLE
            val isMuted = mUserViewModel.isUserMuted.value == true
            baseBind.muteSwitch.isChecked = isMuted
            baseBind.muteSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    PixivOperate.muteUser(user)
                    mUserViewModel.isUserMuted.value = true
                } else {
                    PixivOperate.unMuteUser(user)
                    mUserViewModel.isUserMuted.value = false
                }
                mUserViewModel.refreshEvent.value = Event(100, 0L)
            }
        }
    }

    private fun setupSocialChips(profile: ceui.lisa.models.ProfileBean) {
        var hasAny = false
        val textColor = resources.getColor(R.color.v3_text_2, theme)
        val dp = resources.displayMetrics.density

        fun addChip(label: String, url: String?) {
            if (url.isNullOrEmpty()) return
            hasAny = true
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 999f * dp
                setColor(0x14FFFFFF)
                setStroke((1 * dp).toInt(), palette.alpha20)
            }
            val tv = android.widget.TextView(this).apply {
                text = label
                setTextColor(textColor)
                textSize = 12f
                background = bg
                setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
                layoutParams = com.google.android.flexbox.FlexboxLayout.LayoutParams(
                    com.google.android.flexbox.FlexboxLayout.LayoutParams.WRAP_CONTENT,
                    com.google.android.flexbox.FlexboxLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, (10 * dp).toInt(), (10 * dp).toInt())
                }
                setOnClickListener { openUrl(url) }
            }
            baseBind.socialsGroup.addView(tv)
        }

        addChip(
            if (!TextUtils.isEmpty(profile.twitter_account)) "@${profile.twitter_account}" else "Twitter",
            profile.twitter_url
        )
        addChip("Website", profile.webpage)
        addChip("Pawoo", profile.pawoo_url)

        if (hasAny) {
            baseBind.socialsGroup.visibility = View.VISIBLE
        }
    }

    private fun setupNavTags(data: UserDetailResponse, isSelf: Boolean) {
        val profile = data.profile
        val tags = mutableListOf<Pair<String, String>>()  // label -> fragment/count
        val counts = mutableListOf<Int>()

        if (profile.total_illusts > 0) {
            tags.add(getString(R.string.string_246) to "插画作品")
            counts.add(profile.total_illusts)
        }
        if (profile.total_manga > 0) {
            tags.add(getString(R.string.string_233) to "漫画作品")
            counts.add(profile.total_manga)
        }
        if (profile.total_illust_series > 0) {
            tags.add(getString(R.string.string_230) to "漫画系列作品")
            counts.add(profile.total_illust_series)
        }
        if (profile.total_novels > 0) {
            tags.add(getString(R.string.string_237) to "小说作品")
            counts.add(profile.total_novels)
        }
        if (profile.total_novel_series > 0) {
            tags.add(getString(R.string.string_257) to "小说系列作品")
            counts.add(profile.total_novel_series)
        }
        if (profile.total_illust_bookmarks_public > 0 || isSelf) {
            tags.add(getString(R.string.string_164) to "插画/漫画收藏")
            counts.add(profile.total_illust_bookmarks_public)
        }
        tags.add(getString(R.string.string_192) to "小说收藏")
        counts.add(0)
        tags.add(getString(R.string.string_436) to "相关用户")
        counts.add(0)

        if (tags.isNotEmpty()) {
            baseBind.navLabel.visibility = View.VISIBLE
            baseBind.navTags.visibility = View.VISIBLE

            baseBind.navTags.adapter = object : TagAdapter<Pair<String, String>>(tags) {
                override fun getView(parent: FlowLayout, position: Int, item: Pair<String, String>?): View {
                    val binding = ItemV3NavTagBinding.inflate(
                        LayoutInflater.from(mContext), parent, false
                    )
                    binding.tagName.text = item?.first ?: ""
                    // Themed pill border
                    val dp = resources.displayMetrics.density
                    binding.root.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 999f * dp
                        setColor(0x08FFFFFF)
                        setStroke((1 * dp).toInt(), palette.alpha20)
                    }
                    val count = counts.getOrNull(position) ?: 0
                    if (count > 0) {
                        binding.tagCount.visibility = View.VISIBLE
                        binding.tagCount.text = NumberFormat.getInstance().format(count)
                        binding.tagCount.setTextColor(palette.textAccent)
                        binding.tagCount.background = palette.tagCountBg(999f * dp)
                    }
                    return binding.root
                }
            }
            baseBind.navTags.setOnTagClickListener { _, position, _ ->
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(Params.USER_ID, data.user.userId)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, tags[position].second)
                startActivity(intent)
                true
            }
        }
    }

    private fun setupProfileCard(data: UserDetailResponse) {
        val profile = data.profile
        val user = data.user
        baseBind.profileCard.visibility = View.VISIBLE

        val chips = mutableListOf<Triple<String, String, Boolean>>()  // label, value, isMono
        chips.add(Triple("User ID", user.id.toString(), true))
        chips.add(Triple("Account", user.account, false))

        if (!TextUtils.isEmpty(profile.gender)) {
            val genderText = when (profile.gender) {
                "male" -> "Male"
                "female" -> "Female"
                else -> profile.gender
            }
            chips.add(Triple("Gender", genderText, false))
        }
        if (!TextUtils.isEmpty(profile.region)) {
            chips.add(Triple("Region", profile.region, false))
        }
        if (!TextUtils.isEmpty(profile.birth_day)) {
            chips.add(Triple("Birthday", profile.birth_day, false))
        }
        if (!TextUtils.isEmpty(profile.job)) {
            chips.add(Triple("Job", profile.job, false))
        }
        chips.add(Triple("Premium", if (user.isIs_premium) "★ Premium User" else "Standard", false))
        chips.add(Triple("Pixiv URL", "pixiv.net/users/${user.id}", true))

        // Build the grid: 2 chips per row
        val grid = baseBind.profileGrid
        grid.removeAllViews()
        val density = resources.displayMetrics.density
        val rowGap = (10 * density).toInt()
        val chipGap = (5 * density).toInt()
        var i = 0
        while (i < chips.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = rowGap }
            }

            val chip1 = createProfileChip(chips[i])
            val isLastSingle = i + 1 >= chips.size
            val isFullWidth = chips[i].first == "Pixiv URL"

            if (isFullWidth || isLastSingle) {
                chip1.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(chip1)
            } else {
                chip1.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = chipGap
                }
                row.addView(chip1)

                val chip2 = createProfileChip(chips[i + 1])
                chip2.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = chipGap
                }
                row.addView(chip2)
                i++
            }
            grid.addView(row)
            i++
        }

        // Toggle
        baseBind.profileHeaderToggle.setOnClickListener {
            val isVisible = baseBind.profileGrid.visibility == View.VISIBLE
            baseBind.profileGrid.visibility = if (isVisible) View.GONE else View.VISIBLE
            baseBind.profileArrow.rotation = if (isVisible) 0f else 180f
        }
    }

    private fun createProfileChip(data: Triple<String, String, Boolean>): View {
        val binding = ItemV3ProfileChipBinding.inflate(LayoutInflater.from(mContext))
        binding.chipLabel.text = data.first
        binding.chipValue.text = data.second
        if (data.second == "★ Premium User") {
            binding.chipValue.setTextColor(0xFFFFC233.toInt())
        }
        return binding.root
    }

    private fun setupWorkspaceCard(workspace: WorkspaceBean?) {
        if (workspace == null) return

        val items = mutableListOf<Pair<String, String>>()
        workspace.pc?.takeIf { it.isNotBlank() }?.let { items.add("PC" to it) }
        workspace.monitor?.takeIf { it.isNotBlank() }?.let { items.add("Monitor" to it) }
        workspace.tool?.takeIf { it.isNotBlank() }?.let { items.add("Tool" to it) }
        workspace.tablet?.takeIf { it.isNotBlank() }?.let { items.add("Tablet" to it) }
        workspace.scanner?.takeIf { it.isNotBlank() }?.let { items.add("Scanner" to it) }
        workspace.mouse?.takeIf { it.isNotBlank() }?.let { items.add("Mouse" to it) }
        workspace.printer?.takeIf { it.isNotBlank() }?.let { items.add("Printer" to it) }
        workspace.desktop?.takeIf { it.isNotBlank() }?.let { items.add("Desktop" to it) }
        workspace.music?.takeIf { it.isNotBlank() }?.let { items.add("Music" to it) }
        workspace.desk?.takeIf { it.isNotBlank() }?.let { items.add("Desk" to it) }
        workspace.chair?.takeIf { it.isNotBlank() }?.let { items.add("Chair" to it) }
        workspace.comment?.takeIf { it.isNotBlank() }?.let { items.add("Comment" to it) }

        if (items.isEmpty()) return

        baseBind.workspaceCard.visibility = View.VISIBLE
        val grid = baseBind.workspaceGrid
        grid.removeAllViews()
        val density = resources.displayMetrics.density
        val rowGap = (10 * density).toInt()
        val chipGap = (5 * density).toInt()

        var i = 0
        while (i < items.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = rowGap }
            }

            val isLastSingle = i + 1 >= items.size
            val chip1 = createWorkspaceChip(items[i])

            if (isLastSingle) {
                chip1.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(chip1)
            } else {
                chip1.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = chipGap
                }
                row.addView(chip1)

                val chip2 = createWorkspaceChip(items[i + 1])
                chip2.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = chipGap
                }
                row.addView(chip2)
                i++
            }
            grid.addView(row)
            i++
        }

        // Toggle
        baseBind.workspaceHeaderToggle.setOnClickListener {
            val isVisible = baseBind.workspaceGrid.visibility == View.VISIBLE
            baseBind.workspaceGrid.visibility = if (isVisible) View.GONE else View.VISIBLE
            baseBind.workspaceArrow.rotation = if (isVisible) 0f else 180f
        }
    }

    private fun createWorkspaceChip(data: Pair<String, String>): View {
        val binding = ItemV3ProfileChipBinding.inflate(LayoutInflater.from(mContext))
        binding.chipLabel.text = data.first
        binding.chipValue.text = data.second
        return binding.root
    }

    private fun showMoreMenu(data: UserDetailResponse, isSelf: Boolean) {
        val isMuted = mUserViewModel.isUserMuted.value == true
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (data.profile.total_illusts > 0) {
            labels.add("跳转到插画…")
            actions.add { jumpTo(data.user.id, UserIllustJumpHelper.Kind.ILLUST, "插画作品") }
        }
        if (data.profile.total_manga > 0) {
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
                    mUserViewModel.isUserMuted.value = false
                    baseBind.muteSwitch.isChecked = false
                } else {
                    PixivOperate.muteUser(data.user)
                    mUserViewModel.isUserMuted.value = true
                    baseBind.muteSwitch.isChecked = true
                }
                mUserViewModel.refreshEvent.value = Event(100, 0L)
            }
        }
        if (labels.isEmpty()) return

        MenuDialogBuilder(mActivity)
            .setSkinManager(QMUISkinManager.defaultInstance(mActivity))
            .addItems(labels.toTypedArray()) { dialog: DialogInterface, which: Int ->
                dialog.dismiss()
                actions.getOrNull(which)?.invoke()
            }
            .show()
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

    private fun openUrl(url: String?) {
        if (url.isNullOrEmpty()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Common.showToast(url)
        }
    }
}
