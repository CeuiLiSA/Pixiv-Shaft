package ceui.pixiv.snapshot

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.databinding.FragmentSnapshotViewerBinding
import ceui.lisa.utils.Common
import ceui.loxia.Comment
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 只读离线快照查看器。所有数据来自快照目录，不发起任何网络请求。
 */
class SnapshotViewerFragment : Fragment() {

    private var _binding: FragmentSnapshotViewerBinding? = null
    private val binding get() = checkNotNull(_binding)

    private val snapshotId by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_SNAPSHOT_ID)
            ?: throw IllegalArgumentException("缺少 snapshotId")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = FragmentSnapshotViewerBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun load() {
        lifecycleScope.launch {
            val data = try {
                withContext(Dispatchers.IO) { SnapshotRepository.loadViewerData(requireContext(), snapshotId) }
            } catch (e: Exception) {
                Common.showToast(getString(R.string.snapshot_open_failed, e.message ?: ""))
                requireActivity().onBackPressedDispatcher.onBackPressed()
                return@launch
            }
            if (_binding == null) return@launch
            render(data)
        }
    }

    private fun render(data: SnapshotViewerData) {
        val context = requireContext()
        binding.title.text = data.illust.title ?: getString(R.string.snapshot_untitled)
        binding.caption.isVisible = !data.illust.caption.isNullOrBlank()
        binding.caption.text = data.illust.caption.orEmpty()
        binding.meta.text = buildMetaText(context, data)
        binding.tags.text = data.illust.tags
            ?.mapNotNull { it?.name }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("  ") { "#$it" }
            ?: ""
        binding.authorName.text = data.illust.user?.name ?: getString(R.string.snapshot_unknown_author)
        binding.authorId.text = getString(R.string.snapshot_author_id_format, data.illust.user?.id ?: 0)

        val authorAvatar = data.resolve(data.illust.snapshotAuthorAvatarUrl())
        if (authorAvatar != null) {
            Glide.with(binding.authorAvatar).load(authorAvatar).into(binding.authorAvatar)
        }

        binding.imagesContainer.removeAllViews()
        val pageCount = data.illust.page_count.coerceAtLeast(1)
        for (i in 0 until pageCount) {
            val url = data.illust.snapshotPageUrl(i, data.manifest.includeOriginal)
            val file = data.resolve(url)
            val imageView = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(ContextCompat.getColor(context, R.color.v3_text_3))
            }
            if (file != null) {
                Glide.with(this).load(file).into(imageView)
            } else {
                imageView.minimumHeight = dp(160)
            }
            binding.imagesContainer.addView(imageView)
        }

        binding.commentsContainer.removeAllViews()
        val comments = data.comments
        binding.commentsHeader.isVisible = comments != null
        if (comments != null) {
            comments.threads.forEach { thread ->
                addCommentView(binding.commentsContainer, thread.comment, false, data)
                thread.replies.forEach { reply -> addCommentView(binding.commentsContainer, reply, true, data) }
            }
        }
    }

    private fun buildMetaText(context: Context, data: SnapshotViewerData): String {
        val parts = mutableListOf<String>()
        if (data.illust.total_view > 0) parts += context.getString(R.string.snapshot_views_format, data.illust.total_view)
        if (data.illust.total_bookmarks > 0) parts += context.getString(R.string.snapshot_bookmarks_format, data.illust.total_bookmarks)
        data.illust.create_date?.let { parts += it }
        if (data.manifest.includeComments) parts += getString(R.string.snapshot_badge_comments)
        if (data.manifest.includeOriginal) parts += getString(R.string.snapshot_badge_original)
        return parts.joinToString(" · ")
    }

    private fun addCommentView(
        container: LinearLayout,
        comment: Comment,
        isReply: Boolean,
        data: SnapshotViewerData,
    ) {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(10)
                if (isReply) marginStart = dp(28)
            }
        }

        val avatar = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(ContextCompat.getColor(context, R.color.v3_text_3))
        }
        val avatarFile = data.resolve(comment.snapshotAvatarUrl())
        row.addView(avatar)

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameLine = TextView(context).apply {
            text = listOfNotNull(
                comment.user.name,
                comment.user.id.takeIf { it > 0 }?.let { "ID $it" },
            ).joinToString(" · ")
            setTextColor(ContextCompat.getColor(context, R.color.v3_text_1))
            textSize = 13f
        }
        val time = TextView(context).apply {
            text = comment.date?.let { raw ->
                runCatching {
                    val parsed = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(raw)
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(parsed?.time ?: 0L))
                }.getOrDefault(raw)
            } ?: ""
            setTextColor(ContextCompat.getColor(context, R.color.v3_text_3))
            textSize = 11f
        }
        val content = TextView(context).apply {
            text = comment.comment ?: ""
            setTextColor(ContextCompat.getColor(context, R.color.v3_text_1))
            textSize = 14f
            setTextIsSelectable(true)
        }
        body.addView(nameLine)
        body.addView(time)
        if (comment.stamp != null) {
            val stamp = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            data.resolve(comment.snapshotStampUrl())?.let { stampFile ->
                Glide.with(this).load(stampFile).into(stamp)
            }
            body.addView(stamp)
        } else {
            body.addView(content)
        }
        row.addView(body)
        container.addView(row)

        if (avatarFile != null) Glide.with(this).load(avatarFile).into(avatar)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ARG_SNAPSHOT_ID = "snapshotId"
    }
}