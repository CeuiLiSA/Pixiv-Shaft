package ceui.pixiv.widgets

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.databinding.BindingAdapter
import androidx.databinding.InverseBindingAdapter
import androidx.databinding.InverseBindingListener
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.databinding.SmallTagCellBinding
import ceui.lisa.databinding.TagCellBinding
import ceui.lisa.models.TagsBean
import ceui.loxia.ObjectType
import ceui.loxia.Tag
import ceui.loxia.findActionReceiverOrNull
import ceui.loxia.findFragmentOrNull
import ceui.loxia.hideKeyboard
import ceui.pixiv.utils.ColorRandom
import ceui.pixiv.utils.ShapedDrawables
import ceui.pixiv.utils.getIntColor
import com.google.android.flexbox.FlexboxLayout

class TagsFlowView(context: Context, attrs: AttributeSet?, defStyle: Int)
    : FlexboxLayout(context, attrs, defStyle)  {


    object Style {
        val NORMAL = 0
        val SMALL = 1
    }


    var style: Int
    private var _cellClickable: Boolean
    private var tagsMaxLines: Int

    fun setTagsMaxLine(line: Int) {
        tagsMaxLines = line
    }
    init {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.TagsFlowView)
        style = ta.getInt(R.styleable.TagsFlowView_tfv_style, Style.NORMAL)
        _cellClickable = ta.getBoolean(R.styleable.TagsFlowView_tfv_cell_clickable, true)
        tagsMaxLines = ta.getInt(R.styleable.TagsFlowView_tfv_max_lines, 0)
        ta.recycle()
    }

    constructor(context: Context) : this(context, null, 0)

    constructor(context: Context, attrs: AttributeSet?): this(context, attrs, 0)

    private val tagList = mutableListOf<Tag>()

    private var onInverseBindingListener: InverseBindingListener? = null

    private var onTagsChangedListener: (()->Unit)? = null

    fun setOnTagsChangedListener(listener: ()->Unit) {
        onTagsChangedListener = listener
    }

    fun notifyChanged() {
        onInverseBindingListener?.onChange()
        onTagsChangedListener?.invoke()
    }

    fun setOnTagsChangedInverseBindingListener(listener: InverseBindingListener) {
        onInverseBindingListener = listener
    }

    private var onCellClickListener: ((tag: Tag, index: Int)->Unit)? = null
    fun setOnCellClickListener(listener: (tag: Tag, index: Int)->Unit) {
        onCellClickListener = listener
    }

    fun updateCellClickable(clickable: Boolean) {
        _cellClickable = clickable
    }


    fun getTags(): List<Tag> {
        return tagList.toList()
    }

    private var nonCellCount = -1

    fun setJavaTags(tags: List<TagsBean>?) {
        setTags(tags?.sortedBy { (it.translated_name ?: it.name).length }?.map { Tag(name = it.name, translated_name = it.translated_name) })
    }

    fun setTags(tags: List<Tag>?) {
        if (nonCellCount == -1) {
            nonCellCount = childCount
        }

        val newtagList = tags ?: listOf()
        if (tagList == newtagList) {
            return
        }

        tagList.clear()
        tagList.addAll(newtagList)

        if (childCount - nonCellCount < tagList.size) {
            for (i in 0 until tagList.size - (childCount - nonCellCount)) {
                addChild()
            }
        }


        for (i in 0 until childCount - nonCellCount) {
            val child = getChildAt(i)

            if (i < tagList.size) {
                setupChild(child)
            } else {
                child.visibility = View.GONE
            }
        }
    }

    private fun addChild(): View {
        val cell = when (style) {
            Style.NORMAL -> {
                TagCellBinding.inflate(LayoutInflater.from(context), this, false)
            }
            else -> {
                SmallTagCellBinding.inflate(LayoutInflater.from(context), this, false)
            }
        }

        addView(cell.root, childCount - nonCellCount)
        return cell.root
    }

    private fun setupChild(child: View) {
        val index = indexOfChild(child)
        val tag = tagList[index]

        val height = child.layoutParams.height.toFloat()

        val borderColor = getIntColor(ColorRandom.randomColorFromTag(tag))
        val backgroundColorString = ColorRandom.randomColorFromTag(tag)
        val backgroundWithAlpha = backgroundColorString.replace("#", "#33")
        val backgroundColor = getIntColor(backgroundWithAlpha)

        val normal = ShapedDrawables.getRoundedRect(height / 2, context.resources.getDimension(R.dimen.tag_border_width), borderColor, backgroundColor)
        val selected = ShapedDrawables.getRoundedRect(height / 2, 0F, Color.TRANSPARENT, borderColor)


        val selector = StateListDrawable()
        selector.addState(intArrayOf(android.R.attr.state_selected), selected)
        selector.addState(intArrayOf(android.R.attr.state_enabled), normal)

        child.background = selector
        val textView = child.findViewById<TextView>(R.id.hashtag_name)
        textView.text = tag.translated_name?.takeIf { it.isNotEmpty() } ?: tag.name

        val normalTextColor = getIntColor(ColorRandom.randomColorFromTag(tag))
        val selectedTextColor = Color.WHITE

        val colorSelector = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf(android.R.attr.state_enabled)),
            intArrayOf(selectedTextColor, normalTextColor)
        )

        textView.setTextColor(colorSelector)


        val poundView = child.findViewById<ImageView>(R.id.pound_image)
        poundView.imageTintList = colorSelector

        child.visibility = View.VISIBLE

        child.setOnClickListener {
            if (onCellClickListener != null) {
                onCellClickListener!!.invoke(tag, indexOfChild(child))
            } else {
                child.findActionReceiverOrNull<TagsActionReceiver>()?.onClickTag(tag, ObjectType.ILLUST)
            }
        }
    }

    override fun onViewAdded(child: View?) {
        super.onViewAdded(child)

        val tagEditorView = findViewById<ViewGroup>(R.id.tag_edit_view) ?: return
        if (tagEditorView == child) {
            val tagEditor = tagEditorView.findViewById<EditText>(R.id.tag_editer)
            tagEditor.setOnEditorActionListener { tv, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    val tagName = tv.text.toString().trim()
                    if (tagName.isEmpty()) {
                        return@setOnEditorActionListener false
                    }

                    appendTag(tagName)

                    tagEditor.text = null
                    notifyChanged()
                    true
                } else {
                    false
                }
            }

            tagEditor.setOnFocusChangeListener { v, hasFocus ->
                if (!hasFocus) {
                    val fragment = findFragmentOrNull<Fragment>() ?: return@setOnFocusChangeListener
                    fragment.hideKeyboard()
                }
            }
        }
    }

    private fun appendTag(tagName: String) {
        val matched = tagList.indexOfFirst { tagName.equals(it.tagName, true) }
        if (matched >= 0) {
            tagList.removeAt(matched)
            removeViewAt(matched)
        }

        tagList.add(Tag(tagName))

        val addedChild = addChild()
        setupChild(addedChild)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        return !_cellClickable
    }

    fun commit(tagName: String) {
        if (tagName.isEmpty()) return
        appendTag(tagName)
        notifyChanged()
    }

    fun commitEditingText() {
        val tagEditor = findViewById<EditText>(R.id.tag_editer)
        if (!tagEditor.text.isNullOrEmpty()) {
            tagEditor.onEditorAction(EditorInfo.IME_ACTION_DONE)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        if (childCount > 0 && tagsMaxLines > 0) {
            val child = getChildAt(0)
            val childHeight = child.layoutParams.height
            val spacing = dividerDrawableVertical?.intrinsicHeight ?: 0

            val maxHeight = spacing * (tagsMaxLines - 1) + childHeight * tagsMaxLines

            if (measuredHeight > maxHeight)
                setMeasuredDimension(measuredWidth, maxHeight)
        }
    }
}

interface TagsActionReceiver {
    fun onClickTag(tag: Tag, objectType: String)
}



@BindingAdapter("tags")
fun TagsFlowView.binding_setTags(tags: List<Tag>?) {
    setTags(tags)
}

@InverseBindingAdapter(attribute = "tags", event = "onTagsChanged")
fun TagsFlowView.binding_getTags() : List<Tag> {
    return getTags()
}

@BindingAdapter("onTagsChanged")
fun TagsFlowView.binding_setOnTagsChangedListener(listener: InverseBindingListener) {
    setOnTagsChangedInverseBindingListener(listener)
}
