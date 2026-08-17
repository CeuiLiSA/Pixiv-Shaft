package ceui.pixiv.ui.settings

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import ceui.lisa.R
import ceui.pixiv.witstudio.popup.WitMenuPopup
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import ceui.pixiv.witstudio.dialog.WitDialogBuilder
import ceui.pixiv.witstudio.dialog.WitTipDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import com.qmuiteam.qmui.widget.dialog.QMUIDialogBuilder
import com.qmuiteam.qmui.widget.dialog.QMUITipDialog
import com.qmuiteam.qmui.skin.QMUISkinManager

/**
 * 弹窗画廊 —— **临时开发工具，phase 7 随 `v3_*` 清理一并删除**。
 *
 * 目的只有一个：在动 146 处调用点**之前**，把 wit 版和 QMUI 原版并排摆出来肉眼比对。
 * 两个入口给的是同一组用例（同标题、同文案、同按钮语义），所以任何视觉差异都是实现差异，
 * 不是内容差异。也是日夜 × 主题档截图验收的载体（Index0 / Index4 / Index6 / Custom × 日夜）。
 *
 * 只在 debug 包里挂出入口（见 `FragmentSettingsExperimental`）。
 */
object WitDialogGallery {

    private const val LONG_MESSAGE =
        "这是一段刻意写长的正文，用来验证内容区在超过弹窗最大高度（85% 屏高）之后的行为：" +
                "标题和底部按钮必须始终可见，只有中间这段文字滚动。\n\n" +
                "wit 版的卡片是竖向 LinearLayout，内容区拿 weight=1 且 height=wrap_content。" +
                "LinearLayout 在总高没超上限时 delta=0，不会把内容区拉伸；超了才把负 delta 只摊给" +
                "带权重的内容区——于是「标题与按钮钉死、中间滚动」是布局本身的性质，不需要额外代码。\n\n" +
                "顺便验证行距、段间距、以及左右 24dp 内边距在长文本下的观感。" +
                "再验证一下在夜间模式下 wit_text_2 的对比度是否足够——它是 60% 不透明度的前景色，" +
                "长段落里最容易暴露对比度不足的问题。\n\n" +
                "最后一段：滚到底时应该能看到内容区底部还有 20dp 留白，" +
                "不会让最后一行文字直接贴着按钮容器。"

    private val MENU_ITEMS: Array<CharSequence> = arrayOf(
        "复制链接", "分享作品", "保存到相册", "屏蔽该作者", "举报", "查看原图",
    )

    private val CHECKABLE_ITEMS: Array<CharSequence> = arrayOf(
        "自动", "仅 Wi-Fi", "仅移动网络", "从不",
    )

    private val MULTI_ITEMS: Array<CharSequence> = arrayOf(
        "插画", "漫画", "小说", "动图", "系列",
    )

    private fun longList(): Array<CharSequence> =
        Array(30) { "列表项 ${it + 1}" }

    private fun toast(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    // ── wit ─────────────────────────────────────────────────────────

    private val WIT_CASES: List<Pair<String, (Context) -> Unit>> = listOf(
        "Message · 标题 + 正文 + 双按钮" to { c: Context ->
            WitDialog.MessageDialogBuilder(c)
                .setTitle("删除下载")
                .setMessage("确定要删除这 3 个已下载的作品吗？此操作不可撤销。")
                .addAction("取消") { d, _ -> d.dismiss() }
                .addAction(0, "删除", WitDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                    d.dismiss(); toast(c, "已删除")
                }
                .show()
            Unit
        },
        "Message · 三按钮（NEUTRAL/POSITIVE/NEGATIVE）" to { c: Context ->
            WitDialog.MessageDialogBuilder(c)
                .setTitle("上次还有未完成的下载")
                .setMessage("检测到 12 个未完成的下载任务，要现在继续吗？")
                .addAction(0, "稍后", WitDialogAction.ACTION_PROP_NEUTRAL) { d, _ -> d.dismiss() }
                .addAction(0, "清空", WitDialogAction.ACTION_PROP_NEGATIVE) { d, _ -> d.dismiss() }
                .addAction(0, "继续", WitDialogAction.ACTION_PROP_POSITIVE) { d, _ -> d.dismiss() }
                .show()
            Unit
        },
        "Message · 长正文（验滚动）" to { c: Context ->
            WitDialog.MessageDialogBuilder(c)
                .setTitle("关于内容区滚动")
                .setMessage(LONG_MESSAGE)
                .addAction("我知道了") { d, _ -> d.dismiss() }
                .show()
            Unit
        },
        "Message · 无标题" to { c: Context ->
            WitDialog.MessageDialogBuilder(c)
                .setMessage("没有标题时，正文的顶部留白要自己补够，不能贴着卡片上沿。")
                .addAction("好") { d, _ -> d.dismiss() }
                .show()
            Unit
        },
        "Message · 竖向按钮容器" to { c: Context ->
            WitDialog.MessageDialogBuilder(c)
                .setTitle("按钮文案很长时")
                .setMessage("横排放不下就该显式传 VERTICAL，而不是指望容器自己换行。")
                .setActionContainerOrientation(WitDialogBuilder.VERTICAL)
                .addAction("确定并且不再提示我这件事") { d, _ -> d.dismiss() }
                .addAction("再想想") { d, _ -> d.dismiss() }
                .show()
            Unit
        },
        "CheckBoxMessage · 勾选读回" to { c: Context ->
            val builder = WitDialog.CheckBoxMessageDialogBuilder(c)
                .setTitle("清除图片缓存")
                .setMessage("同时清除已下载的原图（不含导出到相册的文件）")
            builder.addAction("取消") { d, _ -> d.dismiss() }
            builder.addAction(0, "清除", WitDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                toast(c, "isChecked = ${builder.isChecked}")
            }
            builder.show()
            Unit
        },
        "EditText · 占位 + 默认值" to { c: Context ->
            val builder = WitDialog.EditTextDialogBuilder(c)
                .setTitle("使用 PxveAPI 代理")
                .setPlaceholder("https://example.com/")
                .setDefaultText("https://app-api.pixiv.net/")
                .setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            builder.addAction("取消") { d, _ -> d.dismiss() }
            builder.addAction("确定") { d, _ ->
                val value = builder.editText.text?.toString().orEmpty()
                if (value.isEmpty()) {
                    // 校验不通过就早退：弹窗必须留在原地。这条语义是承重的。
                    toast(c, "不能为空（弹窗应保持打开）")
                    return@addAction
                }
                d.dismiss()
                toast(c, "输入：$value")
            }
            builder.show()
            Unit
        },
        "Menu · 6 项" to { c: Context ->
            WitDialog.MenuDialogBuilder(c)
                .setTitle("更多操作")
                .addItems(MENU_ITEMS) { d, which ->
                    d.dismiss(); toast(c, "选了 ${MENU_ITEMS[which]}")
                }
                .show()
            Unit
        },
        "Menu · 无标题 + 混用 addAction" to { c: Context ->
            WitDialog.MenuDialogBuilder(c)
                .addItems(MENU_ITEMS) { d, which ->
                    d.dismiss(); toast(c, "选了 ${MENU_ITEMS[which]}")
                }
                .addAction("取消") { d, _ -> d.dismiss() }
                .show()
            Unit
        },
        "Checkable · 单选（预选第 2 项）" to { c: Context ->
            val builder = WitDialog.CheckableDialogBuilder(c)
                .setTitle("下载网络限制")
                .setCheckedIndex(1)
            builder.addItems(CHECKABLE_ITEMS) { d, which ->
                d.dismiss(); toast(c, "选了 ${CHECKABLE_ITEMS[which]}")
            }
            builder.show()
            Unit
        },
        "Checkable · 30 项（验滚动 + 预选第 20 项）" to { c: Context ->
            val items = longList()
            val builder = WitDialog.CheckableDialogBuilder(c)
                .setTitle("长列表")
                .setCheckedIndex(19)
            builder.addItems(items) { d, which -> d.dismiss(); toast(c, "选了 ${items[which]}") }
            builder.show()
            Unit
        },
        "MultiCheckable · 多选读回" to { c: Context ->
            val builder = WitDialog.MultiCheckableDialogBuilder(c)
                .setTitle("要下载哪些类型")
                .setCheckedItems(intArrayOf(0, 2))
            builder.addItems(MULTI_ITEMS) { _, _ -> }
            builder.addAction("取消") { d, _ -> d.dismiss() }
            builder.addAction("确定") { d, _ ->
                val indexes = builder.checkedItemIndexes
                if (indexes.isEmpty()) {
                    toast(c, "至少选一项（弹窗应保持打开）")
                    return@addAction
                }
                d.dismiss()
                toast(c, "选了 " + indexes.joinToString { MULTI_ITEMS[it].toString() })
            }
            builder.show()
            Unit
        },
        "Custom · setLayout" to { c: Context ->
            WitDialog.CustomDialogBuilder(c)
                .setLayout(R.layout.wit_gallery_custom_content)
                .setTitle("正在重命名")
                .addAction("后台运行") { d, _ -> d.dismiss() }
                .show()
            Unit
        },
        "Custom · 覆写 onCreateContent" to { c: Context ->
            object : WitDialog.CustomDialogBuilder(c) {
                override fun onCreateContent(
                    dialog: WitDialog,
                    parent: ceui.pixiv.witstudio.dialog.WitDialogView,
                    context: Context,
                ): View = View.inflate(context, R.layout.wit_gallery_custom_content, null)
            }
                .setTitle("覆写扩展点")
                .addAction("关闭") { d, _ -> d.dismiss() }
                .show()
            Unit
        },
        "TipDialog · 转圈（2 秒）" to { c: Context ->
            val dialog = WitTipDialog.Builder(c).create()
            dialog.show()
            dialog.window?.decorView?.postDelayed({ dialog.dismiss() }, 2000L)
            Unit
        },
        "TipDialog · 转圈 + 文案（2 秒）" to { c: Context ->
            val dialog = WitTipDialog.Builder(c).setTipWord("正在检查屏蔽状态…").create()
            dialog.show()
            dialog.window?.decorView?.postDelayed({ dialog.dismiss() }, 2000L)
            Unit
        },
        "Popup · 四角贴边（R4 自测，逐个点掉）" to { c: Context ->
            val activity = c as? android.app.Activity
            if (activity == null) toast(c, "需要 Activity context") else showPopupEdgeProbe(activity)
            Unit
        },
    )

    // ── QMUI 对照组（同一组用例，逐字对应）─────────────────────────

    private val QMUI_CASES: List<Pair<String, (Context) -> Unit>> = listOf(
        "Message · 标题 + 正文 + 双按钮" to { c: Context ->
            QMUIDialog.MessageDialogBuilder(c)
                .setTitle("删除下载")
                .setMessage("确定要删除这 3 个已下载的作品吗？此操作不可撤销。")
                .setSkinManager(QMUISkinManager.defaultInstance(c))
                .addAction("取消") { d, _ -> d.dismiss() }
                .addAction(0, "删除", QMUIDialogAction.ACTION_PROP_NEGATIVE) { d, _ -> d.dismiss() }
                .show()
            Unit
        },
        "Message · 三按钮（NEUTRAL/POSITIVE/NEGATIVE）" to { c: Context ->
            QMUIDialog.MessageDialogBuilder(c)
                .setTitle("上次还有未完成的下载")
                .setMessage("检测到 12 个未完成的下载任务，要现在继续吗？")
                .setSkinManager(QMUISkinManager.defaultInstance(c))
                .addAction(0, "稍后", QMUIDialogAction.ACTION_PROP_NEUTRAL) { d, _ -> d.dismiss() }
                .addAction(0, "清空", QMUIDialogAction.ACTION_PROP_NEGATIVE) { d, _ -> d.dismiss() }
                .addAction(0, "继续", QMUIDialogAction.ACTION_PROP_POSITIVE) { d, _ -> d.dismiss() }
                .show()
            Unit
        },
        "Message · 长正文（验滚动）" to { c: Context ->
            QMUIDialog.MessageDialogBuilder(c)
                .setTitle("关于内容区滚动")
                .setMessage(LONG_MESSAGE)
                .setSkinManager(QMUISkinManager.defaultInstance(c))
                .addAction("我知道了") { d, _ -> d.dismiss() }
                .show()
            Unit
        },
        "Message · 无标题" to { c: Context ->
            QMUIDialog.MessageDialogBuilder(c)
                .setMessage("没有标题时，正文的顶部留白要自己补够，不能贴着卡片上沿。")
                .setSkinManager(QMUISkinManager.defaultInstance(c))
                .addAction("好") { d, _ -> d.dismiss() }
                .show()
            Unit
        },
        "Message · 竖向按钮容器" to { c: Context ->
            QMUIDialog.MessageDialogBuilder(c)
                .setTitle("按钮文案很长时")
                .setMessage("横排放不下就该显式传 VERTICAL，而不是指望容器自己换行。")
                .setActionContainerOrientation(QMUIDialogBuilder.VERTICAL)
                .setSkinManager(QMUISkinManager.defaultInstance(c))
                .addAction("确定并且不再提示我这件事") { d, _ -> d.dismiss() }
                .addAction("再想想") { d, _ -> d.dismiss() }
                .show()
            Unit
        },
        "CheckBoxMessage · 勾选读回" to { c: Context ->
            val builder = QMUIDialog.CheckBoxMessageDialogBuilder(c)
                .setTitle("清除图片缓存")
                .setMessage("同时清除已下载的原图（不含导出到相册的文件）")
            builder.setSkinManager(QMUISkinManager.defaultInstance(c))
            builder.addAction("取消") { d, _ -> d.dismiss() }
            builder.addAction(0, "清除", QMUIDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                toast(c, "isChecked = ${builder.isChecked}")
            }
            builder.show()
            Unit
        },
        "EditText · 占位 + 默认值" to { c: Context ->
            val builder = QMUIDialog.EditTextDialogBuilder(c)
                .setTitle("使用 PxveAPI 代理")
                .setPlaceholder("https://example.com/")
                .setDefaultText("https://app-api.pixiv.net/")
                .setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            builder.setSkinManager(QMUISkinManager.defaultInstance(c))
            builder.addAction("取消") { d, _ -> d.dismiss() }
            builder.addAction("确定") { d, _ ->
                val value = builder.editText.text?.toString().orEmpty()
                if (value.isEmpty()) {
                    toast(c, "不能为空（弹窗应保持打开）")
                    return@addAction
                }
                d.dismiss()
                toast(c, "输入：$value")
            }
            builder.show()
            Unit
        },
        "Menu · 6 项" to { c: Context ->
            QMUIDialog.MenuDialogBuilder(c)
                .setTitle("更多操作")
                .addItems(MENU_ITEMS) { d, which -> d.dismiss(); toast(c, "选了 ${MENU_ITEMS[which]}") }
                .setSkinManager(QMUISkinManager.defaultInstance(c))
                .show()
            Unit
        },
        "Menu · 无标题 + 混用 addAction" to { c: Context ->
            QMUIDialog.MenuDialogBuilder(c)
                .addItems(MENU_ITEMS) { d, which -> d.dismiss(); toast(c, "选了 ${MENU_ITEMS[which]}") }
                .addAction("取消") { d, _ -> d.dismiss() }
                .setSkinManager(QMUISkinManager.defaultInstance(c))
                .show()
            Unit
        },
        "Checkable · 单选（预选第 2 项）" to { c: Context ->
            val builder = QMUIDialog.CheckableDialogBuilder(c)
                .setTitle("下载网络限制")
                .setCheckedIndex(1)
            builder.setSkinManager(QMUISkinManager.defaultInstance(c))
            builder.addItems(CHECKABLE_ITEMS) { d, which ->
                d.dismiss(); toast(c, "选了 ${CHECKABLE_ITEMS[which]}")
            }
            builder.show()
            Unit
        },
        "Checkable · 30 项（验滚动 + 预选第 20 项）" to { c: Context ->
            val items = longList()
            val builder = QMUIDialog.CheckableDialogBuilder(c)
                .setTitle("长列表")
                .setCheckedIndex(19)
            builder.setSkinManager(QMUISkinManager.defaultInstance(c))
            builder.addItems(items) { d, which -> d.dismiss(); toast(c, "选了 ${items[which]}") }
            builder.show()
            Unit
        },
        "MultiCheckable · 多选读回" to { c: Context ->
            val builder = QMUIDialog.MultiCheckableDialogBuilder(c)
                .setTitle("要下载哪些类型")
                .setCheckedItems(intArrayOf(0, 2))
            builder.setSkinManager(QMUISkinManager.defaultInstance(c))
            builder.addItems(MULTI_ITEMS) { _, _ -> }
            builder.addAction("取消") { d, _ -> d.dismiss() }
            builder.addAction("确定") { d, _ ->
                val indexes = builder.checkedItemIndexes
                if (indexes == null || indexes.isEmpty()) {
                    toast(c, "至少选一项（弹窗应保持打开）")
                    return@addAction
                }
                d.dismiss()
                toast(c, "选了 " + indexes.joinToString { MULTI_ITEMS[it].toString() })
            }
            builder.show()
            Unit
        },
        "Custom · setLayout" to { c: Context ->
            QMUIDialog.CustomDialogBuilder(c)
                .setLayout(R.layout.wit_gallery_custom_content)
                .setTitle("正在重命名")
                .setSkinManager(QMUISkinManager.defaultInstance(c))
                .addAction("后台运行") { d, _ -> d.dismiss() }
                .show()
            Unit
        },
        "Custom · 覆写 onCreateContent" to { c: Context ->
            object : QMUIDialog.CustomDialogBuilder(c) {
                override fun onCreateContent(
                    dialog: QMUIDialog,
                    parent: com.qmuiteam.qmui.widget.dialog.QMUIDialogView,
                    context: Context,
                ): View = View.inflate(context, R.layout.wit_gallery_custom_content, null)
            }
                .setTitle("覆写扩展点")
                .addAction("关闭") { d, _ -> d.dismiss() }
                .show()
            Unit
        },
        "TipDialog · 转圈（2 秒）" to { c: Context ->
            val dialog = QMUITipDialog.Builder(c)
                .setIconType(QMUITipDialog.Builder.ICON_TYPE_LOADING)
                .create()
            dialog.show()
            dialog.window?.decorView?.postDelayed({ dialog.dismiss() }, 2000L)
            Unit
        },
        "TipDialog · 转圈 + 文案（2 秒）" to { c: Context ->
            val dialog = QMUITipDialog.Builder(c)
                .setIconType(QMUITipDialog.Builder.ICON_TYPE_LOADING)
                .setTipWord("正在检查屏蔽状态…")
                .create()
            dialog.show()
            dialog.window?.decorView?.postDelayed({ dialog.dismiss() }, 2000L)
            Unit
        },
    )

    /** wit 版索引。索引本身也是一个 wit MenuDialog —— 顺便当第 0 个用例看。 */
    @JvmStatic
    fun showWit(context: Context) {
        val titles: Array<CharSequence> = WIT_CASES.map { it.first as CharSequence }.toTypedArray()
        WitDialog.MenuDialogBuilder(context)
            .setTitle("wit studio 弹窗")
            .addItems(titles) { dialog, which ->
                dialog.dismiss()
                WIT_CASES[which].second(context)
            }
            .show()
    }

    /**
     * [WitPopup] 的贴边定位自测（对应迁移风险清单 R4）。
     *
     * 手写 PopupWindow 定位的两个容易错的地方 ——「锚点贴右边缘时左右钳制」和
     * 「锚点贴底部时方向翻到上方」—— 在真实页面里很难稳定复现（要么锚点在页面中间，
     * 要么入口藏在几层导航后面）。这里直接往 decorView 上挂四个 1×1 的透明锚点，
     * 把四个角一次全打到，判定标准是肉眼可见的一句话：**弹层必须完整可见、不越界、不被裁**。
     *
     * 用完即拆（弹层 dismiss 时移除锚点），不留残留 view。
     */
    @JvmStatic
    fun showPopupEdgeProbe(activity: android.app.Activity) {
        val decor = activity.window.decorView as android.view.ViewGroup
        val metrics = activity.resources.displayMetrics
        val corners = listOf(
            "左上" to (Gravity.START or Gravity.TOP),
            "右上" to (Gravity.END or Gravity.TOP),
            "左下" to (Gravity.START or Gravity.BOTTOM),
            "右下" to (Gravity.END or Gravity.BOTTOM),
        )
        var index = 0
        fun showNext() {
            if (index >= corners.size) return
            val (label, gravity) = corners[index++]
            val anchor = View(activity)
            decor.addView(
                anchor,
                FrameLayout.LayoutParams(1, 1).apply { this.gravity = gravity },
            )
            anchor.post {
                WitMenuPopup.show(
                    activity,
                    anchor,
                    arrayOf<CharSequence>(
                        "$label 锚点",
                        "屏幕 ${metrics.widthPixels}×${metrics.heightPixels}",
                        "弹层必须完整可见",
                        "不越界 / 不被裁",
                    ),
                ) { _, _ -> }.onDismiss {
                    decor.removeView(anchor)
                    // 必须 post 在 decor 上：anchor 这时已经脱离视图树，它的 post 只会进
                    // mRunQueue 等下一次 attach —— 而它永远不会再 attach，链条就断在这。
                    decor.post { showNext() }
                }
            }
        }
        showNext()
    }

    /** QMUI 对照组索引。同理，索引本身就是 QMUI 版 MenuDialog 的样子。 */
    @JvmStatic
    fun showQmui(context: Context) {
        val titles: Array<CharSequence> = QMUI_CASES.map { it.first as CharSequence }.toTypedArray()
        QMUIDialog.MenuDialogBuilder(context)
            .setTitle("QMUI 原版（对照）")
            .addItems(titles) { dialog, which ->
                dialog.dismiss()
                QMUI_CASES[which].second(context)
            }
            .setSkinManager(QMUISkinManager.defaultInstance(context))
            .show()
    }
}
