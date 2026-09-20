package ceui.pixiv.plaza.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
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
import ceui.pixiv.witstudio.theme.*
import ceui.pixiv.witstudio.theme.V3Palette
import com.bumptech.glide.Glide

/**
 * 举报表单，V3「设置 / 表单」配方：页面标题 → 一句说明 → 举报对象 → 三个分区 → 贴底主操作。
 *
 * 原因是连通分段行（20/5 圆角、2dp 行隙），选中项换成主题浅底容器加实心对勾指示器；
 * 照片证据是三个等分方格，空位本身就是「添加」槽，不再额外挂一颗会跟着照片数跳位的胶囊；
 * 提交成功后整页换成成功徽章加举报编号，而不是在原表单上改几个字。
 *
 * ViewModel 持有草稿、上传与服务端回执，这里只负责呈现。
 */
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
            setPadding(ctx.dp(20), ctx.dp(24), ctx.dp(20), ctx.dp(16))
        }
        scroll.addView(column)
        fun add(parent: LinearLayout, child: View, top: Int = 0) {
            parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(top) })
        }
        val reportingUser = model.mode == "user"

        // ── 标题与说明 ────────────────────────────────────────────────
        val title = ctx.label(
            getString(if (reportingUser) R.string.plaza_report_user else R.string.plaza_report_post),
            28f, 700,
        ).apply {
            lineHeightRatio(1.35f)
            ViewCompat.setAccessibilityHeading(this, true)
        }
        add(column, title)
        // 说明性正文用中性 muted：整页只有主操作、选中态和图标带主题色，长段落染色会读成链接。
        val notice = ctx.label(getString(R.string.plaza_report_notice), 14f, color = ctx.color(R.color.v3_text_2))
            .apply { lineHeightRatio(1.7f) }
        add(column, notice, 12)
        val form = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        add(column, form)

        // ── 举报对象 ─────────────────────────────────────────────────
        val targetCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ctx.card(22)
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
            addView(
                ctx.iconTile(if (reportingUser) R.drawable.ic_setcat_person else R.drawable.ic_baseline_flag_24),
                LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)),
            )
            val text = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            text.addView(
                ctx.label(getString(R.string.plaza_report_target_label), 12f, 500, ctx.color(R.color.v3_text_2))
                    .apply { lineHeightRatio(1.4f) },
                LinearLayout.LayoutParams(-1, -2),
            )
            // 举报作者时帖子编号只是噪音，只报作者；举报帖子/评论才要帖子编号定位。
            val targetUid = arguments?.getLong("targetUid") ?: 0L
            text.addView(
                ctx.label(
                    if (reportingUser) getString(R.string.plaza_report_target_author, targetUid)
                    else getString(R.string.plaza_report_target, arguments?.getLong("postId") ?: 0L, targetUid),
                    15f, 600,
                ).apply { lineHeightRatio(1.5f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(2) },
            )
            addView(text, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ctx.dp(14) })
        }
        add(form, targetCard, 24)

        // ── 举报原因（连通分段行） ────────────────────────────────────
        add(form, ctx.formSection(
            getString(R.string.plaza_report_reason_label),
            getString(R.string.plaza_form_required),
            required = true,
        ), 28)
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
                lineHeightRatio(1.5f)
                minHeight = ctx.dp(56)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(18), ctx.dp(14), ctx.dp(18), ctx.dp(14))
                // 系统圆点换成末端指示器；控件仍是 RadioButton，读屏、分组与状态语义不变。
                buttonDrawable = null
                compoundDrawablePadding = ctx.dp(16)
                setCompoundDrawablesRelative(null, null, ctx.selectionIndicator(), null)
                isSaveEnabled = false
                reasons.addView(this, RadioGroup.LayoutParams(-1, -2).apply { topMargin = if (index == 0) 0 else ctx.dp(2) })
            }
        }
        fun styleReasons() = radios.forEachIndexed { index, radio ->
            val checked = radio.isChecked
            radio.background = ctx.ripple(
                ctx.rowShape(index, radios.size, if (checked) palette.alpha15 else palette.cardFill),
                ctx.rowShape(index, radios.size, Color.WHITE))
            radio.setTextColor(if (checked) palette.textAccent else ctx.color(R.color.v3_text_1))
            radio.typeface = ctx.v3Font(if (checked) 600 else 500)
        }
        plazaReportReasons.indexOf(model.reason).takeIf { it >= 0 }?.let { reasons.check(ids[it]) }
        styleReasons()
        add(form, reasons, 14)

        // ── 补充说明 ─────────────────────────────────────────────────
        add(form, ctx.formSection(
            getString(R.string.plaza_report_details_label),
            getString(R.string.plaza_form_optional),
            required = false,
        ), 28)
        val details = AppCompatEditText(ctx).apply {
            id = R.id.plaza_report_details_input
            hint = getString(R.string.plaza_report_details_hint)
            contentDescription = getString(R.string.plaza_report_details_label)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            textSize = 15f
            typeface = ctx.v3Font(400)
            lineHeightRatio(1.7f)
            gravity = Gravity.TOP or Gravity.START
            // 卡片底交给外层容器，输入框自己透明：两层圆角叠在一起会在四角描出一圈深边。
            background = shape(0f, Color.TRANSPARENT)
            setTextColor(ctx.color(R.color.v3_text_1))
            setHintTextColor(ctx.color(R.color.v3_text_2))
            setPadding(0, 0, 0, 0)
            minimumHeight = ctx.dp(168)
            minLines = 5
            filters = arrayOf(InputFilter.LengthFilter(1000))
            isSaveEnabled = false // SavedStateHandle is the only draft source.
            setText(model.details)
        }
        val counter = ctx.label("", 12f, 500, ctx.color(R.color.v3_text_2)).apply {
            gravity = Gravity.END
            fontFeatureSettings = "tnum"
        }
        val detailsCard = LinearLayout(ctx).apply {
            id = R.id.plaza_report_details_card
            orientation = LinearLayout.VERTICAL
            background = ctx.card(22)
            setPadding(ctx.dp(18), ctx.dp(16), ctx.dp(18), ctx.dp(12))
            addView(details, LinearLayout.LayoutParams(-1, -2))
            addView(counter, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
        }
        add(form, detailsCard, 14)

        // ── 照片证据（三个等分方格，空位即添加槽） ─────────────────────
        add(form, ctx.formSection(
            getString(R.string.plaza_report_photos_label),
            getString(R.string.plaza_form_optional),
            required = false,
        ), 28)
        add(form, ctx.label(getString(R.string.plaza_report_photos_hint), 13f, color = ctx.color(R.color.v3_text_2))
            .apply { lineHeightRatio(1.6f) }, 8)
        val photos = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        add(form, photos, 14)

        // ── 提交成功 ─────────────────────────────────────────────────
        val receiptMessage = ctx.label("", 14f, 400, ctx.color(R.color.v3_text_2)).apply {
            id = R.id.plaza_report_receipt
            gravity = Gravity.CENTER
            lineHeightRatio(1.7f)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val receipt = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isVisible = false
            // 徽章转了 -8°，画出来的范围比自己的 layout 框上下各多约 6dp；容器默认
            // clipChildren + clipToPadding 会沿 padding 边把顶上那两个角削平。
            clipChildren = false
            clipToPadding = false
            setPadding(ctx.dp(8), ctx.dp(32), ctx.dp(8), ctx.dp(8))
            addView(PlazaSuccessBadge(ctx))
            addView(
                ctx.label(getString(R.string.plaza_report_received_title), 24f, 700).apply {
                    gravity = Gravity.CENTER
                    lineHeightRatio(1.4f)
                    ViewCompat.setAccessibilityHeading(this, true)
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(28) },
            )
            addView(receiptMessage, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(10) })
        }
        add(column, receipt)

        // ── 贴底主操作 ───────────────────────────────────────────────
        val divider = View(ctx).apply { setBackgroundColor(palette.cardHairline) }
        page.addView(divider, LinearLayout.LayoutParams(-1, ctx.hairlinePx()))
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ctx.color(R.color.v3_bg))
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
        }.apply { id = R.id.plaza_report_submit_button; minHeight = ctx.dp(52); textSize = 15f }
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
        var addSlot: View? = null

        /** 三格永远等宽：已选照片占前几格，紧接着的一格是添加槽，其余留空撑住比例。 */
        fun buildPhotoRow(state: ModerationState) {
            photos.removeAllViews()
            photoProgress.clear()
            addSlot = null
            repeat(3) { index ->
                val slot = SquareFrameLayout(ctx)
                val image = state.images.getOrNull(index)
                when {
                    image != null -> {
                        slot.background = ctx.card(18)
                        slot.clipToOutline = true
                        val thumb = ImageView(ctx).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            contentDescription = getString(R.string.plaza_selected_photo, index + 1)
                        }
                        slot.addView(thumb, FrameLayout.LayoutParams(-1, -1))
                        // 不写死解码尺寸：格子宽度跟着列宽走（手机约 100dp，720dp 宽屏列下约 220dp），
                        // 固定 override 会让审核人员要看的证据在宽窗口上糊掉。交给 Glide 按 View 实测尺寸取。
                        Glide.with(thumb).load(Uri.parse(image.uri)).into(thumb)
                        slot.addView(
                            IconButton(
                                ctx,
                                R.drawable.ic_close_black_24dp,
                                getString(R.string.plaza_remove_photo, index + 1),
                                Color.WHITE,
                            ).apply {
                                tag = "remove"
                                // 视觉是 36dp 的小圆，热区仍是整块 48dp：圆靠 InsetDrawable 往里收，
                                // 不缩 View 本身（缩圆点省地方，缩热区就点不中了）。
                                background = InsetDrawable(
                                    ctx.ripple(
                                        shape(999f, V3Palette.withAlpha(Color.BLACK, .55f)),
                                        shape(999f, Color.WHITE),
                                    ),
                                    ctx.dp(6),
                                )
                                icon.layoutParams = FrameLayout.LayoutParams(ctx.dp(16), ctx.dp(16), Gravity.CENTER)
                                setOnClickListener { model.removeImage(image.uri) }
                            },
                            FrameLayout.LayoutParams(ctx.dp(48), ctx.dp(48), Gravity.TOP or Gravity.END),
                        )
                        val progress = ctx.label("", 12f, 600, palette.onPrimary).apply {
                            gravity = Gravity.CENTER
                            setPadding(0, ctx.dp(5), 0, ctx.dp(5))
                            setBackgroundColor(palette.primary)
                            fontFeatureSettings = "tnum"
                        }
                        photoProgress[image.uri] = progress
                        slot.addView(progress, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                    }
                    index == state.images.size -> {
                        slot.id = R.id.plaza_report_add_photos
                        slot.background = ctx.ripple(ctx.dashedSlot(18), shape(ctx.dpF(18f), Color.WHITE))
                        slot.isClickable = true
                        slot.isFocusable = true
                        slot.contentDescription = getString(R.string.plaza_add_photos, 3)
                        slot.setOnClickListener {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        slot.pressScale()
                        slot.addView(
                            ImageView(ctx).apply {
                                setImageResource(R.drawable.ic_add_black_24dp)
                                imageTintList = ColorStateList.valueOf(palette.textAccent)
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            },
                            FrameLayout.LayoutParams(ctx.dp(26), ctx.dp(26), Gravity.CENTER),
                        )
                        addSlot = slot
                    }
                }
                photos.addView(slot, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index > 0) marginStart = ctx.dp(10)
                })
            }
        }

        fun render(state: ModerationState) {
            back.isEnabled = state.busy
            form.isVisible = !state.done
            title.isVisible = !state.done
            notice.isVisible = !state.done
            receipt.isVisible = state.done
            if (state.done) {
                receiptMessage.text = getString(
                    if (state.receiptStatus == "pending") R.string.plaza_report_success
                    else R.string.plaza_report_reviewed,
                    state.receiptId,
                )
            }
            status.text = state.error?.resolve(ctx).orEmpty()
            status.isVisible = state.error != null
            submit.text = getString(when { state.done -> R.string.plaza_report_finish
                state.busy -> R.string.plaza_report_sending; else -> R.string.plaza_report_submit })
            details.isEnabled = !state.busy
            radios.forEach { it.isEnabled = !state.busy }
            val keys = state.images.map { it.uri }
            if (photoKeys != keys) {
                photoKeys = keys
                buildPhotoRow(state)
            }
            addSlot?.isEnabled = !state.busy
            addSlot?.alpha = if (state.busy) .5f else 1f
            state.images.forEachIndexed { index, image ->
                photos.getChildAt(index)?.findViewWithTag<View>("remove")?.isEnabled = !state.busy
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
