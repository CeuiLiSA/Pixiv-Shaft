package ceui.pixiv.plaza.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import ceui.lisa.R
import ceui.pixiv.plaza.PlazaPost
import ceui.pixiv.witstudio.theme.V3Palette

/** Figma detail footer: 40dp input, 12dp gaps, two 60dp counters. */
internal class PlazaReplyBar(context: Context, reply: () -> Unit, react: () -> Unit, comments: () -> Unit) : LinearLayout(context) {
    private val reactionCount=context.label("0",15f)
    private val replyCount=context.label("0",15f)
    private val input=context.label("说点什么…",16f).apply {
        maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
        typeface=ResourcesCompat.getFont(context,R.font.plaza_inter_medium)
        setTextColor(ContextCompat.getColor(context,R.color.v3_text_3))
        background=V3Palette.from(context).pillSecondary(context.dp(16).toFloat())
        gravity=Gravity.CENTER_VERTICAL;setPadding(context.dp(12),0,context.dp(12),0)
        isFocusable=true;setOnClickListener {reply()}
    }
    init {
        gravity=Gravity.CENTER_VERTICAL
        setPadding(context.dp(16),context.dp(12),context.dp(16),context.dp(12))
        addView(input,LayoutParams(0,context.dp(40),1f))
        fun counter(icon:Int,label:android.widget.TextView,description:String,action:()->Unit) {
            val row=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;contentDescription=description;isFocusable=true;setOnClickListener {action()}}
            row.addView(ImageView(context).apply {setImageResource(icon);imageTintList=ColorStateList.valueOf(V3Palette.from(context).floatingPillContent)},LayoutParams(context.dp(24),context.dp(24)))
            row.addView(label,LayoutParams(-2,-2).apply {marginStart=context.dp(6)})
            addView(row,LayoutParams(-2,context.dp(40)).apply {marginStart=context.dp(12)})
            row.minimumWidth=context.dp(60)
        }
        counter(R.drawable.ic_plaza_figma_reaction,reactionCount,"回应帖子",react)
        counter(R.drawable.ic_plaza_figma_comment,replyCount,"查看评论",comments)
    }
    fun bind(post:PlazaPost?,busy:Boolean=false) {
        input.isEnabled=post!=null
        reactionCount.text=compact((post?.likeCount?:0)+ (post?.reactions?.sumOf {it.count}?:0))
        replyCount.text=compact(post?.replyCount?:0)
        getChildAt(1).isEnabled=post!=null && !busy
        getChildAt(2).isEnabled=post!=null
        // Preserve readable text when system font scaling exceeds the design's default size.
        val height=maxOf(context.dp(40),(input.textSize*1.5f).toInt())
        for(i in 0 until childCount) getChildAt(i).layoutParams=getChildAt(i).layoutParams.apply {this.height=height}
    }
    private fun compact(count:Int)=if(count<1000) count.toString() else if(count<1000000) "${count/1000}k" else "${count/1000000}m"
}
