package ceui.pixiv.ui.library

import android.content.ClipData
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Common
import ceui.pixiv.db.mirror.BookmarkFilter
import ceui.pixiv.db.mirror.BookmarkMirrorEntity
import ceui.pixiv.db.mirror.MirrorRestrict
import ceui.pixiv.witstudio.dialog.WitBottomSheet
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.label
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlinx.coroutines.currentCoroutineContext

/**
 * 只导出关注库当前书架、当前筛选和排序的本地镜像结果。胶囊点按多选，长按拖动改变文本字段顺序。
 */
internal object FollowingExportSheet {
    private enum class Field(val title: Int) {
        ID(R.string.following_export_id),
        NAME(R.string.following_export_name),
        ACCOUNT(R.string.following_export_account),
    }

    private data class Option(val field: Field, var selected: Boolean = true)

    fun show(fragment: Fragment, viewModel: BookmarkLibraryViewModel) {
        val context = fragment.requireContext()
        val filter = viewModel.filter.value
        val options = Field.entries.map { Option(it) }.toMutableList()
        val parts = LibraryFilterViews(context)
        val palette = V3Palette.from(context)
        val sheet = WitBottomSheet(context)
        val owner = fragment.viewLifecycleOwner
        val observer = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                sheet.dismiss()
            }
        }
        owner.lifecycle.addObserver(observer)
        sheet.setOnDismissListener {
            owner.lifecycle.removeObserver(observer)
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(20), context.dp(4), context.dp(20), context.dp(20))
        }
        content.addView(
            context.label(context.getString(R.string.following_export_title), 20f, 700).apply {
                ViewCompat.setAccessibilityHeading(this, true)
            })
        val shelfLabel = if (viewModel.shelf.restrict == MirrorRestrict.PUBLIC) {
            R.string.public_like_user
        } else {
            R.string.private_like_user
        }
        content.addView(
            context.label(context.getString(shelfLabel), 13f, 600, palette.textAccent),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) },
        )
        content.addView(
            context.label(
                context.getString(R.string.following_export_hint),
                13f, 400, context.color(R.color.v3_text_2),
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(6) },
        )
        if (viewModel.mirrorState.value?.isFirstSyncDone != true) {
            content.addView(
                context.label(
                    context.getString(R.string.following_export_partial),
                    12f, 400, context.color(R.color.v3_text_2),
                ),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) },
            )
        }

        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            isNestedScrollingEnabled = false
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val adapter = object : RecyclerView.Adapter<FieldHolder>() {
            override fun onCreateViewHolder(
                parent: android.view.ViewGroup,
                viewType: Int
            ): FieldHolder {
                val chip = parts.filterChip("", pillShape = true) { }
                chip.layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                    bottomMargin = context.dp(4)
                }
                return FieldHolder(chip)
            }

            override fun getItemCount(): Int = options.size

            override fun onBindViewHolder(holder: FieldHolder, position: Int) {
                val option = options[position]
                holder.chip.text = context.getString(option.field.title)
                parts.renderChip(holder.chip, option.selected)
                val handle = ContextCompat.getDrawable(context, R.drawable.ic_drag_handle_24)
                    ?.mutate()?.apply {
                        setTint(context.color(R.color.v3_text_2))
                        setBounds(0, 0, context.dp(20), context.dp(20))
                    }
                holder.chip.setCompoundDrawablesRelative(
                    holder.chip.compoundDrawablesRelative[0], null, handle, null,
                )
                holder.chip.contentDescription =
                    context.getString(option.field.title) + " · " +
                            context.getString(if (option.selected) R.string.following_export_selected else R.string.following_export_unselected)
                ViewCompat.setAccessibilityDelegate(
                    holder.chip,
                    object : AccessibilityDelegateCompat() {
                        override fun onInitializeAccessibilityNodeInfo(
                            host: View,
                            info: AccessibilityNodeInfoCompat
                        ) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.className = CheckBox::class.java.name
                            info.isCheckable = true
                            info.isChecked = option.selected
                        }
                    })
                holder.chip.setOnClickListener {
                    val index = holder.bindingAdapterPosition
                    if (index == RecyclerView.NO_POSITION) return@setOnClickListener
                    if (options[index].selected && options.count { it.selected } == 1) {
                        Common.showToast(context.getString(R.string.following_export_one_required))
                    } else {
                        options[index].selected = !options[index].selected
                        notifyItemChanged(index)
                    }
                }
            }
        }
        list.adapter = adapter
        ItemTouchHelper(object :
            ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                options.add(to, options.removeAt(from))
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        }).attachToRecyclerView(list)
        content.addView(list, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = context.dp(14)
            bottomMargin = context.dp(12)
        })

        val copyButton = TextView(context).apply {
            text = context.getString(R.string.following_export_copy)
            gravity = Gravity.CENTER
        }
        parts.stylePrimaryAction(copyButton)
        content.addView(copyButton, LinearLayout.LayoutParams(-1, -2))
        copyButton.setOnClickListener {
            val fields = options.filter { it.selected }.map { it.field }
            if (fields.isEmpty()) {
                Common.showToast(context.getString(R.string.following_export_one_required))
                return@setOnClickListener
            }
            copyButton.isEnabled = false
            copyButton.text = context.getString(R.string.following_export_preparing)
            // 窗口关闭时同步取消；OnDismissListener 要等下一条主线程消息才回调。
            sheet.lifecycleScope.launch {
                try {
                    val (text, count) = buildExport(filter, fields)
                    if (count == 0) {
                        Common.showToast(context.getString(R.string.following_export_empty))
                    } else if (ClipBoardUtils.setPrimaryClip(
                            context,
                            ClipData.newPlainText("following-list", text)
                        )
                    ) {
                        sheet.dismiss()
                        Common.showToast(context.getString(R.string.following_export_copied, count))
                    } else {
                        Common.showToast(context.getString(R.string.msg_copy_failed))
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Timber.tag("FollowingExport").w(t, "复制关注列表失败")
                    Common.showToast(context.getString(R.string.msg_copy_failed))
                } finally {
                    copyButton.isEnabled = true
                    copyButton.text = context.getString(R.string.following_export_copy)
                }
            }
        }

        sheet.setSheetContent(NestedScrollView(context).apply {
            isFillViewport = true
            addView(content)
        })
        sheet.show()
    }

    private class FieldHolder(val chip: TextView) : RecyclerView.ViewHolder(chip)

    private suspend fun buildExport(
        filter: BookmarkFilter,
        fields: List<Field>
    ): Pair<String, Int> =
        BookmarkLibraryRepo.withReadSnapshot(filter) { readPage ->
            val output = StringBuilder()
            var offset = 0
            var exported = 0
            do {
                currentCoroutineContext().ensureActive()
                val rows = readPage(PAGE_SIZE, offset)
                rows.forEach { row ->
                    // 与屏幕上的 UserFeedItem 一致：坏 payload / 没有身份的行不算结果。
                    val user = BookmarkLibraryRepo.toUserPreview(row)?.user ?: return@forEach
                    if (output.isNotEmpty()) output.append('\n')
                    output.append(formatRow(row, user.account, fields))
                    exported++
                }
                offset += rows.size
            } while (rows.size == PAGE_SIZE)
            output.toString() to exported
        }

    private fun formatRow(
        row: BookmarkMirrorEntity,
        account: String?,
        fields: List<Field>
    ): String =
        fields.joinToString(" · ") { field ->
            when (field) {
                Field.ID -> row.targetId.toString()
                Field.NAME -> row.title.oneLine().ifEmpty { "—" }
                Field.ACCOUNT -> account.orEmpty().oneLine().removePrefix("@")
                    .let { if (it.isEmpty()) "—" else "@$it" }
            }
        }

    private fun String.oneLine(): String = trim().replace(Regex("\\s+"), " ")

    private const val PAGE_SIZE = 300
}
