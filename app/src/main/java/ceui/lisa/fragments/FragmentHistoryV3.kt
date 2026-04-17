package ceui.lisa.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.IllustHistoryEntity
import ceui.lisa.databinding.FragmentHistoryV3Binding
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
import ceui.loxia.ObjectPool
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import com.scwang.smart.refresh.footer.ClassicsFooter
import com.scwang.smart.refresh.header.MaterialHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FragmentHistoryV3 : Fragment() {

    private var _binding: FragmentHistoryV3Binding? = null
    private val binding get() = _binding!!

    private val items: MutableList<IllustHistoryEntity> = mutableListOf()
    private val illusts: MutableList<IllustsBean> = mutableListOf()

    private lateinit var listAdapter: HistoryV3Adapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryV3Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { activity?.finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_delete) {
                showClearAllDialog()
                true
            } else false
        }

        listAdapter = HistoryV3Adapter(
            context = requireContext(),
            items = items,
            allIllustsProvider = { illusts },
            onRequestDelete = { pos, entity -> showDeleteDialog(pos, entity) },
        )

        binding.recyclerView.layoutManager =
            StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.adapter = listAdapter

        binding.refreshLayout.setRefreshHeader(MaterialHeader(requireContext()))
        binding.refreshLayout.setRefreshFooter(ClassicsFooter(requireContext()))
        binding.refreshLayout.setOnRefreshListener { loadFirst() }
        binding.refreshLayout.setOnLoadMoreListener { loadMore() }

        loadFirst()
    }

    private fun loadFirst() {
        viewLifecycleOwner.lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                AppDatabase.getAppDatabase(requireContext())
                    .downloadDao()
                    .getAllViewHistory(PAGE_SIZE, 0)
            }
            items.clear()
            items.addAll(data)
            rebuildIllustList()
            listAdapter.submit(data)
            binding.refreshLayout.finishRefresh()
            updateSubtitleAndEmpty()
        }
    }

    private fun loadMore() {
        viewLifecycleOwner.lifecycleScope.launch {
            val offset = items.size
            val data = withContext(Dispatchers.IO) {
                AppDatabase.getAppDatabase(requireContext())
                    .downloadDao()
                    .getAllViewHistory(PAGE_SIZE, offset)
            }
            if (data.isNotEmpty()) {
                listAdapter.append(data)
                appendIllusts(data)
            }
            binding.refreshLayout.finishLoadMore()
            updateSubtitleAndEmpty()
        }
    }

    private fun rebuildIllustList() {
        illusts.clear()
        items.asSequence()
            .filter { it.type == 0 }
            .mapNotNull { Shaft.sGson.fromJson(it.illustJson, IllustsBean::class.java) }
            .forEach {
                ObjectPool.updateIllust(it)
                illusts.add(it)
            }
    }

    private fun appendIllusts(newItems: List<IllustHistoryEntity>) {
        newItems.asSequence()
            .filter { it.type == 0 }
            .mapNotNull { Shaft.sGson.fromJson(it.illustJson, IllustsBean::class.java) }
            .forEach {
                ObjectPool.updateIllust(it)
                illusts.add(it)
            }
    }

    private fun updateSubtitleAndEmpty() {
        binding.historySubtitle.text = getString(R.string.history_count_format, items.size)
        binding.emptyLayout.isVisible = items.isEmpty()
    }

    private fun showDeleteDialog(position: Int, entity: IllustHistoryEntity) {
        val activity = activity ?: return
        QMUIDialog.MessageDialogBuilder(activity)
            .setTitle(getString(R.string.string_143))
            .setMessage(getString(R.string.string_352))
            .setSkinManager(QMUISkinManager.defaultInstance(activity))
            .addAction(getString(R.string.string_142)) { dialog, _ -> dialog.dismiss() }
            .addAction(
                0,
                getString(R.string.string_141),
                QMUIDialogAction.ACTION_PROP_NEGATIVE
            ) { dialog, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabase.getAppDatabase(requireContext()).downloadDao().delete(entity)
                    }
                    val currentPos = items.indexOfFirst {
                        it.illustID == entity.illustID && it.type == entity.type
                    }
                    if (currentPos >= 0) {
                        items.removeAt(currentPos)
                        listAdapter.removeAt(currentPos)
                        if (entity.type == 0) {
                            illusts.removeAll { it.id == entity.illustID }
                        }
                    }
                    Common.showToast(getString(R.string.string_220))
                    dialog.dismiss()
                    updateSubtitleAndEmpty()
                }
            }
            .show()
    }

    private fun showClearAllDialog() {
        val activity = activity ?: return
        if (items.isEmpty()) {
            Common.showToast(getString(R.string.string_254))
            return
        }
        QMUIDialog.MessageDialogBuilder(activity)
            .setTitle(getString(R.string.string_143))
            .setMessage(getString(R.string.string_255))
            .setSkinManager(QMUISkinManager.defaultInstance(activity))
            .addAction(getString(R.string.string_142)) { dialog, _ -> dialog.dismiss() }
            .addAction(
                0,
                getString(R.string.string_141),
                QMUIDialogAction.ACTION_PROP_NEGATIVE
            ) { dialog, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabase.getAppDatabase(requireContext()).downloadDao().deleteAllHistory()
                    }
                    items.clear()
                    illusts.clear()
                    listAdapter.clear()
                    Common.showToast(getString(R.string.string_220))
                    dialog.dismiss()
                    updateSubtitleAndEmpty()
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val PAGE_SIZE = 30
    }
}
