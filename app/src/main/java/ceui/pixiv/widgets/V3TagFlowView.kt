package ceui.pixiv.widgets

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.util.LruCache
import androidx.core.graphics.toColorInt
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.SearchActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.models.TagsBean
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.SearchTypeUtil
import ceui.loxia.Tag
import ceui.pixiv.ui.settings.CustomThemeColor
import ceui.pixiv.ui.settings.ThemeColorCatalog
import ceui.pixiv.ui.synonym.SynonymOperate
import ceui.pixiv.ui.translate.translateTag
import ceui.pixiv.utils.buildPinnedTagPreviewJson
import ceui.pixiv.utils.pinnedTagTranslation
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.widget.WitTagFlowView
import ceui.pixiv.witstudio.widget.WitTagItem
import com.hjq.toast.Toaster

internal fun resolveTagTranslationColor(palette: V3Palette): Int {
    val settings = Shaft.sSettings ?: return palette.textTag
    if (settings.isTagTranslationColorFollowTheme) return palette.textTag
    val index = settings.tagTranslationColorIndex
    val hex = when {
        index == CustomThemeColor.INDEX ->
            CustomThemeColor.normalize(settings.tagTranslationColorCustomHex)
        index in ThemeColorCatalog.entries.indices -> ThemeColorCatalog.hexOf(index)
        else -> null
    } ?: return palette.textTag
    return V3Palette(hex.toColorInt(), palette.isDark).textTag
}

/** Pixiv 标签的业务入口。布局、主题、编辑器与选择能力统一由 witstudio 提供。 */
class V3TagFlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WitTagFlowView(context, attrs, defStyleAttr) {
    var searchIndex: Int = 0
    var onPinTag: ((name: String, translated: String?, newPinned: Boolean) -> Unit)? = null
    var onViewAuthorWorks: ((name: String) -> Unit)? = null
    /** 搜索首页在固定状态变化后刷新；详情和 feed 无需额外处理。 */
    var onTagActionsChanged: Runnable? = null

    override fun setItems(values: List<WitTagItem>) {
        values.forEach { item ->
            item.translation?.takeIf { it.isNotBlank() }?.let { knownTranslations.put(item.name, it) }
        }
        super.setItems(values)
    }

    fun setTags(tags: List<Tag>) {
        setItems(tags.map { WitTagItem(it.name.orEmpty(), it.name.orEmpty(), it.translated_name) })
    }

    fun setJavaTags(tags: List<TagsBean>) {
        setItems(tags.map { WitTagItem(it.name.orEmpty(), it.name.orEmpty(), it.translated_name) })
    }

    override fun translationColor(palette: V3Palette): Int = resolveTagTranslationColor(palette)

    override fun onDefaultTagClick(item: WitTagItem) {
        context.startActivity(Intent(context, SearchActivity::class.java).apply {
            putExtra(Params.KEY_WORD, item.name)
            putExtra(Params.INDEX, searchIndex)
        })
    }

    override fun onDefaultTagLongClick(item: WitTagItem): Boolean {
        showTagActionMenu(item.name, item.translation)
        return true
    }

    fun showTagActionMenu(name: String, translation: String?) {
        val existing = PixivOperate.getSearchHistory(name, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD)
        val translated = translation?.takeIf { it.isNotBlank() }
            ?: knownTranslations.get(name) ?: pinnedTagTranslation(existing?.previewIllustsJson)
        val hasTranslation = !translated.isNullOrBlank()
        // 顺序：原文 / 译文（可选）/ 翻译 / 固定 / 添加为同义词 / 屏蔽 / 该作者相关作品
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        labels.add(context.getString(R.string.v3_tag_menu_copy_original))
        actions.add { copyToClipboard(name) }
        if (hasTranslation) {
            labels.add(context.getString(R.string.v3_tag_menu_copy_translation))
            actions.add { copyToClipboard(translated!!) }
        }
        // 翻译原文（#1054）：冷门 tag 没译名、或 pixiv 只给英文译名时现翻成 app 内语言。
        // 不按「有没有译名」做条件隐藏——译名是英文的情况判不准，恒显示最省心。
        // 协程挂在宿主 Fragment 的 view lifecycle 上（列表/详情都在 Fragment 里），
        // 拿不到再退到 Activity；两者都没有的场合不会有这个菜单。
        labels.add(context.getString(R.string.string_translate_caption))
        actions.add {
            val owner = findViewTreeLifecycleOwner() ?: (context as? LifecycleOwner)
            if (owner != null) {
                translateTag(context, owner.lifecycleScope, name)
            }
        }
        val pinHandler = onPinTag
        val pinned = existing != null && existing.isPinned
        labels.add(context.getString(if (pinned) R.string.string_443 else R.string.string_442))
        actions.add {
            if (pinHandler != null) pinHandler.invoke(name, translated, !pinned)
            else {
                val preview = existing?.previewIllustsJson ?: buildPinnedTagPreviewJson(TagsBean().apply {
                    this.name = name
                    this.translated_name = translated
                })
                PixivOperate.insertPinnedSearchHistory(name, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD, !pinned, preview)
                Toaster.showShort(R.string.operate_success)
            }
            onTagActionsChanged?.run()
        }
        // 同义词词典（issue #904）：长按标签加入词典，备注自动填译文。
        // 功能总开关默认关闭，关闭时菜单与本功能存在之前完全一致。
        if (Shaft.sSettings.isSynonymDictEnabled) {
            labels.add(context.getString(R.string.synonym_add_as_synonym))
            actions.add {
                SynonymOperate.showAddAsSynonymDialog(context, name, translated)
            }
        }
        // 已屏蔽的 tag 给「取消屏蔽」而不是再屏蔽一次（issue #1003）——重复 muteTag 的
        // REPLACE 会把「已屏蔽但未生效」的记录重置成生效，且用户无从在此解除屏蔽。
        val alreadyMuted = AppDatabase.getAppDatabase(context).searchDao()
            .getTagMuteEntityByID(name.hashCode()) != null
        if (alreadyMuted) {
            labels.add(context.getString(R.string.v3_tag_menu_unmute))
            actions.add { unMuteTag(name, translated) }
        } else {
            labels.add(context.getString(R.string.v3_tag_menu_mute))
            actions.add { muteTag(name, translated) }
        }

        onViewAuthorWorks?.let { handler ->
            labels.add(context.getString(R.string.tag_menu_author_works))
            actions.add { handler(name) }
        }

        // 标题写明按中的是哪个 tag（issue #1003：列表卡片的 chip 小，容易误按）。
        // 原文译文都给，QMUI 标题不限行数，过长会换行不会截断。
        WitDialog.MenuDialogBuilder(context)
            .setTitle(buildString {
                append(name)
                if (hasTranslation) {
                    append("  "); append(translated)
                }
            })
            .addItems(labels.toTypedArray()) { dialog, which ->
                actions[which].invoke()
                dialog.dismiss()
            }
            .show()
    }

    private fun copyToClipboard(text: String) {
        if (ClipBoardUtils.setPrimaryClip(context, ClipData.newPlainText("pixiv-tag", text))) {
            Toaster.showShort(R.string.has_copyed)
        } else {
            Toaster.showShort(R.string.msg_copy_failed)
        }
    }

    private fun muteTag(name: String, translated: String?) {
        val bean = TagsBean().apply {
            this.name = name
            this.translated_name = translated
        }
        PixivOperate.muteTag(bean)
        Toaster.showShort(R.string.string_382)
    }

    private fun unMuteTag(name: String, translated: String?) {
        val bean = TagsBean().apply {
            this.name = name
            this.translated_name = translated
        }
        PixivOperate.unMuteTag(bean, false)
        Toaster.showShort(R.string.string_383)
    }

    private companion object {
        // 只记 API 已返回的官方译文；打开菜单不预取网络，搜索历史正文仍保持原文。
        val knownTranslations = LruCache<String, String>(256)
    }
}
