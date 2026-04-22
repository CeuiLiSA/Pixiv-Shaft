package ceui.pixiv.ui.settings

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.FragmentNovelHeaderSettingsBinding
import ceui.loxia.Novel
import ceui.loxia.Series
import ceui.loxia.Tag
import ceui.loxia.User
import ceui.pixiv.download.header.HeaderConfigRepo
import ceui.pixiv.download.header.HeaderConfigStore
import ceui.pixiv.download.header.HeaderField
import ceui.pixiv.download.header.HeaderPreset
import ceui.pixiv.download.header.NovelHeaderRenderer
import ceui.pixiv.ui.common.viewBinding
import com.hjq.toast.ToastUtils

/**
 * "下载内容信息头设置" — lets the user choose which metadata fields are
 * written to the top of every downloaded novel TXT and in what order.
 *
 * Design notes:
 *   - Style matches [DownloadPathSettingsFragment] (V3 palette, rounded
 *     cards, pill buttons, Montserrat-bold section titles).
 *   - Presets are plural. A chip row at the top switches active preset;
 *     separate pills add / rename / delete.
 *   - The field list is a RecyclerView with a checkbox + drag handle per
 *     row. Reordering is driven by ItemTouchHelper (UP | DOWN). Changes
 *     are staged in [draftFields] / [draftPresetName] until the user hits
 *     "保存" — this avoids thrashing MMKV on every toggle.
 *   - A live preview card renders the current draft against a sample
 *     novel (both a standalone and a series variant) so users can see the
 *     effect of their choices immediately.
 */
class NovelHeaderSettingsFragment : Fragment(R.layout.fragment_novel_header_settings) {

    private val binding by viewBinding(FragmentNovelHeaderSettingsBinding::bind)

    // ----------------- Editing state -----------------

    /** Snapshot loaded from disk — only replaced after a successful save. */
    private var store: HeaderConfigStore = HeaderConfigRepo.defaultStore()

    /** Name of the preset the user is currently editing. */
    private var draftPresetName: String = HeaderConfigRepo.DEFAULT_PRESET_NAME

    /**
     * Mutable ordered list of fields under edit. Items in this list are
     * "checked"; items in [availableFields] are not. Reorder only applies
     * to [draftFields].
     */
    private val draftFields: MutableList<HeaderField> = mutableListOf()

    /** Fields NOT in the preset (shown greyed out at the bottom of the list). */
    private val availableFields: MutableList<HeaderField> = mutableListOf()

    private var fieldsAdapter: FieldsAdapter? = null
    private var touchHelper: ItemTouchHelper? = null
    private var previewStandalone: TextView? = null
    private var previewSeries: TextView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = binding.toolbarLayout
        toolbar.naviTitle.apply {
            text = getString(R.string.novel_header_settings_title)
            setTextColor(resources.getColor(R.color.v3_text_1, null))
            setTextAppearance(R.style.textMontserratBold)
            textSize = 18f
        }
        (toolbar.naviBack as ImageView).setColorFilter(resources.getColor(R.color.v3_text_1, null))
        toolbar.naviBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        toolbar.naviMore.visibility = View.GONE

        ViewCompat.setOnApplyWindowInsetsListener(toolbar.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + dp(10))
            insets
        }
        ViewCompat.requestApplyInsets(toolbar.root)

        store = HeaderConfigRepo.load()
        draftPresetName = store.activeName
        loadDraftFromActive()
        render()
    }

    private fun loadDraftFromActive() {
        val preset = store.presets.firstOrNull { it.name == draftPresetName }
            ?: store.presets.first().also { draftPresetName = it.name }
        draftFields.clear()
        draftFields.addAll(preset.fields.distinct())
        availableFields.clear()
        availableFields.addAll(HeaderField.ALL.filter { it !in draftFields })
    }

    // ----------------- Rendering -----------------

    private fun render() {
        val root = binding.contentRoot
        root.removeAllViews()

        addSectionTitle(root, getString(R.string.novel_header_section_presets), topDp = 2)
        addPresetsCard(root)

        addSectionTitle(root, getString(R.string.novel_header_section_fields), topDp = 18)
        addFieldsCard(root)

        addSectionTitle(root, getString(R.string.novel_header_section_preview), topDp = 18)
        addPreviewCard(root)

        addFooterButtons(root)
    }

    // -------- Presets section --------

    private fun addPresetsCard(root: LinearLayout) {
        val card = cardContainer()

        // Horizontal chip row of preset names
        val scroll = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        for (preset in store.presets) {
            val chip = TextView(requireContext()).apply {
                text = preset.name
                textSize = 13f
                setTextColor(
                    if (preset.name == draftPresetName) 0xFFFFFFFF.toInt()
                    else resources.getColor(R.color.v3_text_1, null)
                )
                setBackgroundResource(
                    if (preset.name == draftPresetName) R.drawable.bg_v3_pill_primary
                    else R.drawable.bg_v3_pill_secondary
                )
                setPadding(dp(16), dp(8), dp(16), dp(8))
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, 0, dp(6), 0) }
                setOnClickListener { switchToPreset(preset.name) }
            }
            row.addView(chip)
        }
        scroll.addView(row)
        card.addView(scroll)

        // Action pills row: new / rename / delete
        val actions = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }
        actions.addView(actionPill(getString(R.string.novel_header_preset_new)) { onNewPreset() })
        actions.addView(actionPill(getString(R.string.novel_header_preset_rename)) { onRenamePreset() })
        actions.addView(actionPill(getString(R.string.novel_header_preset_delete)) { onDeletePreset() })
        card.addView(actions)

        root.addView(card)
    }

    private fun actionPill(text: String, onClick: () -> Unit): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFF6C5CE7.toInt())
            setBackgroundResource(R.drawable.bg_v3_pill_secondary)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, dp(6), 0) }
            setOnClickListener { onClick() }
        }
    }

    private fun switchToPreset(name: String) {
        // Persist the in-flight draft into the outgoing preset before we
        // swap — otherwise reordering work is silently lost when the user
        // taps another chip.
        commitDraftIntoStore(persist = false)
        draftPresetName = name
        loadDraftFromActive()
        render()
    }

    private fun onNewPreset() {
        promptForName(
            title = getString(R.string.novel_header_preset_new),
            initial = "",
            hint = getString(R.string.novel_header_preset_name_hint),
        ) { entered ->
            if (store.presets.any { it.name == entered }) {
                ToastUtils.show(getString(R.string.novel_header_preset_name_taken))
                return@promptForName
            }
            commitDraftIntoStore(persist = false)
            val newPreset = HeaderPreset(entered, HeaderField.ALL)
            store = store.copy(
                presets = store.presets + newPreset,
                activeName = entered,
            )
            draftPresetName = entered
            loadDraftFromActive()
            HeaderConfigRepo.save(store)
            render()
        }
    }

    private fun onRenamePreset() {
        promptForName(
            title = getString(R.string.novel_header_preset_rename),
            initial = draftPresetName,
            hint = getString(R.string.novel_header_preset_name_hint),
        ) { entered ->
            if (entered == draftPresetName) return@promptForName
            if (store.presets.any { it.name == entered }) {
                ToastUtils.show(getString(R.string.novel_header_preset_name_taken))
                return@promptForName
            }
            commitDraftIntoStore(persist = false)
            val renamed = store.presets.map {
                if (it.name == draftPresetName) it.copy(name = entered) else it
            }
            store = store.copy(presets = renamed, activeName = entered)
            draftPresetName = entered
            HeaderConfigRepo.save(store)
            render()
        }
    }

    private fun onDeletePreset() {
        if (store.presets.size <= 1) {
            ToastUtils.show(getString(R.string.novel_header_preset_delete_last))
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.novel_header_preset_delete))
            .setMessage(getString(R.string.novel_header_preset_delete_confirm, draftPresetName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val remaining = store.presets.filter { it.name != draftPresetName }
                store = store.copy(presets = remaining, activeName = remaining.first().name)
                draftPresetName = store.activeName
                loadDraftFromActive()
                HeaderConfigRepo.save(store)
                render()
            }
            .show()
    }

    private fun promptForName(
        title: String,
        initial: String,
        hint: String,
        onConfirm: (String) -> Unit,
    ) {
        val edit = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText(initial)
            this.hint = hint
            setSelection(initial.length)
        }
        val container = FrameLayout(requireContext()).apply {
            setPadding(dp(20), dp(8), dp(20), dp(0))
            addView(edit)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val entered = edit.text?.toString()?.trim().orEmpty()
                if (entered.isBlank()) {
                    ToastUtils.show(getString(R.string.novel_header_preset_name_blank))
                } else {
                    onConfirm(entered)
                }
            }
            .show()
    }

    // -------- Fields section --------

    private fun addFieldsCard(root: LinearLayout) {
        val card = cardContainer()
        card.addView(bodyText(getString(R.string.novel_header_fields_hint)))

        val rv = RecyclerView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
            layoutManager = LinearLayoutManager(requireContext())
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val adapter = FieldsAdapter()
        fieldsAdapter = adapter
        rv.adapter = adapter

        touchHelper = ItemTouchHelper(DragCallback(adapter)).also { it.attachToRecyclerView(rv) }
        card.addView(rv)

        root.addView(card)
    }

    // -------- Preview section --------

    private fun addPreviewCard(root: LinearLayout) {
        val card = cardContainer()

        card.addView(subHeader(getString(R.string.novel_header_preview_standalone), topDp = 0))
        val standalone = previewTextView()
        previewStandalone = standalone
        card.addView(standalone)

        card.addView(subHeader(getString(R.string.novel_header_preview_series), topDp = 14))
        val series = previewTextView()
        previewSeries = series
        card.addView(series)

        root.addView(card)
        refreshPreviews()
    }

    private fun previewTextView(): TextView = TextView(requireContext()).apply {
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setTextColor(resources.getColor(R.color.v3_text_2, null))
        setBackgroundResource(R.drawable.bg_v3_chip)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setLineSpacing(0f, 1.25f)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) }
    }

    private fun refreshPreviews() {
        val preset = HeaderPreset(draftPresetName, draftFields.toList())
        previewStandalone?.text = NovelHeaderRenderer.render(
            novel = SAMPLE_STANDALONE,
            preset = preset,
            isSeriesChapter = false,
        )
        previewSeries?.text = NovelHeaderRenderer.render(
            novel = SAMPLE_SERIES,
            preset = preset,
            isSeriesChapter = true,
            seriesIndex = 2,
            seriesTotal = 7,
        )
    }

    // -------- Footer buttons --------

    private fun addFooterButtons(root: LinearLayout) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(22) }
        }
        val save = TextView(requireContext()).apply {
            text = getString(R.string.novel_header_save)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.bg_v3_pill_primary)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(28), dp(14), dp(28), dp(14))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(12) }
            setOnClickListener { onSave() }
        }
        val reset = TextView(requireContext()).apply {
            text = getString(R.string.novel_header_reset)
            setTextColor(0xFF6C5CE7.toInt())
            setBackgroundResource(R.drawable.bg_v3_pill_secondary)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(28), dp(14), dp(28), dp(14))
            textSize = 14f
            setOnClickListener { onResetDefault() }
        }
        row.addView(save)
        row.addView(reset)
        root.addView(row)
    }

    private fun onSave() {
        commitDraftIntoStore(persist = true)
        ToastUtils.show(getString(R.string.novel_header_saved))
    }

    private fun commitDraftIntoStore(persist: Boolean) {
        val updatedPreset = HeaderPreset(draftPresetName, draftFields.toList())
        val newPresets = if (store.presets.any { it.name == draftPresetName }) {
            store.presets.map { if (it.name == draftPresetName) updatedPreset else it }
        } else {
            store.presets + updatedPreset
        }
        store = store.copy(presets = newPresets, activeName = draftPresetName)
        if (persist) HeaderConfigRepo.save(store)
    }

    private fun onResetDefault() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.novel_header_reset))
            .setMessage(getString(R.string.novel_header_reset_confirm))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                HeaderConfigRepo.reset()
                store = HeaderConfigRepo.load()
                draftPresetName = store.activeName
                loadDraftFromActive()
                render()
                ToastUtils.show(getString(R.string.novel_header_reset_done))
            }
            .show()
    }

    // ----------------- RecyclerView plumbing -----------------

    private inner class FieldsAdapter : RecyclerView.Adapter<FieldVH>() {

        override fun getItemCount(): Int = draftFields.size + availableFields.size

        private fun fieldAt(position: Int): HeaderField =
            if (position < draftFields.size) draftFields[position]
            else availableFields[position - draftFields.size]

        private fun isChecked(position: Int): Boolean = position < draftFields.size

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): FieldVH {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                )
            }
            val cb = CheckBox(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            val label = TextView(ctx).apply {
                textSize = 14f
                setTextColor(resources.getColor(R.color.v3_text_1, null))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply { marginStart = dp(4) }
            }
            val badge = TextView(ctx).apply {
                textSize = 11f
                text = getString(R.string.novel_header_badge_series)
                setTextColor(0xFF6C5CE7.toInt())
                setBackgroundResource(R.drawable.bg_v3_chip)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) }
            }
            val handle = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_drag_handle_24)
                setColorFilter(resources.getColor(R.color.v3_text_3, null))
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            }
            row.addView(cb)
            row.addView(label)
            row.addView(badge)
            row.addView(handle)
            return FieldVH(row, cb, label, badge, handle)
        }

        override fun onBindViewHolder(holder: FieldVH, position: Int) {
            val field = fieldAt(position)
            val checked = isChecked(position)
            holder.checkbox.setOnCheckedChangeListener(null)
            holder.checkbox.isChecked = checked
            holder.label.text = labelFor(field)
            holder.label.alpha = if (checked) 1f else 0.5f
            holder.badge.visibility =
                if (HeaderField.isSeriesOnly(field)) View.VISIBLE else View.GONE
            holder.handle.alpha = if (checked) 1f else 0.25f

            holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                toggleField(field, isChecked)
            }

            // Drag only from the handle — touching the row elsewhere must
            // not fight with the checkbox / list scroll.
            holder.handle.setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN && checked) {
                    touchHelper?.startDrag(holder)
                }
                false
            }
        }

        fun swap(from: Int, to: Int): Boolean {
            // Only rows in the "draft" range participate in reorder.
            if (from >= draftFields.size || to >= draftFields.size) return false
            val item = draftFields.removeAt(from)
            draftFields.add(to, item)
            notifyItemMoved(from, to)
            refreshPreviews()
            return true
        }

        private fun toggleField(field: HeaderField, enable: Boolean) {
            if (enable) {
                if (field !in draftFields) {
                    draftFields.add(field)
                    availableFields.remove(field)
                }
            } else {
                draftFields.remove(field)
                if (field !in availableFields) availableFields.add(field)
            }
            notifyDataSetChanged()
            refreshPreviews()
        }
    }

    private class FieldVH(
        itemView: View,
        val checkbox: CheckBox,
        val label: TextView,
        val badge: TextView,
        val handle: ImageView,
    ) : RecyclerView.ViewHolder(itemView)

    private inner class DragCallback(private val adapter: FieldsAdapter) : ItemTouchHelper.Callback() {
        override fun isLongPressDragEnabled(): Boolean = false
        override fun isItemViewSwipeEnabled(): Boolean = false

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int = makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean = adapter.swap(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
    }

    private fun labelFor(field: HeaderField): String = when (field) {
        HeaderField.Title       -> getString(R.string.novel_header_field_title)
        HeaderField.Author      -> getString(R.string.novel_header_field_author)
        HeaderField.AuthorId    -> getString(R.string.novel_header_field_author_id)
        HeaderField.NovelId     -> getString(R.string.novel_header_field_novel_id)
        HeaderField.NovelLink   -> getString(R.string.novel_header_field_novel_link)
        HeaderField.Caption     -> getString(R.string.novel_header_field_caption)
        HeaderField.PublishTime -> getString(R.string.novel_header_field_publish_time)
        HeaderField.TextLength  -> getString(R.string.novel_header_field_text_length)
        HeaderField.Tags        -> getString(R.string.novel_header_field_tags)
        HeaderField.SeriesTitle -> getString(R.string.novel_header_field_series_title)
        HeaderField.SeriesIndex -> getString(R.string.novel_header_field_series_index)
    }

    // ----------------- Helpers -----------------

    private fun cardContainer(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_v3_card)
        setPadding(dp(20), dp(18), dp(20), dp(18))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(4) }
    }

    private fun addSectionTitle(root: LinearLayout, text: String, topDp: Int) {
        val title = TextView(requireContext()).apply {
            this.text = text
            setTextAppearance(R.style.textMontserratBold)
            textSize = 15f
            setTextColor(resources.getColor(R.color.v3_text_1, null))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(topDp); bottomMargin = dp(10); leftMargin = dp(4) }
            letterSpacing = 0.02f
            isAllCaps = false
        }
        root.addView(title)
    }

    private fun bodyText(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 13f
        setTextColor(resources.getColor(R.color.v3_text_2, null))
        setLineSpacing(0f, 1.35f)
    }

    private fun subHeader(text: String, topDp: Int) = TextView(requireContext()).apply {
        this.text = text
        textSize = 13f
        setTextColor(resources.getColor(R.color.v3_text_1, null))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(topDp), 0, dp(6))
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    companion object {
        private val SAMPLE_STANDALONE = Novel(
            id = 12345678L,
            title = "示例单篇小说",
            caption = "这是简介，<br/>支持多行。",
            create_date = "2025-04-21T12:00:00+09:00",
            text_length = 3214,
            tags = listOf(
                Tag(name = "原创"),
                Tag(name = "百合"),
                Tag(name = "日常"),
            ),
            user = User(id = 987654L, name = "示例作者"),
        )

        private val SAMPLE_SERIES = SAMPLE_STANDALONE.copy(
            id = 87654321L,
            title = "示例系列 · 第三章",
            series = Series(id = 24680L, title = "示例系列合集"),
        )
    }
}
