package ceui.pixiv.plaza.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.pixiv.chat.base.launchSuspend
import ceui.pixiv.shaftapi.MediaHttpTransport
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import ceui.pixiv.witstudio.theme.*
import ceui.pixiv.witstudio.theme.V3Palette
import com.bumptech.glide.Glide

/**
 * Create a post on the V3 form recipe: labelled fields in a 22dp card, an image card with
 * 12dp tiles, the Pixiv reference as a connected row, field-adjacent errors, and the
 * standard toolbar action for publishing.
 */
class PlazaComposeFragment : Fragment(R.layout.fragment_plaza_shell) {
    private val model: PlazaComposeViewModel by viewModels()
    private val picker =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
            uris.take(9).forEach { uri ->
                runCatching {
                    requireContext()
                        .contentResolver
                        .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            model.attach(uris)
        }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val ctx = requireContext()
            if (model.state.value.sending)
                WitDialog.MessageDialogBuilder(ctx)
                    .setMessage(ctx.getString(R.string.plaza_publishing_wait))
                    .addAction(ctx.getString(R.string.plaza_understood)) { d, _ ->
                        d.dismiss()
                    }
                    .show()
            else if (model.shouldInterceptBack())
                WitDialog.MessageDialogBuilder(ctx)
                    .setMessage(ctx.getString(R.string.plaza_discard_confirm))
                    .addAction(ctx.getString(R.string.plaza_keep_editing)) { d, _ ->
                        d.dismiss()
                    }
                    .addAction(0, R.string.plaza_discard, WitDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                        d.dismiss()
                        requireActivity().finish()
                    }
                    .show()
            else {
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val palette = V3Palette.from(ctx)
        model.avatarUrl = ceui.pixiv.session.SessionManager.loggedInUser?.profile_image_urls?.medium
        if (savedInstanceState == null) {
            model.replyTo = arguments?.getLong(ARG_REPLY_TO)?.takeIf { it > 0 }
            val id = arguments?.getLong(ARG_PREFILL_ILLUST_ID) ?: 0L
            if (id > 0 && model.state.value.objectId == null)
                model.reference(
                    id,
                    arguments?.getString(ARG_OBJECT_TYPE)?.takeIf {
                        it in listOf("illust", "manga", "novel", "user")
                    } ?: "illust",
                )
        }
        backCallback.isEnabled = model.shouldInterceptBack()
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        val toolbar =
            setupPlazaToolbar(
                view,
                if (model.replyTo != null) ctx.getString(R.string.plaza_reply)
                else ctx.getString(R.string.plaza_compose_title),
            )
        val sendAction = toolbar.menu.add(R.string.plaza_send_post).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        val frame = view.findViewById<FrameLayout>(R.id.plaza_content)
        val scroll =
            NestedScrollView(ctx).apply {
                isFillViewport = true
                clipToPadding = false
            }
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        frame.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val width = minOf(r - l, ctx.dp(720))
            if (scroll.layoutParams.width != width)
                scroll.layoutParams = FrameLayout.LayoutParams(width, -1, Gravity.CENTER_HORIZONTAL)
        }
        val column =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(24))
            }
        scroll.addView(column)

        fun card(): LinearLayout =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = ctx.card(22)
                setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
            }
        fun field(hintRes: Int): EditText =
            EditText(ctx).apply {
                hint = ctx.getString(hintRes)
                background = null
                setPadding(0, ctx.dp(6), 0, ctx.dp(6))
                includeFontPadding = false
                setTextColor(ctx.color(R.color.v3_text_1))
                setHintTextColor(ctx.color(R.color.v3_text_3))
                isSaveEnabled = false
            }

        // ── Text card: persistent labels, no floating hints, counter on the body ──
        val textCard = card()
        textCard.addView(ctx.sectionLabel(ctx.getString(R.string.plaza_title_label)))
        val title =
            field(R.string.plaza_title_hint).apply {
                textSize = 20f
                typeface = ctx.v3Font(600)
                isSingleLine = true
                minHeight = ctx.dp(44)
                filters = arrayOf(InputFilter.LengthFilter(240))
                setText(model.title)
                id = R.id.plaza_draft_title
            }
        textCard.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(2) })
        textCard.addView(
            View(ctx).apply { setBackgroundColor(palette.cardHairline) },
            LinearLayout.LayoutParams(-1, ctx.hairlinePx()).apply {
                topMargin = ctx.dp(8)
                bottomMargin = ctx.dp(14)
            },
        )
        textCard.addView(ctx.sectionLabel(ctx.getString(R.string.plaza_body_label)))
        val input =
            field(R.string.plaza_body_hint).apply {
                textSize = 15f
                gravity = Gravity.TOP
                minHeight = ctx.dp(200)
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                // Setting inputType resets the typeface; apply the V3 face afterwards.
                typeface = ctx.v3Font(400)
                lineHeightRatio(1.7f)
                setText(model.text)
                id = R.id.plaza_draft_text
                filters = arrayOf(InputFilter.LengthFilter(8000))
            }
        textCard.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(2) })
        val counter =
            ctx.label("", 12f, 500, ctx.color(R.color.v3_text_3)).apply {
                fontFeatureSettings = "tnum"
                gravity = Gravity.END
            }
        textCard.addView(counter, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
        column.addView(textCard, LinearLayout.LayoutParams(-1, -2))

        // ── Image card: 80dp tiles, 12dp radius, dashed add tile, 40dp remove targets ──
        val photoCard = card()
        val photosLabel = ctx.sectionLabel("")
        photoCard.addView(photosLabel)
        val horizontal =
            HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                clipChildren = false
            }
        val previews = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        horizontal.addView(previews)
        photoCard.addView(
            horizontal,
            LinearLayout.LayoutParams(-1, ctx.dp(80)).apply { topMargin = ctx.dp(12) },
        )
        column.addView(photoCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(12) })

        // ── Reference: one connected row (20dp corners) with a chevron, chip when set ──
        val referenceRow =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ctx.rowSurface(0, 1)
                minimumHeight = ctx.dp(64)
                setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(12), ctx.dp(14))
                isClickable = true
                isFocusable = true
                contentDescription = ctx.getString(R.string.plaza_add_reference)
            }
        val referenceCopy = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        referenceCopy.addView(ctx.label(ctx.getString(R.string.plaza_reference_section), 15f, 500))
        val referenceHint =
            ctx.label(ctx.getString(R.string.plaza_reference_hint), 12f, 400, ctx.color(R.color.v3_text_3))
        referenceCopy.addView(
            referenceHint,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4) },
        )
        val reference =
            ctx.label("", 13f, 600, palette.textAccent).apply {
                background =
                    ctx.ripple(
                        shape(ctx.dpF(12f), palette.alpha08, palette.alpha15, ctx.hairlinePx()),
                        shape(ctx.dpF(12f), Color.WHITE),
                    )
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
                minHeight = ctx.dp(40)
                isClickable = true
                isFocusable = true
                setOnClickListener { model.reference(null, null) }
                pressScale()
            }
        referenceCopy.addView(
            reference,
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(8) },
        )
        referenceRow.addView(referenceCopy, LinearLayout.LayoutParams(0, -2, 1f))
        val chevron =
            ImageView(ctx).apply {
                setImageResource(R.drawable.ic_v3_chevron_24)
                imageTintList = ColorStateList.valueOf(ctx.color(R.color.v3_text_3))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        referenceRow.addView(
            chevron,
            LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)).apply { marginStart = ctx.dp(12) },
        )
        referenceRow.setOnClickListener { chooseReference() }
        column.addView(referenceRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(12) })

        // ── Field-adjacent error, announced politely ──
        val error =
            ctx.label("", 13f, 500, ctx.color(R.color.v3_danger)).apply {
                setPadding(ctx.dp(4), ctx.dp(12), ctx.dp(4), 0)
                lineHeightRatio(1.5f)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        column.addView(error)
        toolbar.setOnMenuItemClickListener {
            ctx.withPlazaPolicy { model.send(ctx.applicationContext.contentResolver) }
            true
        }
        fun updateSend() {
            sendAction.isEnabled = model.canSend()
            backCallback.isEnabled = model.shouldInterceptBack()
            photosLabel.text =
                ctx.getString(R.string.plaza_photos_count, model.state.value.images.size, 9)
            counter.text =
                ctx.getString(
                    R.string.plaza_char_count,
                    model.text.codePointCount(0, model.text.length),
                    2000,
                )
            counter.setTextColor(
                if (model.text.codePointCount(0, model.text.length) > 2000) ctx.color(R.color.v3_danger)
                else ctx.color(R.color.v3_text_3)
            )
            error.text =
                when {
                    model.text.codePointCount(0, model.text.length) > 2000 ->
                        ctx.getString(R.string.plaza_body_limit, 2000)
                    model.title.codePointCount(0, model.title.length) > 120 ->
                        ctx.getString(R.string.plaza_title_limit, 120)
                    else -> model.state.value.error?.resolve(ctx).orEmpty()
                }
            error.isVisible = error.text.isNotEmpty()
        }
        title.doAfterTextChanged {
            model.title = it?.toString().orEmpty()
            updateSend()
        }
        input.doAfterTextChanged {
            model.text = it?.toString().orEmpty()
            updateSend()
        }
        var rendered: List<String>? = null
        val progressLabels = mutableMapOf<String, TextView>()
        launchSuspend {
            model.state.collect { state ->
                if (state.sentId != null) {
                    requireActivity().finish()
                    return@collect
                }
                model.takeErrorForAlert()?.let(ctx::showPlazaError)
                updateSend()
                input.isEnabled = !state.sending
                title.isEnabled = !state.sending
                referenceRow.isEnabled = !state.sending
                reference.isEnabled = !state.sending
                reference.isVisible = state.objectId != null
                referenceHint.isVisible = state.objectId == null
                reference.text =
                    state.objectId
                        ?.let {
                            ctx.getString(
                                R.string.plaza_reference_removable,
                                ctx.objectLabel(state.objectType),
                                it,
                            )
                        }
                        .orEmpty()
                reference.contentDescription =
                    state.objectId?.let {
                        ctx.getString(
                            R.string.plaza_remove_reference,
                            ctx.objectLabel(state.objectType),
                            it,
                        )
                    }
                sendAction.title =
                    if (state.sending) ctx.getString(R.string.plaza_publishing)
                    else ctx.getString(R.string.plaza_send_post)
                val keys = state.images.map { it.uri }
                if (keys != rendered) {
                    previews.removeAllViews()
                    progressLabels.clear()
                    rendered = keys
                    val add =
                        FrameLayout(ctx).apply {
                            contentDescription = ctx.getString(R.string.plaza_add_photos, 9)
                            tag = "add"
                            isClickable = true
                            isFocusable = true
                            background =
                                ctx.ripple(
                                    GradientDrawable().apply {
                                        cornerRadius = ctx.dpF(12f)
                                        setColor(palette.alpha08)
                                        setStroke(
                                            ctx.hairlinePx(),
                                            palette.alpha30,
                                            ctx.dpF(6f),
                                            ctx.dpF(4f),
                                        )
                                    },
                                    shape(ctx.dpF(12f), Color.WHITE),
                                )
                            addView(
                                ImageView(ctx).apply {
                                    setImageResource(R.drawable.ic_add_black_24dp)
                                    imageTintList = ColorStateList.valueOf(palette.textAccent)
                                },
                                FrameLayout.LayoutParams(ctx.dp(24), ctx.dp(24), Gravity.CENTER),
                            )
                            pressScale()
                            setOnClickListener {
                                MediaHttpTransport.prewarm()
                                picker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                        }
                    if (keys.size < 9)
                        previews.addView(
                            add,
                            LinearLayout.LayoutParams(ctx.dp(80), ctx.dp(80)).apply {
                                marginEnd = ctx.dp(8)
                            },
                        )
                    state.images.forEachIndexed { index, image ->
                        val tile =
                            FrameLayout(ctx).apply {
                                background = shape(ctx.dpF(12f), palette.alpha08)
                                clipToOutline = true
                            }
                        val thumb =
                            ImageView(ctx).apply {
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                contentDescription =
                                    ctx.getString(R.string.plaza_selected_photo, index + 1)
                            }
                        tile.addView(thumb, FrameLayout.LayoutParams(-1, -1))
                        Glide.with(thumb)
                            .load(Uri.parse(image.uri))
                            .override(ctx.dp(80), ctx.dp(80))
                            .into(thumb)
                        // 22dp close disc on the theme-tinted floating pill, 40dp touch target.
                        val remove =
                            FrameLayout(ctx).apply {
                                tag = "remove"
                                isClickable = true
                                isFocusable = true
                                contentDescription = ctx.getString(R.string.plaza_remove_photo, index + 1)
                                val disc =
                                    FrameLayout(ctx).apply {
                                        background = palette.floatingPillBg(999f, 0.92f)
                                        addView(
                                            ImageView(ctx).apply {
                                                setImageResource(R.drawable.ic_close_black_24dp)
                                                imageTintList =
                                                    ColorStateList.valueOf(palette.floatingPillContent)
                                            },
                                            FrameLayout.LayoutParams(ctx.dp(14), ctx.dp(14), Gravity.CENTER),
                                        )
                                    }
                                addView(
                                    disc,
                                    FrameLayout.LayoutParams(ctx.dp(22), ctx.dp(22), Gravity.TOP or Gravity.END)
                                        .apply {
                                            topMargin = ctx.dp(4)
                                            marginEnd = ctx.dp(4)
                                        },
                                )
                                setOnClickListener { model.remove(image.uri) }
                            }
                        tile.addView(
                            remove,
                            FrameLayout.LayoutParams(ctx.dp(40), ctx.dp(40), Gravity.TOP or Gravity.END),
                        )
                        val progress =
                            ctx.label("", 11f, 600, palette.onPrimary).apply {
                                gravity = Gravity.CENTER
                                setBackgroundColor(palette.primary)
                            }
                        progressLabels[image.uri] = progress
                        tile.addView(
                            progress,
                            FrameLayout.LayoutParams(-1, ctx.dp(20), Gravity.BOTTOM),
                        )
                        previews.addView(
                            tile,
                            LinearLayout.LayoutParams(ctx.dp(80), ctx.dp(80)).apply {
                                marginEnd = ctx.dp(8)
                            },
                        )
                    }
                }
                state.images.forEach { image ->
                    progressLabels[image.uri]?.apply {
                        isVisible = state.sending || image.mediaId != null
                        text =
                            if (image.mediaId != null) ctx.getString(R.string.plaza_uploaded)
                            else ctx.getString(R.string.plaza_upload_progress, image.progress)
                    }
                }
                previews.findViewWithTag<View>("add")?.isEnabled = !state.sending
                for (i in 0 until previews.childCount) previews
                    .getChildAt(i)
                    .findViewWithTag<View>("remove")
                    ?.isEnabled = !state.sending
            }
        }
        MediaHttpTransport.prewarm()
    }

    private fun chooseReference() {
        // Selection and ID stay explicit; a manga reference is not silently rewritten as illust.
        WitDialog.MenuDialogBuilder(requireContext())
            .addItems(
                arrayOf(
                    getString(R.string.plaza_object_illust),
                    getString(R.string.plaza_object_manga),
                    getString(R.string.plaza_object_novel),
                    getString(R.string.plaza_object_user),
                )
            ) { dialog, which ->
                dialog.dismiss()
                inputReference(listOf("illust", "manga", "novel", "user")[which])
            }
            .show()
    }

    private fun inputReference(type: String) {
        val builder = WitDialog.EditTextDialogBuilder(requireContext())
        builder
            .setTitle(getString(R.string.plaza_reference_type, requireContext().objectLabel(type)))
            .setPlaceholder(getString(R.string.plaza_id_hint))
            .setInputType(InputType.TYPE_CLASS_NUMBER)
            .addAction(getString(R.string.cancel)) { d, _ -> d.dismiss() }
            .addAction(getString(R.string.add)) { d, _ ->
                val id = builder.editText.text.toString().trim().toLongOrNull()
                if (id != null && id in 1..Int.MAX_VALUE.toLong()) {
                    model.reference(id, type)
                    d.dismiss()
                } else builder.editText.error = getString(R.string.plaza_id_invalid)
            }
            .show()
    }

    companion object {
        const val ARG_PREFILL_ILLUST_ID = "plaza_compose_prefill_illust_id"
        const val ARG_OBJECT_TYPE = "plaza_object_type"
        const val ARG_REPLY_TO = "plaza_reply_to"
    }
}
