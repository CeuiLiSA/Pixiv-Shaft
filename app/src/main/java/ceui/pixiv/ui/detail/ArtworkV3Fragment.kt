package ceui.pixiv.ui.detail

import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentArtworkV3Binding
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Comment
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.ProgressIndicator
import ceui.loxia.UserResponse
import ceui.loxia.launchSuspend
import ceui.loxia.pushFragment
import ceui.pixiv.ui.comments.CommentsFragmentArgs
import ceui.pixiv.ui.common.FitsSystemWindowFragment
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.shareIllust
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.related.RelatedIllustsFragmentArgs
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu
import com.bumptech.glide.Glide
import com.google.android.flexbox.FlexboxLayout
import de.hdodenhof.circleimageview.CircleImageView
import java.text.NumberFormat
import kotlin.getValue

class ArtworkV3Fragment : PixivFragment(R.layout.fragment_artwork_v3), FitsSystemWindowFragment {

    private val binding by viewBinding(FragmentArtworkV3Binding::bind)
    private val illustId: Long by lazy {
        requireArguments().getLong(Params.ILLUST_ID)
    }
    private val viewModel by constructVM({ illustId }) { id ->
        ArtworkV3ViewModel(id)
    }

    companion object {
        fun newInstance(illustId: Long): ArtworkV3Fragment {
            return ArtworkV3Fragment().apply {
                arguments = Bundle().apply {
                    putLong(Params.ILLUST_ID, illustId)
                }
            }
        }
    }

    private lateinit var authorWorksAdapter: AuthorWorksAdapter
    private lateinit var relatedWorksAdapter: RelatedWorksAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupNavBar()
        setupScrollProgress()
        setupAdapters()

        viewModel.illustLiveData.observe(viewLifecycleOwner) { illust: Illust? ->
            if (illust != null) {
                bindIllustData(illust)
            }
        }

        viewModel.comments.observe(viewLifecycleOwner) { comments: List<Comment>? ->
            if (comments != null) bindComments(comments)
        }

        viewModel.authorWorks.observe(viewLifecycleOwner) { works: List<Illust>? ->
            if (works != null && works.isNotEmpty()) {
                binding.authorWorksSection.isVisible = true
                authorWorksAdapter.submitList(works)
            }
        }

        viewModel.relatedIllusts.observe(viewLifecycleOwner) { illusts: List<Illust>? ->
            if (illusts != null && illusts.isNotEmpty()) {
                binding.relatedWorksSection.isVisible = true
                relatedWorksAdapter.submitList(illusts)
            }
        }

        viewModel.userProfile.observe(viewLifecycleOwner) { profile: UserResponse? ->
            if (profile != null) {
                bindUserProfile(profile)
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading: Boolean? ->
            if (loading == false) {
                playEntranceAnimations()
            }
        }
    }

    private fun setupNavBar() {
        binding.navBack.setOnClick { requireActivity().onBackPressedDispatcher.onBackPressed() }
        binding.navMore.setOnClick {
            val illust = viewModel.illustLiveData.value ?: return@setOnClick
            showActionMenu {
                add(MenuItem(getString(R.string.view_comments)) {
                    pushFragment(
                        R.id.navigation_comments_illust,
                        CommentsFragmentArgs(
                            illustId, illust.user?.id ?: 0L, ObjectType.ILLUST
                        ).toBundle()
                    )
                })
                add(MenuItem(getString(R.string.string_110)) {
                    shareIllust(illust)
                })
            }
        }
        binding.navBookmark.setOnClick {
            val illust = viewModel.illustLiveData.value ?: return@setOnClick
            onClickBookmarkIllust(object : ProgressIndicator {
                override fun showProgress() {}
                override fun hideProgress() {}
            }, illust.id)
        }
    }

    private fun setupScrollProgress() {
        binding.scrollView.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { v, _, scrollY, _, _ ->
                val maxScroll = v.getChildAt(0).height - v.height
                val progress = if (maxScroll > 0) scrollY.toFloat() / maxScroll else 0f
                binding.scrollProgressBar.scaleX = progress
            }
        )
    }

    private fun setupAdapters() {
        authorWorksAdapter = AuthorWorksAdapter { illust ->
            onClickIllust(illust.id)
        }
        binding.authorWorksRv.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.authorWorksRv.adapter = authorWorksAdapter

        relatedWorksAdapter = RelatedWorksAdapter(
            onClickWork = { illust -> onClickIllust(illust.id) },
            onClickBookmark = { illust, _ ->
                onClickBookmarkIllust(object : ProgressIndicator {
                    override fun showProgress() {}
                    override fun hideProgress() {}
                }, illust.id)
            }
        )
        binding.relatedWorksRv.layoutManager =
            StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        binding.relatedWorksRv.adapter = relatedWorksAdapter
    }

    private fun bindIllustData(illust: Illust) {
        // Hero image
        val imageUrl = illust.image_urls?.large ?: illust.image_urls?.medium
        if (imageUrl != null) {
            Glide.with(this)
                .load(GlideUrlChild(imageUrl))
                .placeholder(R.drawable.bg_loading_placeholder)
                .centerCrop()
                .into(binding.heroImage)
        }

        binding.heroImage.setOnClickListener {
            pushFragment(
                R.id.navigation_paged_img_urls,
                ceui.pixiv.ui.works.PagedImgUrlFragmentArgs(illust.id, 0).toBundle()
            )
        }

        // Title & meta
        binding.heroTitle.text = illust.title
        binding.metaType.text = when (illust.type) {
            "manga" -> "Manga"
            "ugoira" -> "Ugoira"
            else -> "Illustration"
        }
        binding.metaDate.text = illust.displayCreateDate()
        binding.metaPages.text = if (illust.page_count == 1) "1 page" else "${illust.page_count} pages"

        // Badges
        binding.badgeAi.isVisible = illust.illust_ai_type == 2
        binding.badgePages.isVisible = illust.page_count > 1
        if (illust.page_count > 1) {
            binding.badgePages.text = "${illust.page_count}P"
        }
        binding.badgeDimensions.text = "${illust.width} × ${illust.height}"

        // Bookmark icon state
        updateBookmarkNavIcon(illust.is_bookmarked == true)

        // Series strip
        val series = illust.series
        if (series != null && series.title?.isNotEmpty() == true) {
            binding.seriesStrip.isVisible = true
            binding.seriesName.text = series.title
            binding.seriesStrip.setOnClickListener {
                pushFragment(
                    R.id.navigation_illust_series,
                    IllustSeriesFragmentArgs(series.id).toBundle()
                )
            }
            applyTouchScale(binding.seriesStrip)
        } else {
            binding.seriesStrip.isVisible = false
        }

        // Description
        if (!illust.caption.isNullOrBlank()) {
            binding.description.isVisible = true
            binding.description.setHtml(illust.caption)
        } else {
            binding.description.isVisible = false
        }

        // Stats
        val fmt = NumberFormat.getNumberInstance()
        binding.statViews.text = fmt.format(illust.total_view ?: 0)
        binding.statBookmarks.text = fmt.format(illust.total_bookmarks ?: 0)
        binding.statLikes.text = "--"
        binding.statComments.text = "--"

        // Tags
        bindTags(illust)

        // Artist basic info
        val user = illust.user
        if (user != null) {
            binding.artistName.text = user.name
            binding.artistHandle.text = "@${user.account ?: ""}"
            val avatarUrl = user.profile_image_urls?.medium
                ?: user.profile_image_urls?.square_medium
            if (avatarUrl != null) {
                Glide.with(this)
                    .load(GlideUrlChild(avatarUrl))
                    .placeholder(R.drawable.bg_loading_placeholder)
                    .circleCrop()
                    .into(binding.artistAvatar)
            }

            binding.artistCard.setOnClickListener {
                onClickUser(user.id)
            }
            applyTouchScale(binding.artistCard)

            // Follow button
            updateFollowButton(user.is_followed == true)
            binding.followBtn.setOnClick {
                toggleFollow(user.id)
            }

            // Bio
            if (!user.comment.isNullOrBlank()) {
                binding.artistBio.isVisible = true
                binding.artistBio.text = user.comment
            }
        }

        // Detail panel
        bindDetailPanel(illust)

        // Author works label
        binding.authorWorksLabel.text = "${user?.name ?: ""}'s Works".uppercase()

        // Comments button
        binding.commentsMore.setOnClick {
            pushFragment(
                R.id.navigation_comments_illust,
                CommentsFragmentArgs(
                    illust.id, illust.user?.id ?: 0L, ObjectType.ILLUST
                ).toBundle()
            )
        }

        // Related see more
        binding.relatedSeeMore.setOnClick {
            pushFragment(
                R.id.navigation_related_illusts,
                RelatedIllustsFragmentArgs(illust.id).toBundle()
            )
        }

        // Detail panel collapse/expand
        binding.detailHeader.setOnClickListener {
            val expanded = viewModel.detailExpanded.value == true
            viewModel.detailExpanded.value = !expanded
            binding.detailGrid.isVisible = !expanded
            binding.detailArrow.rotation = if (!expanded) 0f else 180f
        }
    }

    private fun updateBookmarkNavIcon(bookmarked: Boolean) {
        if (bookmarked) {
            binding.navBookmark.setImageResource(R.drawable.ic_favorite_black_24dp)
            binding.navBookmark.setColorFilter(requireContext().getColor(R.color.v3_pink))
        } else {
            binding.navBookmark.setImageResource(R.drawable.ic_favorite_border_black_24dp)
            binding.navBookmark.setColorFilter(Color.WHITE)
        }
    }

    private fun updateFollowButton(isFollowed: Boolean) {
        if (isFollowed) {
            binding.followBtn.text = getString(R.string.unfollow)
            binding.followBtn.setBackgroundResource(R.drawable.v3_follow_btn_secondary)
            binding.followBtn.setTextColor(requireContext().getColor(R.color.v3_tag_locked_text))
        } else {
            binding.followBtn.text = getString(R.string.follow)
            binding.followBtn.setBackgroundResource(R.drawable.v3_follow_btn_primary)
            binding.followBtn.setTextColor(Color.WHITE)
        }
    }

    private fun toggleFollow(userId: Long) {
        launchSuspend(binding.followBtn) {
            val illust = viewModel.illustLiveData.value ?: return@launchSuspend
            val currentUser = illust.user ?: return@launchSuspend
            if (currentUser.is_followed == true) {
                Client.appApi.postUnFollow(userId)
                val updatedUser = currentUser.copy(is_followed = false)
                ObjectPool.update(illust.copy(user = updatedUser))
                updateFollowButton(false)
            } else {
                Client.appApi.postFollow(userId, Params.TYPE_PUBLIC)
                val updatedUser = currentUser.copy(is_followed = true)
                ObjectPool.update(illust.copy(user = updatedUser))
                updateFollowButton(true)
            }
        }
    }

    private fun bindTags(illust: Illust) {
        val tagsFlow = binding.tagsFlow
        tagsFlow.removeAllViews()
        illust.tags?.forEach { tag ->
            val tv = TextView(requireContext()).apply {
                val isLocked = true // App API doesn't distinguish, treat all as locked
                text = buildString {
                    append("# ")
                    append(tag.name ?: "")
                    if (!tag.translated_name.isNullOrBlank()) {
                        append("  ")
                        append(tag.translated_name)
                    }
                }
                textSize = 13f
                setTextColor(requireContext().getColor(
                    if (isLocked) R.color.v3_tag_locked_text else R.color.v3_text_2
                ))
                setBackgroundResource(
                    if (isLocked) R.drawable.v3_tag_locked_bg else R.drawable.v3_tag_open_bg
                )
                setPadding(14.ppppx, 7.ppppx, 14.ppppx, 7.ppppx)
                val lp = FlexboxLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 8.ppppx, 8.ppppx)
                layoutParams = lp
                setOnClickListener {
                    onClickTag(tag, ObjectType.ILLUST)
                }
            }
            applyTouchScale(tv, 0.94f)
            tagsFlow.addView(tv)
        }
    }

    private fun bindDetailPanel(illust: Illust) {
        val grid = binding.detailGrid
        grid.removeAllViews()

        val chips = mutableListOf<Pair<String, String>>()
        chips.add("Artwork ID" to illust.id.toString())
        chips.add("User ID" to (illust.user?.id?.toString() ?: "--"))
        chips.add("Type" to when (illust.type) {
            "manga" -> "Manga"; "ugoira" -> "Ugoira"; else -> "Illustration"
        })
        chips.add("Resolution" to "${illust.width} × ${illust.height}")
        chips.add("Pages" to illust.page_count.toString())
        chips.add("AI" to if (illust.illust_ai_type == 2) "Yes (AI)" else "No (Human)")
        chips.add("Restriction" to when {
            illust.x_restrict == 1 -> "R-18"
            illust.x_restrict == 2 -> "R-18G"
            else -> "All Ages"
        })
        chips.add("Published" to illust.displayCreateDate())

        // Build 2-column rows
        for (i in chips.indices step 2) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                if (i > 0) {
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    lp.topMargin = 8.ppppx
                    layoutParams = lp
                }
            }

            row.addView(createDetailChip(chips[i].first, chips[i].second, illust))

            if (i + 1 < chips.size) {
                // 4dp gap
                val spacer = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(8.ppppx, 1)
                }
                row.addView(spacer)
                row.addView(createDetailChip(chips[i + 1].first, chips[i + 1].second, illust))
            }

            grid.addView(row)
        }
    }

    private fun createDetailChip(label: String, value: String, illust: Illust): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.v3_detail_chip_bg)
            setPadding(12.ppppx, 10.ppppx, 12.ppppx, 10.ppppx)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

            addView(TextView(requireContext()).apply {
                text = label.uppercase()
                textSize = 9f
                setTextColor(requireContext().getColor(R.color.v3_text_3))
                letterSpacing = 0.08f
                alpha = 0.7f
            })

            addView(TextView(requireContext()).apply {
                text = value
                textSize = 13f
                setTextColor(
                    when {
                        label == "Artwork ID" || label == "User ID" ->
                            requireContext().getColor(R.color.v3_detail_chip_ambient)
                        label == "AI" && illust.illust_ai_type == 2 ->
                            requireContext().getColor(R.color.v3_purple)
                        label == "AI" ->
                            requireContext().getColor(R.color.v3_green)
                        label == "Restriction" && (illust.x_restrict ?: 0) > 0 ->
                            requireContext().getColor(R.color.v3_pink)
                        label == "Restriction" ->
                            requireContext().getColor(R.color.v3_blue)
                        else -> requireContext().getColor(R.color.v3_text_2)
                    }
                )
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                if (label == "Artwork ID" || label == "User ID") {
                    typeface = android.graphics.Typeface.MONOSPACE
                }
            })
        }
    }

    private fun bindComments(comments: List<Comment>) {
        val list = binding.commentsList
        list.removeAllViews()

        if (comments.isEmpty()) {
            binding.commentsCount.text = "0"
            return
        }

        binding.commentsCount.text = comments.size.toString()

        comments.forEach { comment ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 14.ppppx, 0, 14.ppppx)
            }

            // Avatar
            val avatar = CircleImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(36.ppppx, 36.ppppx).apply {
                    marginEnd = 12.ppppx
                }
            }
            val avatarUrl = comment.user.profile_image_urls?.medium
                ?: comment.user.profile_image_urls?.square_medium
            if (avatarUrl != null) {
                Glide.with(this)
                    .load(GlideUrlChild(avatarUrl))
                    .circleCrop()
                    .into(avatar)
            }
            row.addView(avatar)

            // Content column
            val content = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            // Name + time
            val header = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(requireContext()).apply {
                text = comment.user.name
                textSize = 13f
                setTextColor(requireContext().getColor(R.color.v3_text_1))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            header.addView(TextView(requireContext()).apply {
                text = comment.displayCommentDate()
                textSize = 11f
                setTextColor(requireContext().getColor(R.color.v3_text_3))
                setPadding(8.ppppx, 0, 0, 0)
            })
            content.addView(header)

            // Comment text
            if (!comment.comment.isNullOrBlank()) {
                content.addView(TextView(requireContext()).apply {
                    text = comment.comment
                    textSize = 13f
                    setTextColor(requireContext().getColor(R.color.v3_text_2))
                    setPadding(0, 4.ppppx, 0, 0)
                    setLineSpacing(0f, 1.65f)
                })
            }

            // Stamp
            if (comment.stamp?.stamp_url != null) {
                val stampView = android.widget.ImageView(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(80.ppppx, 80.ppppx).apply {
                        topMargin = 4.ppppx
                    }
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                }
                Glide.with(this)
                    .load(GlideUrlChild(comment.stamp!!.stamp_url!!))
                    .into(stampView)
                content.addView(stampView)
            }

            // Reply indicator
            if (comment.has_replies) {
                content.addView(TextView(requireContext()).apply {
                    text = "View replies ›"
                    textSize = 11f
                    setTextColor(requireContext().getColor(R.color.v3_ambient_primary))
                    setPadding(0, 6.ppppx, 0, 0)
                })
            }

            row.addView(content)

            // Add divider between comments
            if (list.childCount > 0) {
                list.addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(0x0AFFFFFF)
                })
            }
            list.addView(row)
        }
    }

    private fun bindUserProfile(profile: ceui.loxia.UserResponse) {
        // Premium badge
        binding.premiumBadge.isVisible = profile.isPremium()

        // Twitter / social links
        val twitterUrl = profile.profile?.twitter_url
        val webpage = profile.profile?.webpage
        if (twitterUrl != null || webpage != null) {
            binding.artistSocials.isVisible = true
            binding.artistSocials.removeAllViews()

            if (!twitterUrl.isNullOrBlank()) {
                addSocialChip("Twitter", twitterUrl)
            }
            if (webpage != null && webpage.toString().isNotBlank()) {
                addSocialChip("Website", webpage.toString())
            }
        }

        // Following count
        val followCount = profile.profile?.total_follow_users
        if (followCount != null) {
            binding.artistFollowing.isVisible = true
            binding.artistFollowing.text = "$followCount Following"
        }
    }

    private fun addSocialChip(label: String, url: String) {
        val chip = TextView(requireContext()).apply {
            text = label
            textSize = 11f
            setTextColor(requireContext().getColor(R.color.v3_text_2))
            setBackgroundResource(R.drawable.v3_social_chip_bg)
            setPadding(12.ppppx, 5.ppppx, 12.ppppx, 5.ppppx)
            val lp = FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 6.ppppx, 6.ppppx)
            layoutParams = lp
            setOnClickListener {
                pushFragment(
                    R.id.navigation_web_fragment,
                    ceui.pixiv.ui.web.WebFragmentArgs(url).toBundle()
                )
            }
        }
        binding.artistSocials.addView(chip)
    }

    private fun playEntranceAnimations() {
        // Hero fade
        binding.heroSection.alpha = 0f
        binding.heroSection.scaleX = 1.03f
        binding.heroSection.scaleY = 1.03f
        binding.heroSection.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(700)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()

        // Staggered slide-up for content children
        val column = binding.contentColumn
        var delay = 50L
        for (i in 0 until column.childCount) {
            val child = column.getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.alpha = 0f
            child.translationY = 24.ppppx.toFloat()
            child.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(delay)
                .setDuration(550)
                .setInterpolator(DecelerateInterpolator(2f))
                .start()
            delay += 40
        }
    }

    private fun applyTouchScale(view: View, scale: Float = 0.97f) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(scale).scaleY(scale).setDuration(200).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                }
            }
            false
        }
    }
}
