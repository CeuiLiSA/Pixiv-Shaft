package ceui.pixiv.plaza.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.pixiv.witstudio.theme.V3Palette

/** Figma 64dp bar, 40dp circle at x=16, title at x=68. System status bar is native. */
class PlazaHeader @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context,attrs) {
    val title = context.label("",18f,true).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END; typeface = androidx.core.content.res.ResourcesCompat.getFont(context,R.font.plaza_inter_bold) }
    internal val back = context.figmaIcon(R.drawable.ic_plaza_figma_back, "返回", circle = true)
    val action = context.label("",14f).apply { typeface=androidx.core.content.res.ResourcesCompat.getFont(context,R.font.plaza_inter_medium); gravity = Gravity.CENTER; setTextColor(V3Palette.from(context).onPrimary)
        background = V3Palette.from(context).pillPrimary(context.dp(20).toFloat()); setPadding(context.dp(14),0,context.dp(14),0) }
    val trailing = FrameLayout(context)
    init {
        addView(back,LayoutParams(context.dp(48),context.dp(48),Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart=context.dp(12) })
        addView(title,LayoutParams(-1,-2,Gravity.CENTER_VERTICAL).apply { marginStart=context.dp(68); marginEnd=context.dp(90) })
        trailing.addView(action,LayoutParams(-2,context.dp(33),Gravity.CENTER))
        addView(trailing,LayoutParams(-2,context.dp(48),Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd=context.dp(16) })
        val actionHeight=maxOf(context.dp(33),(action.textSize*1.35f).toInt()+context.dp(12))
        action.layoutParams=action.layoutParams.apply {height=actionHeight}
        trailing.layoutParams=trailing.layoutParams.apply {height=maxOf(context.dp(48),actionHeight)}
        trailing.addOnLayoutChangeListener {_,left,_,right,_,_,_,_,_->
            val needed=right-left+context.dp(28)
            val params=title.layoutParams as LayoutParams
            if(params.marginEnd!=needed) {params.marginEnd=needed;title.layoutParams=params}
        }
        trailing.minimumWidth=context.dp(48); trailing.isClickable=true; trailing.isFocusable=true
    }
}
internal fun Fragment.setupPlazaHeader(root: View, title: String, compose: Boolean = false): PlazaHeader {
    val header=root.findViewById<PlazaHeader>(R.id.plaza_header)
    header.title.text=title
    if(compose) header.back.icon.setImageResource(R.drawable.ic_plaza_figma_close)
    header.back.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
    val content=root.findViewById<View>(R.id.plaza_content)
    WindowInsetsControllerCompat(requireActivity().window,root).isAppearanceLightStatusBars = !V3Palette.from(requireContext()).isDark
    ViewCompat.setOnApplyWindowInsetsListener(root) { _,insets ->
        val top=insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
        header.setPadding(0,top,0,0)
        header.layoutParams=header.layoutParams.apply { height=maxOf(requireContext().dp(64),header.trailing.layoutParams.height+requireContext().dp(16))+top }
        val bottom=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()).bottom
        content.setPadding(0,0,0,bottom)
        insets
    }
    ViewCompat.requestApplyInsets(root)
    return header
}
