package ceui.lisa.fragments

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.databinding.ViewDataBinding
import ceui.lisa.R
import ceui.lisa.activities.BaseActivity
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.NovelSeriesAdapter
import ceui.lisa.core.BaseRepo
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.model.ListNovelSeries
import ceui.lisa.models.NovelSeriesItem
import ceui.lisa.repo.NovelSeriesRepo
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.pixiv.ui.novel.CrossSeriesDownloadOptionsSheet
import ceui.pixiv.ui.task.CrossSeriesDownloadTask

/**
 * 某作者「小说系列」总览页（注意：不是单个系列详情页 NovelSeriesFragment）。
 *
 * 新增能力：顶部 Toolbar 下载按钮，点击弹出 [CrossSeriesDownloadOptionsSheet]
 * 三选一——
 *   - 选择下载：多选系列，每个系列各自合并为独立文件
 *   - 全部下载：全部系列，每个各自合并为独立文件
 *   - 合并下载：全部系列合并为唯一一个文件
 */
class FragmentNovelSeries :
    NetListFragment<FragmentBaseListBinding, ListNovelSeries, NovelSeriesItem>() {

    override fun adapter(): BaseAdapter<*, out ViewDataBinding> {
        return NovelSeriesAdapter(allItems, mContext)
    }

    override fun repository(): BaseRepo {
        return NovelSeriesRepo(mActivity.intent.getIntExtra(Params.USER_ID, 0))
    }

    override fun getToolbarTitle(): String {
        return getString(R.string.string_257)
    }

    override fun initToolbar(toolbar: Toolbar) {
        super.initToolbar(toolbar)
        // 通过菜单加一个下载 icon，复用 FragmentNovelSeriesDetail 里同样的模式。
        toolbar.inflateMenu(R.menu.cross_series_download)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.cross_series_download) {
                onClickDownloadEntry()
                true
            } else {
                false
            }
        }
    }

    private fun onClickDownloadEntry() {
        if (!isAdded) return
        if (allItems.isNullOrEmpty()) {
            Common.showToast(getString(R.string.cross_series_no_series_loaded))
            return
        }
        val sheet = CrossSeriesDownloadOptionsSheet()
        sheet.configure { option ->
            when (option) {
                CrossSeriesDownloadOptionsSheet.Option.Pick -> showSeriesPicker()
                CrossSeriesDownloadOptionsSheet.Option.All -> runPerSeries(allItems.toList())
                CrossSeriesDownloadOptionsSheet.Option.Merge -> runMergeAll()
            }
        }
        sheet.show(childFragmentManager, CrossSeriesDownloadOptionsSheet.TAG)
    }

    /**
     * 多选对话框：最简实现——AlertDialog.setMultiChoiceItems。避免引入单独的
     * ActionMode 状态到 NovelSeriesAdapter，保持列表页自身不变。
     */
    private fun showSeriesPicker() {
        val list = allItems.toList()
        if (list.isEmpty()) {
            Common.showToast(getString(R.string.cross_series_no_series_loaded))
            return
        }
        val titles = list.map { it.title.orEmpty() }.toTypedArray()
        val checked = BooleanArray(list.size)

        val dialog = AlertDialog.Builder(mContext)
            .setTitle(getString(R.string.cross_series_pick_dialog_title))
            .setMultiChoiceItems(titles, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            // 占位 text，下面手动覆盖 click listener，以便按勾选数量实时更新 button text
            .setPositiveButton(
                getString(R.string.cross_series_pick_dialog_ok, 0), null,
            )
            .setNegativeButton(getString(R.string.cross_series_pick_dialog_cancel), null)
            .create()

        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            fun refreshOkText() {
                val count = checked.count { it }
                ok.text = getString(R.string.cross_series_pick_dialog_ok, count)
                ok.isEnabled = count > 0
            }
            refreshOkText()
            // 勾选变化时刷新按钮文字
            dialog.listView.setOnItemClickListener { _, _, position, _ ->
                checked[position] = dialog.listView.isItemChecked(position)
                refreshOkText()
            }
            ok.setOnClickListener {
                val picked = list.filterIndexed { idx, _ -> checked[idx] }
                if (picked.isEmpty()) {
                    Common.showToast(getString(R.string.cross_series_pick_empty))
                    return@setOnClickListener
                }
                dialog.dismiss()
                runPerSeries(picked)
            }
        }
        dialog.show()
    }

    private fun runPerSeries(seriesList: List<NovelSeriesItem>) {
        val act = activity as? BaseActivity<*> ?: return
        CrossSeriesDownloadTask.runPerSeries(
            activity = act,
            seriesList = seriesList,
        ) { _, failures ->
            if (!isAdded) return@runPerSeries
            if (failures.isEmpty()) return@runPerSeries
            val msg = failures.joinToString(separator = "\n") { f ->
                "《${f.seriesTitle}》— ${f.reason}"
            }
            AlertDialog.Builder(mContext)
                .setTitle(
                    getString(R.string.batch_download_some_failed, failures.size)
                )
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun runMergeAll() {
        val act = activity as? BaseActivity<*> ?: return
        val list = allItems.toList()
        if (list.isEmpty()) {
            Common.showToast(getString(R.string.cross_series_no_series_loaded))
            return
        }
        // 作者 id / name：allItems 里任何一项的 user 都指向该作者本人。
        val firstUser = list.firstOrNull { it.user != null }?.user
        val authorId = firstUser?.id ?: mActivity.intent.getIntExtra(Params.USER_ID, 0)
        val authorName = firstUser?.name
        CrossSeriesDownloadTask.runAllMergedOne(
            activity = act,
            seriesList = list,
            authorName = authorName,
            authorId = authorId,
        ) { _, _ -> /* toast handled inside task */ }
    }
}
