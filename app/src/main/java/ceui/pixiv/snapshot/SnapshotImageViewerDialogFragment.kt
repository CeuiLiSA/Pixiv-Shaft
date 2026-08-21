package ceui.pixiv.snapshot

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ceui.lisa.R
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 快照本地大图查看器：只读快照里已经落盘的图片，绝不联网。
 * 多 P 支持左右滑动，点击关闭按钮或系统返回关闭。
 */
class SnapshotImageViewerDialogFragment : DialogFragment() {

    private val snapshotId by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_SNAPSHOT_ID)
            ?: throw IllegalArgumentException("缺少 snapshotId")
    }

    private val initialIndex by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getInt(ARG_INDEX, 0)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_snapshot_image_viewer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }

        val pager = view.findViewById<ViewPager2>(R.id.pager)
        val close = view.findViewById<ImageView>(R.id.closeButton)
        val indicator = view.findViewById<TextView>(R.id.pageIndicator)
        close.setOnClickListener { dismiss() }

        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                SnapshotRepository.loadViewerData(requireContext(), snapshotId)
            }
            val files = (0 until data.illust.page_count.coerceAtLeast(1)).mapNotNull { index ->
                val url = data.illust.snapshotPageUrl(index, data.manifest.includeOriginal)
                data.resolve(url)
            }
            if (files.isEmpty() || view == null) {
                dismiss()
                return@launch
            }
            val adapter = SnapshotImagePagerAdapter(files)
            pager.adapter = adapter
            val safeIndex = initialIndex.coerceIn(0, files.lastIndex)
            pager.setCurrentItem(safeIndex, false)
            indicator.text = getString(R.string.snapshot_page_indicator_format, safeIndex + 1, files.size)
            pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    indicator.text = getString(
                        R.string.snapshot_page_indicator_format, position + 1, files.size
                    )
                }
            })
        }
    }

    companion object {
        const val ARG_SNAPSHOT_ID = "snapshotId"
        const val ARG_INDEX = "index"

        fun show(manager: FragmentManager, snapshotId: String, index: Int) {
            SnapshotImageViewerDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SNAPSHOT_ID, snapshotId)
                    putInt(ARG_INDEX, index)
                }
            }.show(manager, "SnapshotImageViewer")
        }
    }
}

private class SnapshotImagePagerAdapter(
    private val files: List<File>,
) : RecyclerView.Adapter<SnapshotImagePagerAdapter.SnapshotImageVH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SnapshotImageVH {
        val imageView = ImageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        return SnapshotImageVH(imageView)
    }

    override fun onBindViewHolder(holder: SnapshotImageVH, position: Int) {
        Glide.with(holder.imageView).load(files[position]).into(holder.imageView)
    }

    override fun onViewRecycled(holder: SnapshotImageVH) {
        Glide.with(holder.imageView.context).clear(holder.imageView)
    }

    override fun getItemCount(): Int = files.size

    class SnapshotImageVH(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)
}