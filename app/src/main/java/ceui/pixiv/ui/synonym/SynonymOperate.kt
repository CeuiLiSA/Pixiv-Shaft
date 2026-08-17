package ceui.pixiv.ui.synonym

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import ceui.lisa.R
import ceui.lisa.database.AppDatabase
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Common
import ceui.pixiv.db.synonym.SynonymDao
import ceui.pixiv.db.synonym.SynonymTagEntity
import ceui.pixiv.db.synonym.SynonymTargetEntity
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogView
import java.util.concurrent.Executors

/**
 * 同义词词典共享操作弹窗（issue #904）。
 *
 * 所有入口（3 个插画详情页 / 小说页 / 匹配框 / 管理页）共用这一套 WitDialog 流程，
 * 保证不同详情页实现之间行为一致（用户设置必须全局适配原则）。
 *
 * 日夜由 witstudio 自带的 values-night 处理，弹窗侧不需要挂任何皮肤管理器。
 * DB 写入后由 Room LiveData 驱动各处 UI 自动刷新，不需要手动回调链。
 *
 * 线程模型：全表级查询（getRecentTargets）放 [dbExecutor]；单目标索引查询（getTargetByName /
 * getSynonymsOfTarget 等，毫秒级）沿用项目 PixivOperate 的主线程同步模式。
 */
object SynonymOperate {

    /** 「添加为同义词」菜单最多列出的目标标签数（词典可能数千个目标，全列菜单不可用） */
    private const val MENU_TARGET_LIMIT = 20

    private val dbExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "synonym-operate-db").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun dao(context: Context): SynonymDao =
        AppDatabase.getAppDatabase(context).synonymDao()

    /** 后台查询回主线程弹窗前，确认宿主 Activity 还活着（避免 BadTokenException）。
     *  view 的 context 可能是 ContextThemeWrapper，要沿 wrapper 链找到底层 Activity 再判活。 */
    private fun canShowDialog(context: Context): Boolean {
        var c: Context? = context
        while (c is android.content.ContextWrapper) {
            if (c is Activity) {
                return !c.isFinishing && !c.isDestroyed
            }
            c = c.baseContext
        }
        return true
    }

    // ────────────────────────────────────────────────────────────────
    // 入口 1：长按作品标签 →「添加为同义词标签」
    // ────────────────────────────────────────────────────────────────

    /**
     * issue：点击后显示 新建目标标签 与 目标标签列表作为选项；
     * 不论如何选择，长按标签存在 tag 译文时，备注自动填入译文。
     * 目标过多时只列最近 [MENU_TARGET_LIMIT] 个（全量在管理页里）。
     *
     * issue #910：去掉了「新建目标标签自动把作品收藏进同名收藏标签」的闭环 ——
     * 每次都得再手动取消收藏，没有实际意义（最初设计想多了）。
     */
    @JvmStatic
    @JvmOverloads
    fun showAddAsSynonymDialog(
        context: Context,
        tagName: String,
        translatedName: String?,
        onDone: (() -> Unit)? = null,
    ) {
        dbExecutor.execute {
            val targets = dao(context).getRecentTargets(MENU_TARGET_LIMIT)
            mainHandler.post {
                if (!canShowDialog(context)) return@post
                showAddAsSynonymMenu(context, tagName, translatedName, targets, onDone)
            }
        }
    }

    private fun showAddAsSynonymMenu(
        context: Context,
        tagName: String,
        translatedName: String?,
        targets: List<SynonymTargetEntity>,
        onDone: (() -> Unit)?,
    ) {
        // 菜单结构：[新建目标标签] + 最近 N 个目标 + [手动输入目标标签名…]
        // 最后一项兜底覆盖「目标很多、想加进第 N+1 个旧目标」的场景（issue 要求所有目标可选）
        val labels = ArrayList<String>(targets.size + 2)
        labels.add(context.getString(R.string.synonym_new_target))
        targets.forEach { labels.add(it.name) }
        labels.add(context.getString(R.string.synonym_pick_target_by_name))

        WitDialog.MenuDialogBuilder(context)
            .addItems(labels.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> {
                        // 新建目标标签，建好后把当前长按标签挂进去
                        showCreateTargetDialog(context) { target ->
                            addSynonymChecked(context, target, tagName, translatedName, onDone)
                        }
                    }
                    labels.size - 1 -> {
                        // 手动输入已有目标标签名（不在最近列表里的旧目标）
                        showPickTargetByNameDialog(context, tagName, translatedName, onDone)
                    }
                    else -> {
                        addSynonymChecked(context, targets[which - 1], tagName, translatedName, onDone)
                    }
                }
            }
            .show()
    }

    /** 输入已有目标标签名，把当前标签挂进去（目标数超过菜单上限时的兜底入口） */
    private fun showPickTargetByNameDialog(
        context: Context,
        tagName: String,
        translatedName: String?,
        onDone: (() -> Unit)?,
    ) {
        val builder = WitDialog.EditTextDialogBuilder(context)
        builder.setTitle(R.string.synonym_pick_target_by_name)
            .setPlaceholder(context.getString(R.string.synonym_new_target_hint))
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.sure)) { dialog, _ ->
                val name = builder.editText.text?.toString()?.trim().orEmpty()
                val target = dao(context).getTargetByName(name)
                if (target == null) {
                    Common.showToast(context.getString(R.string.synonym_target_not_found, name))
                    return@addAction
                }
                dialog.dismiss()
                addSynonymChecked(context, target, tagName, translatedName, onDone)
            }
            .show()
    }

    // ────────────────────────────────────────────────────────────────
    // 入口 2：新建目标标签
    // ────────────────────────────────────────────────────────────────

    /**
     * issue：自定义的目标标签中不能有空格（Pixiv 使用空格作为标签间的分隔符）。
     */
    @JvmStatic
    @JvmOverloads
    fun showCreateTargetDialog(
        context: Context,
        onCreated: ((SynonymTargetEntity) -> Unit)? = null,
    ) {
        val builder = WitDialog.EditTextDialogBuilder(context)
        builder.setTitle(R.string.synonym_new_target)
            .setPlaceholder(context.getString(R.string.synonym_new_target_hint))
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.add)) { dialog, _ ->
                val name = builder.editText.text?.toString()?.trim().orEmpty()
                if (name.isEmpty() || name.contains(' ') || name.contains('　')) {
                    Common.showToast(context.getString(R.string.synonym_target_name_invalid))
                    return@addAction
                }
                if (dao(context).getTargetByName(name) != null) {
                    Common.showToast(context.getString(R.string.synonym_target_exists))
                    return@addAction
                }
                val target = SynonymTargetEntity(name = name)
                var id = dao(context).insertTarget(target)
                if (id == -1L) {
                    // IGNORE 策略下唯一索引冲突（并发写入等罕见情况）→ 复用已存在的行
                    id = dao(context).getTargetByName(name)?.id ?: return@addAction
                }
                dialog.dismiss()
                onCreated?.invoke(target.copy(id = id))
            }
            .show()
    }

    /** 重命名目标标签。重命名为已存在的目标标签名 → 合并两者（issue #905） */
    @JvmStatic
    fun showRenameTargetDialog(context: Context, target: SynonymTargetEntity) {
        val builder = WitDialog.EditTextDialogBuilder(context)
        builder.setTitle(context.getString(R.string.synonym_rename_target_title, target.name))
            .setPlaceholder(context.getString(R.string.synonym_new_target_hint))
            .setDefaultText(target.name)
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.sure)) { dialog, _ ->
                val name = builder.editText.text?.toString()?.trim().orEmpty()
                if (name.isEmpty() || name.contains(' ') || name.contains('　')) {
                    Common.showToast(context.getString(R.string.synonym_target_name_invalid))
                    return@addAction
                }
                val existing = dao(context).getTargetByName(name)
                if (existing != null && existing.id != target.id) {
                    // issue #905：与已有目标同名 → 不再报错阻止，转为二次确认后合并
                    //（典型场景：用户自建 "Genshin" 与内置词典导入的 "原神" 并存，想合并成一个）
                    dialog.dismiss()
                    showMergeTargetDialog(context, source = target, dest = existing)
                    return@addAction
                }
                if (name == target.name) {
                    // 没改动 → 直接关掉，不弹无意义的确认
                    dialog.dismiss()
                    return@addAction
                }
                // issue #910：普通重命名也二次确认 —— 没删干净就粘贴/打错字时给个拦截点
                dialog.dismiss()
                showRenameTargetConfirm(context, target, name)
            }
            .show()
    }

    /** 普通重命名的二次确认（issue #910） */
    private fun showRenameTargetConfirm(
        context: Context,
        target: SynonymTargetEntity,
        newName: String,
    ) {
        WitDialog.MessageDialogBuilder(context)
            .setTitle(target.name)
            .setMessage(context.getString(R.string.synonym_rename_target_confirm, target.name, newName))
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.sure)) { dialog, _ ->
                dao(context).renameTarget(target.id, newName)
                Common.showToast(context.getString(R.string.operate_success))
                dialog.dismiss()
            }
            .create()
            .show()
    }

    /** 合并确认框：把 [source] 并入 [dest]（issue #905） */
    private fun showMergeTargetDialog(
        context: Context,
        source: SynonymTargetEntity,
        dest: SynonymTargetEntity,
    ) {
        WitDialog.MessageDialogBuilder(context)
            .setTitle(source.name + " → " + dest.name)
            .setMessage(context.getString(R.string.synonym_merge_confirm, dest.name, source.name))
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.synonym_merge)) { dialog, _ ->
                mergeTargets(context, source, dest)
                dialog.dismiss()
            }
            .create()
            .show()
    }

    /**
     * 合并两个目标标签（issue #905）：
     * - source 的同义词全部并入 dest（大小写不敏感去重，dest 已覆盖的丢弃）
     * - source 的名字自身转为 dest 的同义词 —— 目标标签名参与匹配，合并后不能丢，
     *   否则只带 source 名字标签的作品会从匹配结果里消失
     * - 删除 source 目标标签
     */
    private fun mergeTargets(
        context: Context,
        source: SynonymTargetEntity,
        dest: SynonymTargetEntity,
    ) {
        val db = AppDatabase.getAppDatabase(context)
        val dao = db.synonymDao()
        db.runInTransaction {
            val destNorm = dest.name.trim().lowercase()
            val seenNorms = dao.getSynonymsOfTarget(dest.id)
                .mapTo(HashSet()) { it.name.trim().lowercase() }
            dao.getSynonymsOfTarget(source.id).forEach { syn ->
                val norm = syn.name.trim().lowercase()
                if (norm == destNorm || !seenNorms.add(norm)) {
                    dao.deleteSynonymById(syn.id)
                } else {
                    dao.moveSynonymToTarget(syn.id, dest.id)
                }
            }
            val sourceNorm = source.name.trim().lowercase()
            if (sourceNorm != destNorm && seenNorms.add(sourceNorm)) {
                dao.insertSynonym(SynonymTagEntity(targetId = dest.id, name = source.name))
            }
            // issue #910：被合并入的目标冒泡到「最近」顶部
            dao.touchTarget(dest.id, System.currentTimeMillis())
            dao.deleteTargetOnly(source.id)
        }
        Common.showToast(context.getString(R.string.synonym_merged_into, source.name, dest.name))
    }

    /** 删除目标标签（issue：删除要有二次确认；不同步删除用户已收藏的标签） */
    @JvmStatic
    fun showDeleteTargetDialog(context: Context, target: SynonymTargetEntity, synonymCount: Int) {
        WitDialog.MessageDialogBuilder(context)
            .setTitle(target.name)
            .setMessage(
                context.getString(R.string.synonym_delete_target_confirm, target.name, synonymCount)
            )
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.synonym_delete)) { dialog, _ ->
                dao(context).deleteTargetWithSynonyms(target.id)
                Common.showToast(context.getString(R.string.operate_success))
                dialog.dismiss()
            }
            .create()
            .show()
    }

    /** 长按目标标签 → 管理菜单（匹配框 / 管理页共用） */
    @JvmStatic
    fun showTargetMenu(context: Context, target: SynonymTargetEntity, synonymCount: Int) {
        val labels = arrayOf(
            context.getString(R.string.synonym_add_synonym),
            context.getString(R.string.synonym_copy_target),
            context.getString(R.string.synonym_rename),
            context.getString(R.string.synonym_delete),
            context.getString(R.string.synonym_new_target),
        )
        WitDialog.MenuDialogBuilder(context)
            .addItems(labels) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> showAddSynonymToTargetDialog(context, target)
                    1 -> ClipBoardUtils.putTextIntoClipboard(context, target.name)
                    2 -> showRenameTargetDialog(context, target)
                    3 -> showDeleteTargetDialog(context, target, synonymCount)
                    4 -> showCreateTargetDialog(context)
                }
            }
            .show()
    }

    // ────────────────────────────────────────────────────────────────
    // 同义词标签操作
    // ────────────────────────────────────────────────────────────────

    /** 给指定目标手动添加同义词（先输名字，再输备注，两步 EditTextDialog） */
    @JvmStatic
    fun showAddSynonymToTargetDialog(context: Context, target: SynonymTargetEntity) {
        val nameBuilder = WitDialog.EditTextDialogBuilder(context)
        nameBuilder.setTitle(R.string.synonym_add_synonym)
            .setPlaceholder(context.getString(R.string.synonym_synonym_name_hint))
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.add)) { dialog, _ ->
                val name = nameBuilder.editText.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Common.showToast(context.getString(R.string.synonym_synonym_name_invalid))
                    return@addAction
                }
                dialog.dismiss()
                // 第二步：备注（可空，留空 = 无备注）。「取消」是真取消，整个添加操作中止。
                val remarkBuilder = WitDialog.EditTextDialogBuilder(context)
                remarkBuilder.setTitle(name)
                    .setPlaceholder(context.getString(R.string.synonym_remark_hint))
                    .setInputType(InputType.TYPE_CLASS_TEXT)
                    .addAction(context.getString(R.string.cancel)) { d2, _ -> d2.dismiss() }
                    .addAction(context.getString(R.string.add)) { d2, _ ->
                        val remark = remarkBuilder.editText.text?.toString()?.trim()
                            ?.ifEmpty { null }
                        addSynonymChecked(context, target, name, remark, null)
                        d2.dismiss()
                    }
                    .show()
            }
            .show()
    }

    /** 长按同义词 → 管理菜单（匹配框 / 管理页共用） */
    @JvmStatic
    fun showSynonymMenu(context: Context, synonym: SynonymTagEntity) {
        val hasRemark = !synonym.remark.isNullOrBlank()
        val labels = ArrayList<String>(6)
        val actions = ArrayList<() -> Unit>(6)

        // issue #910：重命名 + 编辑备注 合并成一个两输入框弹窗
        labels.add(context.getString(R.string.synonym_edit))
        actions.add { showEditSynonymDialog(context, synonym) }

        labels.add(context.getString(R.string.synonym_copy_synonym))
        actions.add { ClipBoardUtils.putTextIntoClipboard(context, synonym.name) }

        if (hasRemark) {
            // issue #910：复制备注
            labels.add(context.getString(R.string.synonym_copy_remark))
            actions.add { ClipBoardUtils.putTextIntoClipboard(context, synonym.remark!!.trim()) }

            // issue：将备注添加为同义词（在该同义词所属的目标标签下）
            labels.add(context.getString(R.string.synonym_remark_to_synonym))
            actions.add {
                val target = dao(context).getTargetById(synonym.targetId)
                if (target != null) {
                    addSynonymChecked(context, target, synonym.remark!!.trim(), null, null)
                }
            }
            // issue：点击备注 → 搜索备注（管理页单击整行搜的是原文，备注搜索从这里走）
            labels.add(context.getString(R.string.synonym_search_remark))
            actions.add {
                val intent = android.content.Intent(context, ceui.lisa.activities.SearchActivity::class.java)
                intent.putExtra(ceui.lisa.utils.Params.KEY_WORD, synonym.remark!!.trim())
                intent.putExtra(ceui.lisa.utils.Params.INDEX, 0)
                context.startActivity(intent)
            }
        }

        // issue #905：移动到其他目标标签（导入内置词典后重新归类用）
        labels.add(context.getString(R.string.synonym_move_to_target))
        actions.add { showMoveSynonymDialog(context, synonym) }

        labels.add(context.getString(R.string.synonym_delete))
        actions.add { showDeleteSynonymDialog(context, synonym) }

        WitDialog.MenuDialogBuilder(context)
            .addItems(labels.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                actions[which].invoke()
            }
            .show()
    }

    /**
     * 移动同义词到其他目标标签（issue #905）。
     * 目标列表复用「添加为同义词」的姿势：最近 [MENU_TARGET_LIMIT] 个 + 手动输入兜底。
     */
    @JvmStatic
    fun showMoveSynonymDialog(context: Context, synonym: SynonymTagEntity) {
        dbExecutor.execute {
            val targets = dao(context).getRecentTargets(MENU_TARGET_LIMIT)
                .filter { it.id != synonym.targetId }
            mainHandler.post {
                if (!canShowDialog(context)) return@post
                val labels = ArrayList<String>(targets.size + 1)
                targets.forEach { labels.add(it.name) }
                labels.add(context.getString(R.string.synonym_pick_target_by_name))
                WitDialog.MenuDialogBuilder(context)
                    .addItems(labels.toTypedArray()) { dialog, which ->
                        dialog.dismiss()
                        if (which == labels.size - 1) {
                            showMoveSynonymByNameDialog(context, synonym)
                        } else {
                            doMoveSynonym(context, synonym, targets[which])
                        }
                    }
                    .show()
            }
        }
    }

    /** 手动输入目标标签名移动（不在最近列表里的旧目标） */
    private fun showMoveSynonymByNameDialog(context: Context, synonym: SynonymTagEntity) {
        val builder = WitDialog.EditTextDialogBuilder(context)
        builder.setTitle(R.string.synonym_pick_target_by_name)
            .setPlaceholder(context.getString(R.string.synonym_new_target_hint))
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.sure)) { dialog, _ ->
                val name = builder.editText.text?.toString()?.trim().orEmpty()
                val target = dao(context).getTargetByName(name)
                if (target == null) {
                    Common.showToast(context.getString(R.string.synonym_target_not_found, name))
                    return@addAction
                }
                dialog.dismiss()
                doMoveSynonym(context, synonym, target)
            }
            .show()
    }

    /**
     * 执行移动。目的地已覆盖该同义词（大小写变体已存在 / 与目的地目标同名）时，
     * 直接从原处删除 —— 移动的语义目的（该词归属目的地）已经达成，保留只会产生冗余。
     */
    private fun doMoveSynonym(
        context: Context,
        synonym: SynonymTagEntity,
        dest: SynonymTargetEntity,
    ) {
        if (dest.id == synonym.targetId) return
        val dao = dao(context)
        val norm = synonym.name.trim().lowercase()
        val covered = norm == dest.name.trim().lowercase() ||
                dao.getSynonymsOfTarget(dest.id).any { it.name.trim().lowercase() == norm }
        if (covered) {
            dao.deleteSynonymById(synonym.id)
        } else {
            dao.moveSynonymToTarget(synonym.id, dest.id)
        }
        // issue #910：被移入的目标冒泡到「最近」顶部
        dao.touchTarget(dest.id, System.currentTimeMillis())
        Common.showToast(context.getString(R.string.synonym_moved_to, dest.name))
    }

    /**
     * 编辑同义词（issue #910）：把「重命名」+「编辑备注」合并成一个两输入框弹窗，
     * 省去先选哪个菜单项的纠结。上：标签名（必填）；下：备注（可空，清空即删备注）。
     * 内置词典的同义词备注大多为空，常见操作就是补一条备注，单弹窗一次改完。
     */
    @JvmStatic
    fun showEditSynonymDialog(context: Context, synonym: SynonymTagEntity) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun label(textRes: Int, topGap: Int) = TextView(context).apply {
            setText(textRes)
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.v3_text_3))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(topGap) }
        }
        fun field(prefill: String?, hintRes: Int) = EditText(context).apply {
            setText(prefill)
            setHint(hintRes)
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.v3_text_1))
            setHintTextColor(ContextCompat.getColor(context, R.color.v3_text_3))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val nameEdit = field(synonym.name, R.string.synonym_synonym_name_hint)
        val remarkEdit = field(synonym.remark?.takeIf { it.isNotBlank() }, R.string.synonym_remark_hint)
        container.addView(label(R.string.synonym_field_name, 0))
        container.addView(nameEdit)
        container.addView(label(R.string.synonym_field_remark, 12))
        container.addView(remarkEdit)

        object : WitDialog.CustomDialogBuilder(context) {
            override fun onCreateContent(dialog: WitDialog, parent: WitDialogView, ctx: Context): View {
                return container
            }
        }
            .setTitle(context.getString(R.string.synonym_edit_synonym_title))
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.sure)) { dialog, _ ->
                val newName = nameEdit.text?.toString()?.trim().orEmpty()
                if (newName.isEmpty()) {
                    Common.showToast(context.getString(R.string.synonym_synonym_name_invalid))
                    return@addAction
                }
                val newRemark = remarkEdit.text?.toString()?.trim()?.ifEmpty { null }
                dao(context).updateSynonym(synonym.id, newName, newRemark)
                Common.showToast(context.getString(R.string.operate_success))
                dialog.dismiss()
            }
            .create()
            .show()
    }

    /** 删除同义词（二次确认） */
    @JvmStatic
    fun showDeleteSynonymDialog(context: Context, synonym: SynonymTagEntity) {
        WitDialog.MessageDialogBuilder(context)
            .setTitle(synonym.name)
            .setMessage(context.getString(R.string.synonym_delete_synonym_confirm, synonym.name))
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.synonym_delete)) { dialog, _ ->
                dao(context).deleteSynonymById(synonym.id)
                Common.showToast(context.getString(R.string.operate_success))
                dialog.dismiss()
            }
            .create()
            .show()
    }

    // ────────────────────────────────────────────────────────────────
    // 内部：带重复检测的添加
    // ────────────────────────────────────────────────────────────────

    /**
     * issue 重复检测要求：
     * - 同一目标下已存在 → toast 提示
     * - 同一同义词已被其他目标标签使用 → 提示，但给出按钮允许强制添加
     *
     * 重复检测大小写不敏感（issue #905）：匹配引擎 lowercase 归一，"Genshin" 与 "genshin"
     * 是同一词条；与目标标签自身同名的也视为已存在（目标标签名自身参与匹配）。
     */
    private fun addSynonymChecked(
        context: Context,
        target: SynonymTargetEntity,
        name: String,
        remark: String?,
        onDone: (() -> Unit)?,
    ) {
        val dao = dao(context)
        val norm = name.trim().lowercase()
        if (norm == target.name.trim().lowercase()) {
            // 与目标标签自身同名（目标名已参与匹配），没有备注可补 → 维持原行为
            Common.showToast(context.getString(R.string.synonym_already_in_target))
            return
        }
        val existingInTarget = dao.getSynonymsOfTarget(target.id)
            .firstOrNull { it.name.trim().lowercase() == norm }
        if (existingInTarget != null) {
            // issue #910「完全更优时覆盖」：新词带备注、旧词无备注、名字归一相同 → 补全备注，不再驳回。
            // 内置词典同义词备注大多为空，把作品标签连译文加进来时直接补上，省去手动改的纠结。
            val newRemark = remark?.trim()?.ifEmpty { null }
            if (newRemark != null && existingInTarget.remark.isNullOrBlank()) {
                dao.updateSynonymRemark(existingInTarget.id, newRemark)
                dao.touchTarget(target.id, System.currentTimeMillis())
                Common.showToast(context.getString(R.string.synonym_remark_filled, target.name))
                onDone?.invoke()
                return
            }
            Common.showToast(context.getString(R.string.synonym_already_in_target))
            return
        }
        val usedElsewhere = dao.getSynonymsByName(name)
            .firstOrNull { it.targetId != target.id }
        if (usedElsewhere != null) {
            val otherTarget = dao.getTargetById(usedElsewhere.targetId)
            WitDialog.MessageDialogBuilder(context)
                .setTitle(name)
                .setMessage(
                    context.getString(
                        R.string.synonym_in_other_target_confirm,
                        name, otherTarget?.name ?: "?", target.name
                    )
                )
                .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
                .addAction(context.getString(R.string.synonym_force_add)) { dialog, _ ->
                    doInsertSynonym(context, target, name, remark)
                    dialog.dismiss()
                    onDone?.invoke()
                }
                .create()
                .show()
            return
        }
        doInsertSynonym(context, target, name, remark)
        onDone?.invoke()
    }

    private fun doInsertSynonym(
        context: Context,
        target: SynonymTargetEntity,
        name: String,
        remark: String?,
    ) {
        val dao = dao(context)
        dao.insertSynonym(
            SynonymTagEntity(targetId = target.id, name = name, remark = remark)
        )
        // issue #910：收到新同义词的目标冒泡到「最近」顶部（手动输入旧目标后也能刷新）
        dao.touchTarget(target.id, System.currentTimeMillis())
        Common.showToast(context.getString(R.string.synonym_added_to, target.name))
    }
}
