package ceui.pixiv.plaza.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.lisa.fragments.BaseFragment
import ceui.pixiv.chat.base.launchSuspend
import ceui.pixiv.shaftapi.MediaHttpTransport
import ceui.pixiv.witstudio.dialog.WitDialog
import com.bumptech.glide.Glide

class PlazaComposeFragment : Fragment(R.layout.fragment_plaza_shell) {
    private val model: PlazaComposeViewModel by viewModels()
    private val picker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        uris.take(9).forEach { uri -> runCatching { requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        model.attach(uris)
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        model.avatarUrl=ceui.pixiv.session.SessionManager.loggedInUser?.profile_image_urls?.medium
        if (savedInstanceState == null) {
            model.replyTo = arguments?.getLong(ARG_REPLY_TO)?.takeIf { it > 0 }
            val id = arguments?.getLong(ARG_PREFILL_ILLUST_ID) ?: 0L
            if (id > 0 && model.state.value.objectId == null) model.reference(id, arguments?.getString(ARG_OBJECT_TYPE)?.takeIf { it in listOf("illust", "manga", "novel", "user") } ?: "illust")
        }
        val header=setupPlazaHeader(view,if(model.replyTo!=null) "回复" else "新帖子",true)
        header.action.text="发布"
        val frame=view.findViewById<FrameLayout>(R.id.plaza_content)
        val scroll=androidx.core.widget.NestedScrollView(ctx).apply {isFillViewport=true;clipToPadding=false}
        frame.addView(scroll,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER_HORIZONTAL))
        frame.addOnLayoutChangeListener { _,l,_,r,_,_,_,_,_ ->
            val width=minOf(r-l,ctx.dp(720))
            if(scroll.layoutParams.width!=width) scroll.layoutParams=FrameLayout.LayoutParams(width,-1,Gravity.CENTER_HORIZONTAL)
        }
        val column=LinearLayout(ctx).apply {orientation=LinearLayout.VERTICAL;setPadding(ctx.dp(16),ctx.dp(16),ctx.dp(16),ctx.dp(24))}
        scroll.addView(column)
        fun divider() { column.addView(View(ctx).apply {setBackgroundColor(ceui.pixiv.witstudio.theme.V3Palette.from(ctx).cardHairline)},
            LinearLayout.LayoutParams(-1,ctx.dp(1).coerceAtLeast(1)).apply {topMargin=ctx.dp(16);bottomMargin=ctx.dp(16)}) }
        val title=EditText(ctx).apply {
            hint="标题（选填）";textSize=24f;typeface=androidx.core.content.res.ResourcesCompat.getFont(ctx,R.font.plaza_inter_bold)
            background=null;setPadding(0,0,0,0);isSingleLine=true;includeFontPadding=false;minHeight=ctx.dp(36);setText(model.title)
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx,R.color.v3_text_1))
            setHintTextColor(androidx.core.content.ContextCompat.getColor(ctx,R.color.v3_text_3));isSaveEnabled=false
            filters=arrayOf(android.text.InputFilter.LengthFilter(240));id=R.id.plaza_draft_title
        }
        column.addView(title,LinearLayout.LayoutParams(-1,-2));divider()
        val input=EditText(ctx).apply {
            hint="分享你的想法…";textSize=15f;typeface=androidx.core.content.res.ResourcesCompat.getFont(ctx,R.font.plaza_inter_medium)
            background=null;setPadding(0,0,0,0);gravity=Gravity.TOP;includeFontPadding=false;figmaLineHeight(1.5f);minHeight=ctx.dp(260)
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            typeface=androidx.core.content.res.ResourcesCompat.getFont(ctx,R.font.plaza_inter_medium);figmaLineHeight(1.5f)
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx,R.color.v3_text_2))
            setHintTextColor(androidx.core.content.ContextCompat.getColor(ctx,R.color.v3_text_3))
            setText(model.text);id=R.id.plaza_draft_text;isSaveEnabled=false;filters=arrayOf(android.text.InputFilter.LengthFilter(8000))
        }
        column.addView(input,LinearLayout.LayoutParams(-1,-2))
        val photosLabel=ctx.label("",13f).apply {setTextColor(androidx.core.content.ContextCompat.getColor(ctx,R.color.v3_text_2));letterSpacing=.035f}
        column.addView(photosLabel,LinearLayout.LayoutParams(-1,-2).apply {topMargin=ctx.dp(12);bottomMargin=ctx.dp(8)})
        val horizontal=HorizontalScrollView(ctx).apply {isHorizontalScrollBarEnabled=false;clipToPadding=false}
        val previews=LinearLayout(ctx).apply {orientation=LinearLayout.HORIZONTAL}
        horizontal.addView(previews);column.addView(horizontal,LinearLayout.LayoutParams(-1,ctx.dp(80)))
        divider()
        val referenceHeader=LinearLayout(ctx).apply {gravity=Gravity.CENTER_VERTICAL}
        referenceHeader.addView(ctx.label("引用作品 / 用户",13f),LinearLayout.LayoutParams(0,-2,1f))
        val ref=ImageView(ctx).apply {setImageResource(R.drawable.ic_plaza_figma_chevron)
            imageTintList=android.content.res.ColorStateList.valueOf(ceui.pixiv.witstudio.theme.V3Palette.from(ctx).floatingPillContent)
            contentDescription="添加引用";isClickable=true;isFocusable=true}
        referenceHeader.addView(ref,LinearLayout.LayoutParams(ctx.dp(20),ctx.dp(20)))
        column.addView(referenceHeader);referenceHeader.setOnClickListener {chooseReference()};ref.setOnClickListener {chooseReference()}
        val reference=ctx.label("",14f).apply {
            setTextColor(ceui.pixiv.witstudio.theme.V3Palette.from(ctx).textAccent)
            background=ceui.pixiv.witstudio.theme.V3Palette.from(ctx).pillSecondary(ctx.dp(8).toFloat())
            setPadding(ctx.dp(8),ctx.dp(4),ctx.dp(8),ctx.dp(4));minHeight=ctx.dp(28)
            setOnClickListener {model.reference(null,null)}
        }
        column.addView(reference,LinearLayout.LayoutParams(-2,-2).apply {topMargin=ctx.dp(8)})
        divider()
        val error=ctx.label("").apply {setPadding(0,ctx.dp(12),0,ctx.dp(12));accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
        column.addView(error)
        header.trailing.setOnClickListener {model.send(ctx.applicationContext.contentResolver)}
        fun updateSend() {
            header.trailing.isEnabled=model.canSend();header.action.alpha=if(header.trailing.isEnabled) 1f else .4f
            photosLabel.text="图片 (${model.state.value.images.size}/9)"
            error.text=when {
                model.text.codePointCount(0,model.text.length)>2000 -> "正文最多 2000 字"
                model.title.codePointCount(0,model.title.length)>120 -> "标题最多 120 字"
                else -> model.state.value.error.orEmpty()
            }
            error.isVisible=error.text.isNotEmpty()
        }
        title.doAfterTextChanged {model.title=it?.toString().orEmpty();updateSend()}
        input.doAfterTextChanged {model.text=it?.toString().orEmpty();updateSend()}
        var rendered:List<String>?=null
        val progressLabels=mutableMapOf<String,TextView>()
        launchSuspend {
            model.state.collect {state ->
                if(state.sentId!=null) {requireActivity().finish();return@collect}
                updateSend();input.isEnabled=!state.sending;title.isEnabled=!state.sending
                ref.isEnabled=!state.sending;referenceHeader.isEnabled=!state.sending;reference.isEnabled=!state.sending
                reference.isVisible=state.objectId!=null;reference.text="${objectLabel(state.objectType)} #${state.objectId}   ×"
                reference.contentDescription="移除 ${objectLabel(state.objectType)} ${state.objectId} 引用"
                header.action.text=if(state.sending) "发布中" else "发布"
                val keys=state.images.map {it.uri}
                if(keys!=rendered) {
                    previews.removeAllViews();progressLabels.clear();rendered=keys
                    val add=FrameLayout(ctx).apply {
                        contentDescription="添加图片，最多 9 张";tag="add";isClickable=true;isFocusable=true
                        background=android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius=ctx.dp(6).toFloat();setColor(ceui.pixiv.witstudio.theme.V3Palette.from(ctx).cardFill)
                            setStroke(ctx.dp(2),ceui.pixiv.witstudio.theme.V3Palette.from(ctx).cardHairline,ctx.dp(4).toFloat(),ctx.dp(3).toFloat()) }
                        addView(ImageView(ctx).apply {setImageResource(R.drawable.ic_plaza_figma_add_photo)
                            imageTintList=android.content.res.ColorStateList.valueOf(ceui.pixiv.witstudio.theme.V3Palette.from(ctx).floatingPillContent)},FrameLayout.LayoutParams(ctx.dp(20),ctx.dp(20),Gravity.CENTER))
                        setOnClickListener {MediaHttpTransport.prewarm();picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}
                    }
                    if(keys.size<9) previews.addView(add,LinearLayout.LayoutParams(ctx.dp(80),ctx.dp(80)).apply {marginEnd=ctx.dp(5)})
                    state.images.forEachIndexed {index,image ->
                        val tile=FrameLayout(ctx).apply {background=ceui.pixiv.witstudio.theme.V3Palette.from(ctx).pillSecondary(ctx.dp(4).toFloat());clipToOutline=true}
                        val thumb=ImageView(ctx).apply {scaleType=ImageView.ScaleType.CENTER_CROP;contentDescription="已选图片 ${index+1}"}
                        tile.addView(thumb,FrameLayout.LayoutParams(-1,-1))
                        Glide.with(thumb).load(Uri.parse(image.uri)).override(ctx.dp(80),ctx.dp(80)).into(thumb)
                        val remove=ctx.figmaIcon(R.drawable.ic_plaza_figma_close,"移除图片 ${index+1}",true).apply {tag="remove";setOnClickListener {model.remove(image.uri)}}
                        // Exported 20dp close circle, with a 40dp touch target inside the thumbnail.
                        val surface=remove.getChildAt(0);surface.layoutParams=(surface.layoutParams as FrameLayout.LayoutParams).apply {width=ctx.dp(20);height=ctx.dp(20);gravity=Gravity.TOP or Gravity.END;topMargin=ctx.dp(3);marginEnd=ctx.dp(3)}
                        remove.icon.layoutParams=(remove.icon.layoutParams as FrameLayout.LayoutParams).apply {width=ctx.dp(15);height=ctx.dp(15)}
                        tile.addView(remove,FrameLayout.LayoutParams(ctx.dp(40),ctx.dp(40),Gravity.TOP or Gravity.END))
                        val progress=ctx.label("",12f).apply {gravity=Gravity.CENTER;setTextColor(ceui.pixiv.witstudio.theme.V3Palette.from(ctx).onPrimary)
                            setBackgroundColor(ceui.pixiv.witstudio.theme.V3Palette.from(ctx).primary)}
                        progressLabels[image.uri]=progress;tile.addView(progress,FrameLayout.LayoutParams(-1,ctx.dp(20),Gravity.BOTTOM))
                        previews.addView(tile,LinearLayout.LayoutParams(ctx.dp(80),ctx.dp(80)).apply {marginEnd=ctx.dp(5)})
                    }
                }
                state.images.forEach {image ->progressLabels[image.uri]?.apply {isVisible=state.sending||image.mediaId!=null;text=if(image.mediaId!=null) "已上传" else "${image.progress}%"}}
                previews.findViewWithTag<View>("add")?.isEnabled=!state.sending
                for(i in 0 until previews.childCount) previews.getChildAt(i).findViewWithTag<View>("remove")?.isEnabled=!state.sending
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,object:OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if(model.state.value.sending) WitDialog.MessageDialogBuilder(ctx).setMessage("正在发布，请等待结果。").addAction("知道了") {d,_->d.dismiss()}.show()
                else if(model.title.isNotBlank()||model.text.isNotBlank()||model.state.value.images.isNotEmpty()||model.state.value.objectId!=null)
                    WitDialog.MessageDialogBuilder(ctx).setMessage("放弃这条草稿？").addAction("继续编辑") {d,_->d.dismiss()}
                        .addAction("放弃") {d,_->d.dismiss();requireActivity().finish()}.show()
                else requireActivity().finish()
            }
        })
        MediaHttpTransport.prewarm()
    }
    private fun chooseReference() {
        // Selection and ID stay explicit; a manga reference is not silently rewritten as illust.
        WitDialog.MenuDialogBuilder(requireContext()).addItems(arrayOf("插画", "漫画", "小说", "用户")) { dialog, which ->
            dialog.dismiss(); inputReference(listOf("illust", "manga", "novel", "user")[which])
        }.show()
    }
    private fun inputReference(type: String) {
        val builder = WitDialog.EditTextDialogBuilder(requireContext())
        builder.setTitle("引用${objectLabel(type)}").setPlaceholder("输入 Pixiv ID")
            .setInputType(InputType.TYPE_CLASS_NUMBER)
            .addAction("取消") { d,_ -> d.dismiss() }
            .addAction("添加") { d,_ ->
                val id = builder.editText.text.toString().trim().toLongOrNull()
                if (id != null && id in 1..Int.MAX_VALUE.toLong()) { model.reference(id,type); d.dismiss() }
                else builder.editText.error = "请输入有效的 Pixiv ID"
            }.show()
    }
    companion object {
        const val ARG_PREFILL_ILLUST_ID = "plaza_compose_prefill_illust_id"
        const val ARG_OBJECT_TYPE = "plaza_object_type"
        const val ARG_REPLY_TO = "plaza_reply_to"
    }
}
