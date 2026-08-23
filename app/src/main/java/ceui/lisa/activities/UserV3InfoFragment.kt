package ceui.lisa.activities

import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import ceui.lisa.R
import ceui.lisa.databinding.FragmentUserV3InfoBinding
import ceui.lisa.databinding.ItemV3ProfileChipBinding
import ceui.lisa.models.UserDetailResponse
import ceui.lisa.models.WorkspaceBean
import ceui.lisa.utils.PixivOperate
import ceui.lisa.viewmodel.UserViewModel
import ceui.loxia.Event
import ceui.loxia.WebUserDetail
import ceui.pixiv.session.SessionManager

/**
 * V3 用户主页右侧 Tab —— 资料 / 工作环境 / 社交链接 / 简介 / 屏蔽。
 *
 * 共享宿主 UserActivityV3 的 UserViewModel,自身只负责把数据渲染进卡片。
 */
class UserV3InfoFragment : Fragment() {

    private var _binding: FragmentUserV3InfoBinding? = null
    private val binding get() = _binding!!
    private val userViewModel: UserViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DataBindingUtil.inflate(
            inflater, R.layout.fragment_user_v3_info, container, false
        )
        return binding.root
    }

    private val muteSwitchListener =
        android.widget.CompoundButton.OnCheckedChangeListener { _, isChecked ->
            val user = userViewModel.user.value?.user ?: return@OnCheckedChangeListener
            if (isChecked) {
                PixivOperate.muteUser(user)
                userViewModel.isUserMuted.value = true
            } else {
                PixivOperate.unMuteUser(user)
                userViewModel.isUserMuted.value = false
            }
            userViewModel.refreshEvent.value = Event(100, 0L)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        userViewModel.user.observe(viewLifecycleOwner) { data ->
            if (data != null) bindUserDetail(data)
        }
        userViewModel.webUserDetail.observe(viewLifecycleOwner) { detail ->
            if (detail != null) bindWebUserDetail(detail)
        }
        // 宿主菜单里也能改 mute,这里同步 switch 状态,临时摘掉 listener 避免回调里再写回 LiveData 导致循环。
        userViewModel.isUserMuted.observe(viewLifecycleOwner) { isMuted ->
            val checked = isMuted == true
            if (binding.muteSwitch.isChecked != checked) {
                binding.muteSwitch.setOnCheckedChangeListener(null)
                binding.muteSwitch.isChecked = checked
                binding.muteSwitch.setOnCheckedChangeListener(muteSwitchListener)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun bindUserDetail(data: UserDetailResponse) {
        val user = data.user
        val profile = data.profile
        val isSelf = user.id.toLong() == SessionManager.loggedInUid

        // Bio
        if (!TextUtils.isEmpty(user.comment)) {
            binding.bio.visibility = View.VISIBLE
            binding.bio.text = androidx.core.text.HtmlCompat.fromHtml(
                user.comment.orEmpty(), androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT
            )
            binding.bio.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }

        // Social chips from app API (web API may add more later)
        setupSocialChips(profile)

        setupProfileCard(data)
        setupWorkspaceCard(data.workspace)

        if (!isSelf) {
            binding.muteStrip.visibility = View.VISIBLE
            val isMuted = userViewModel.isUserMuted.value == true
            // 摘 listener → 设值 → 重新挂,避免初始 isChecked 写入触发 muteSwitchListener。
            binding.muteSwitch.setOnCheckedChangeListener(null)
            binding.muteSwitch.isChecked = isMuted
            binding.muteSwitch.setOnCheckedChangeListener(muteSwitchListener)
        }
    }

    private fun bindWebUserDetail(detail: WebUserDetail) {
        // Web 端的 commentHtml 比 app 端的 user.comment 排版更全（有 <a> 等标签）,有就覆盖。
        if (!detail.commentHtml.isNullOrEmpty()) {
            binding.bio.isVisible = true
            binding.bio.text = androidx.core.text.HtmlCompat.fromHtml(
                detail.commentHtml, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT
            )
            binding.bio.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }

        // Supplement social links from Web API
        detail.social?.forEach { (platform, link) ->
            val url = link.url ?: return@forEach
            addSocialChip(platform, prettySocialLabel(platform), url)
        }
        detail.webpage?.takeIf { it.isNotBlank() }?.let { url ->
            addSocialChip("webpage", "Website", url)
        }
    }

    private data class SocialStyle(val iconRes: Int, val badgeColor: Int)

    private fun socialStyle(platformKey: String): SocialStyle {
        return when (platformKey.lowercase()) {
            "instagram" -> SocialStyle(R.drawable.ic_v3_social_instagram, 0xFFFFE7FC.toInt())
            "facebook" -> SocialStyle(R.drawable.ic_v3_social_facebook, 0xFFDBF2FE.toInt())
            "twitter", "x" -> SocialStyle(R.drawable.ic_v3_social_twitter, 0xFFDFDFDF.toInt())
            "youtube" -> SocialStyle(R.drawable.ic_v3_social_youtube, 0xFFFFD6CD.toInt())
            "tiktok" -> SocialStyle(R.drawable.ic_v3_social_tiktok, 0xFFDFDFDF.toInt())
            "linkedin" -> SocialStyle(R.drawable.ic_v3_social_linkedin, 0xFFE0EFFF.toInt())
            "spotify" -> SocialStyle(R.drawable.ic_v3_social_spotify, 0xFFD5EDC2.toInt())
            else -> SocialStyle(R.drawable.ic_v3_social_link, 0xFFE8DCCD.toInt())
        }
    }

    private fun prettySocialLabel(platformKey: String): String {
        return when (platformKey.lowercase()) {
            "twitter", "x" -> "X (Twitter)"
            "youtube" -> "YouTube"
            "tiktok" -> "TikTok"
            "linkedin" -> "LinkedIn"
            "github" -> "GitHub"
            "webpage" -> "Website"
            else -> platformKey.replaceFirstChar { it.uppercase() }
        }
    }

    private fun setupSocialChips(profile: ceui.lisa.models.ProfileBean) {
        addSocialChip(
            "twitter",
            if (!TextUtils.isEmpty(profile.twitter_account)) "@${profile.twitter_account}" else "X (Twitter)",
            profile.twitter_url
        )
        addSocialChip("webpage", "Website", profile.webpage)
        addSocialChip("pawoo", "Pawoo", profile.pawoo_url)
    }

    private fun addSocialChip(platformKey: String, label: String, url: String?) {
        if (url.isNullOrEmpty()) return
        val dedupTag = "${platformKey.lowercase()}:${url}"
        for (i in 0 until binding.socialsGroup.childCount) {
            if (binding.socialsGroup.getChildAt(i).tag == dedupTag) return
        }

        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val style = socialStyle(platformKey)

        if (binding.socialsGroup.childCount > 0) {
            val divider = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    Math.max(1, (0.5f * dp).toInt())
                )
                setBackgroundColor(resources.getColor(R.color.v3_border_2, ctx.theme))
            }
            binding.socialsGroup.addView(divider)
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            tag = dedupTag
            val typed = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, typed, true)
            setBackgroundResource(typed.resourceId)
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { openUrl(url) }
        }

        val badge = android.widget.ImageView(ctx).apply {
            setImageResource(style.iconRes)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 10f * dp
                setColor(style.badgeColor)
            }
            val pad = (8 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                (36 * dp).toInt(), (36 * dp).toInt()
            )
        }
        row.addView(badge)

        val tv = android.widget.TextView(ctx).apply {
            text = label
            setTextColor(resources.getColor(R.color.v3_text_1, ctx.theme))
            textSize = 14f
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart = (12 * dp).toInt()
                marginEnd = (12 * dp).toInt()
            }
        }
        row.addView(tv)

        val chevron = android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.ic_arrow_right_little)
            imageTintList = android.content.res.ColorStateList.valueOf(
                resources.getColor(R.color.v3_text_3, ctx.theme)
            )
            layoutParams = LinearLayout.LayoutParams(
                (20 * dp).toInt(), (20 * dp).toInt()
            )
        }
        row.addView(chevron)

        binding.socialsGroup.addView(row)
        binding.socialsCard.isVisible = true
    }

    private fun setupProfileCard(data: UserDetailResponse) {
        val profile = data.profile
        val user = data.user
        binding.profileCard.visibility = View.VISIBLE

        val chips = mutableListOf<Triple<String, String, Boolean>>()
        chips.add(Triple("User ID", user.id.toString(), true))
        chips.add(Triple("Account", user.account.orEmpty(), false))

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
        chips.add(Triple("Premium", if (user.is_premium == true) "★ Premium User" else "Standard", false))
        chips.add(Triple("Pixiv URL", "https://www.pixiv.net/users/${user.id}", true))

        val grid = binding.profileGrid
        grid.removeAllViews()
        val density = resources.displayMetrics.density
        val rowGap = (10 * density).toInt()
        val chipGap = (5 * density).toInt()
        var i = 0
        while (i < chips.size) {
            val row = LinearLayout(requireContext()).apply {
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
                chip1.layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(chip1)
            } else {
                chip1.layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = chipGap
                    }
                row.addView(chip1)

                val chip2 = createProfileChip(chips[i + 1])
                chip2.layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = chipGap
                    }
                row.addView(chip2)
                i++
            }
            grid.addView(row)
            i++
        }
    }

    private fun createProfileChip(data: Triple<String, String, Boolean>): View {
        val bd = ItemV3ProfileChipBinding.inflate(LayoutInflater.from(requireContext()))
        bd.chipLabel.text = data.first
        bd.chipValue.text = data.second
        if (data.second == "★ Premium User") {
            bd.chipValue.setTextColor(0xFFFFC233.toInt())
        }
        if (data.second.startsWith("http://") || data.second.startsWith("https://")) {
            // 不用 autoLinkMask/LinkMovementMethod:LinkMovementMethod 继承
            // ScrollingMovementMethod,横拖 URL 会跟外层 ViewPager2 抢手势,整页
            // 跟着滑(issue #917)。改成整块点击打开 + 复用 textColorLink 的链接色,
            // 跟 addSocialChip 的 tap-to-open 一致。
            bd.chipValue.setTextColor(bd.chipValue.linkTextColors)
            bd.root.setOnClickListener { openUrl(data.second) }
        }
        return bd.root
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

        binding.workspaceCard.visibility = View.VISIBLE
        val grid = binding.workspaceGrid
        grid.removeAllViews()
        val density = resources.displayMetrics.density
        val rowGap = (10 * density).toInt()
        val chipGap = (5 * density).toInt()

        var i = 0
        while (i < items.size) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = rowGap }
            }

            val isLastSingle = i + 1 >= items.size
            val chip1 = createWorkspaceChip(items[i])

            if (isLastSingle) {
                chip1.layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(chip1)
            } else {
                chip1.layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = chipGap
                    }
                row.addView(chip1)

                val chip2 = createWorkspaceChip(items[i + 1])
                chip2.layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = chipGap
                    }
                row.addView(chip2)
                i++
            }
            grid.addView(row)
            i++
        }

        binding.workspaceHeaderToggle.setOnClickListener {
            val isVisible = binding.workspaceGrid.visibility == View.VISIBLE
            binding.workspaceGrid.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.workspaceArrow.rotation = if (isVisible) 0f else 180f
        }
    }

    private fun createWorkspaceChip(data: Pair<String, String>): View {
        val bd = ItemV3ProfileChipBinding.inflate(LayoutInflater.from(requireContext()))
        bd.chipLabel.text = data.first
        bd.chipValue.text = data.second
        return bd.root
    }

    private fun openUrl(url: String?) {
        if (url.isNullOrEmpty()) return
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            ceui.lisa.utils.Common.showToast(url)
        }
    }
}
