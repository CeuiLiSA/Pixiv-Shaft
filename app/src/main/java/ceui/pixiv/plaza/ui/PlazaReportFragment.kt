package ceui.pixiv.plaza.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.pixiv.chat.base.launchSuspend
import ceui.pixiv.witstudio.theme.V3Palette
import com.bumptech.glide.Glide

/** Full-page V3 report form. The ViewModel retains drafts, uploads and the server receipt. */
class PlazaReportFragment : Fragment(R.layout.fragment_plaza_shell) {
    private val model: PlazaModerationModel by viewModels()
    private val picker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { uris ->
        val resolver = context?.applicationContext?.contentResolver ?: return@registerForActivityResult
        uris.take(3).forEach { uri ->
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        model.attach(uris)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val palette = V3Palette.from(ctx)
        setupPlazaToolbar(view, getString(R.string.plaza_report_title))
        val frame = view.findViewById<FrameLayout>(R.id.plaza_content)
        val page = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(page, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        frame.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = minOf(right - left, ctx.dp(720))
            if (page.layoutParams.width != width)
                page.layoutParams = FrameLayout.LayoutParams(width, -1, Gravity.CENTER_HORIZONTAL)
        }
        val scroll = NestedScrollView(ctx).apply {
            id = R.id.plaza_report_scroll
            isFillViewport = true
            clipToPadding = false
        }
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(24), ctx.dp(20), ctx.dp(24))
        }
        scroll.addView(column)
        fun add(parent: LinearLayout, child: View, top: Int = 0) {
            parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(top) })
        }
        fun heading(text: String, size: Float = 20f) = ctx.label(text, size, 700).apply {
            lineHeightRatio(1.4f)
            ViewCompat.setAccessibilityHeading(this, true)
        }
        val title = heading(getString(if (model.mode == "user") R.string.plaza_report_user else R.string.plaza_report_post), 28f)
        add(column, title)
        val notice = ctx.label(getString(R.string.plaza_report_notice), 14f, color = palette.textSecondary).apply {
            lineHeightRatio(1.7f)
        }
        add(column, notice, 12)
        val form = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        add(column, form)
        val target = ctx.label(getString(R.string.plaza_report_target,
            arguments?.getLong("postId") ?: 0L, arguments?.getLong("targetUid") ?: 0L), 13f, 500, palette.textSecondary).apply {
            background = ctx.card(22)
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
            lineHeightRatio(1.6f)
        }
        add(form, target, 20)
        add(form, heading(getString(R.string.plaza_report_reason_title)), 28)
        val ids = listOf(R.id.plaza_reason_child, R.id.plaza_reason_sexual, R.id.plaza_reason_violence,
            R.id.plaza_reason_hate, R.id.plaza_reason_harassment, R.id.plaza_reason_privacy,
            R.id.plaza_reason_advertising, R.id.plaza_reason_spam, R.id.plaza_reason_illegal, R.id.plaza_reason_other)
        val labels = resources.getStringArray(R.array.plaza_report_reasons)
        val reasons = RadioGroup(ctx).apply { isSaveEnabled = false }
        val radios = plazaReportReasons.mapIndexed { index, _ ->
            RadioButton(ctx).apply {
                id = ids[index]
                text = labels[index]
                textSize = 15f
                typeface = ctx.v3Font(500)
                lineHeightRatio(1.5f)
                minHeight = ctx.dp(56)
                setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(16), ctx.dp(12))
                setTextColor(ctx.color(R.color.v3_text_1))
                buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(palette.textAccent, palette.textSecondary))
                isSaveEnabled = false
                reasons.addView(this, RadioGroup.LayoutParams(-1, -2).apply { topMargin = if (index == 0) 0 else ctx.dp(2) })
            }
        }
        fun styleReasons() = radios.forEachIndexed { index, radio ->
            radio.background = ctx.ripple(
                ctx.rowShape(index, radios.size, if (radio.isChecked) palette.alpha08 else palette.cardFill),
                ctx.rowShape(index, radios.size, android.graphics.Color.WHITE))
        }
        plazaReportReasons.indexOf(model.reason).takeIf { it >= 0 }?.let { reasons.check(ids[it]) }
        styleReasons()
        add(form, reasons, 12)

        add(form, heading(getString(R.string.plaza_report_details_title)), 28)
        val details = AppCompatEditText(ctx).apply {
            id = R.id.plaza_report_details_input
            hint = getString(R.string.plaza_report_details_hint)
            contentDescription = getString(R.string.plaza_report_details_title)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            textSize = 15f
            typeface = ctx.v3Font(400)
            lineHeightRatio(1.7f)
            gravity = Gravity.TOP or Gravity.START
            background = ctx.card(22)
            setTextColor(ctx.color(R.color.v3_text_1))
            setHintTextColor(palette.textSecondary)
            setPadding(ctx.dp(18), ctx.dp(16), ctx.dp(18), ctx.dp(16))
            minimumHeight = ctx.dp(168)
            minLines = 5
            filters = arrayOf(InputFilter.LengthFilter(1000))
            isSaveEnabled = false // SavedStateHandle is the only draft source.
            setText(model.details)
        }
        add(form, details, 12)
        val counter = ctx.label("", 12f, 500, palette.textSecondary).apply {
            gravity = Gravity.END
            fontFeatureSettings = "tnum"
        }
        add(form, counter, 8)

        add(form, heading(getString(R.string.plaza_report_photos_title)), 28)
        add(form, ctx.label(getString(R.string.plaza_report_photos_hint), 13f, color = palette.textSecondary).apply {
            lineHeightRatio(1.6f)
        }, 8)
        val photoScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val photos = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        photoScroll.addView(photos)
        add(form, photoScroll, 12)
        val addPhoto = ctx.pillButton(getString(R.string.plaza_add_photos, 3), false, R.drawable.ic_add_black_24dp) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        addPhoto.id = R.id.plaza_report_add_photos
        add(form, addPhoto, 12)

        val receipt = ctx.label("", 16f, 500).apply {
            id = R.id.plaza_report_receipt
            background = ctx.card(22)
            setPadding(ctx.dp(24), ctx.dp(28), ctx.dp(24), ctx.dp(28))
            lineHeightRatio(1.8f)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            isVisible = false
        }
        add(column, receipt, 28)
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(16))
        }
        val status = ctx.label("", 13f, 500, ctx.color(R.color.v3_danger)).apply {
            lineHeightRatio(1.5f)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        add(footer, status)
        val submit = ctx.pillButton(getString(R.string.plaza_report_submit)) {
            if (model.state.value.done) requireActivity().finish()
            else model.submit(resolver = ctx.applicationContext.contentResolver)
        }.apply { id = R.id.plaza_report_submit_button; minHeight = ctx.dp(52) }
        add(footer, submit, 8)
        page.addView(footer, LinearLayout.LayoutParams(-1, -2))
        val back = object : OnBackPressedCallback(model.state.value.busy) {
            override fun handleOnBackPressed() { status.text = getString(R.string.plaza_report_sending) }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        fun updateInput() {
            counter.text = getString(R.string.plaza_char_count, model.details.length, 1000)
            submit.isEnabled = !model.state.value.busy && (model.state.value.done || model.reason in plazaReportReasons)
            submit.alpha = if (submit.isEnabled) 1f else .5f
        }
        reasons.setOnCheckedChangeListener { _, checked ->
            ids.indexOf(checked).takeIf { it >= 0 }?.let { model.reason = plazaReportReasons[it] }
            styleReasons()
            updateInput()
        }
        details.doAfterTextChanged { model.details = it?.toString().orEmpty(); updateInput() }
        var photoKeys: List<String>? = null
        val photoProgress = mutableMapOf<String, TextView>()
        fun render(state: ModerationState) {
            back.isEnabled = state.busy
            form.isVisible = !state.done
            notice.isVisible = !state.done
            receipt.isVisible = state.done
            if (state.done) {
                title.text = getString(R.string.plaza_report_received_title)
                receipt.text = getString(if (state.receiptStatus == "pending") R.string.plaza_report_success
                    else R.string.plaza_report_reviewed, state.receiptId)
            }
            status.text = state.error?.resolve(ctx).orEmpty()
            status.isVisible = state.error != null
            submit.text = getString(when { state.done -> R.string.plaza_report_finish
                state.busy -> R.string.plaza_report_sending; else -> R.string.plaza_report_submit })
            details.isEnabled = !state.busy
            radios.forEach { it.isEnabled = !state.busy }
            addPhoto.isVisible = state.images.size < 3
            addPhoto.isEnabled = !state.busy
            val keys = state.images.map { it.uri }
            if (photoKeys != keys) {
                photoKeys = keys
                photos.removeAllViews()
                photoProgress.clear()
                state.images.forEachIndexed { index, image ->
                    val tile = FrameLayout(ctx).apply { background = ctx.card(16); clipToOutline = true }
                    val thumb = ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription = getString(R.string.plaza_selected_photo, index + 1)
                    }
                    tile.addView(thumb, FrameLayout.LayoutParams(-1, -1))
                    Glide.with(thumb).load(Uri.parse(image.uri)).override(ctx.dp(104)).into(thumb)
                    val remove = ImageView(ctx).apply {
                        tag = "remove"
                        setImageResource(R.drawable.ic_close_black_24dp)
                        imageTintList = ColorStateList.valueOf(palette.textAccent)
                        background = ctx.cardSurface(999)
                        setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
                        contentDescription = getString(R.string.plaza_remove_photo, index + 1)
                        isFocusable = true
                        setOnClickListener { model.removeImage(image.uri) }
                    }
                    tile.addView(remove, FrameLayout.LayoutParams(ctx.dp(48), ctx.dp(48), Gravity.TOP or Gravity.END))
                    val progress = ctx.label("", 12f, 600, palette.onPrimary).apply {
                        gravity = Gravity.CENTER
                        setBackgroundColor(palette.primary)
                    }
                    photoProgress[image.uri] = progress
                    tile.addView(progress, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                    photos.addView(tile, LinearLayout.LayoutParams(ctx.dp(104), ctx.dp(104)).apply { marginEnd = ctx.dp(8) })
                }
            }
            photoScroll.isVisible = state.images.isNotEmpty()
            state.images.forEachIndexed { index, image ->
                photos.getChildAt(index).findViewWithTag<View>("remove").isEnabled = !state.busy
                photoProgress[image.uri]?.apply {
                    isVisible = state.busy
                    text = getString(R.string.plaza_upload_progress, image.progress)
                }
            }
            updateInput()
        }
        render(model.state.value)
        launchSuspend { model.state.collect(::render) }
    }
}
