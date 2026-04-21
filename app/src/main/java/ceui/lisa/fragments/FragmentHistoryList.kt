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
import ceui.lisa.models.IllustsBean
import ceui.loxia.ObjectPool
import com.scwang.smart.refresh.footer.ClassicsFooter
import com.scwang.smart.refresh.header.MaterialHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A single-type history list (illust type=0, novel type=1).
 * Used as a child of [FragmentHistoryTabs].
 */
class FragmentHistoryList : Fragment() {

    private val historyType: Int by lazy { arguments?.getInt(ARG_TYPE, 0) ?: 0 }

    private val items = mutableListOf<IllustHistoryEntity>()
    private val illusts = mutableListOf<IllustsBean>()
    private lateinit var listAdapter: HistoryV3Adapter

    private var recyclerView: androidx.recyclerview.widget.RecyclerView? = null
    private var refreshLayout: com.scwang.smart.refresh.layout.SmartRefreshLayout? = null
    private var emptyView: View? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_history_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.recycler_view)
        refreshLayout = view.findViewById(R.id.refresh_layout)
        emptyView = view.findViewById(R.id.empty_layout)

        listAdapter = HistoryV3Adapter(
            context = requireContext(),
            items = items,
            allIllustsProvider = { illusts },
            onRequestDelete = { _, entity -> confirmDelete(entity) },
        )

        val spanCount = if (historyType == TYPE_NOVEL) 1 else 2
        recyclerView?.layoutManager = StaggeredGridLayoutManager(spanCount, StaggeredGridLayoutManager.VERTICAL)
        recyclerView?.itemAnimator = null
        recyclerView?.adapter = listAdapter

        refreshLayout?.setRefreshHeader(MaterialHeader(requireContext()))
        refreshLayout?.setRefreshFooter(ClassicsFooter(requireContext()))
        refreshLayout?.setOnRefreshListener { loadFirst() }
        refreshLayout?.setOnLoadMoreListener { loadMore() }

        loadFirst()
    }

    private fun loadFirst() {
        viewLifecycleOwner.lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                AppDatabase.getAppDatabase(requireContext()).downloadDao()
                    .getViewHistoryByType(historyType, PAGE_SIZE, 0)
            }
            items.clear()
            items.addAll(data)
            rebuildIllustList()
            listAdapter.submit(data)
            refreshLayout?.finishRefresh()
            emptyView?.isVisible = items.isEmpty()
        }
    }

    private fun loadMore() {
        viewLifecycleOwner.lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                AppDatabase.getAppDatabase(requireContext()).downloadDao()
                    .getViewHistoryByType(historyType, PAGE_SIZE, items.size)
            }
            if (data.isNotEmpty()) {
                listAdapter.append(data)
                appendIllusts(data)
            }
            refreshLayout?.finishLoadMore()
        }
    }

    private fun confirmDelete(entity: IllustHistoryEntity) {
        val act = activity ?: return
        androidx.appcompat.app.AlertDialog.Builder(act)
            .setTitle(R.string.string_143)
            .setMessage(R.string.string_352)
            .setPositiveButton(R.string.string_141) { _, _ -> deleteItem(entity) }
            .setNegativeButton(R.string.string_142, null)
            .show()
    }

    private fun deleteItem(entity: IllustHistoryEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppDatabase.getAppDatabase(requireContext()).downloadDao().delete(entity)
            }
            val pos = items.indexOfFirst { it.illustID == entity.illustID && it.type == entity.type }
            if (pos >= 0) {
                items.removeAt(pos)
                listAdapter.removeAt(pos)
                if (entity.type == 0) illusts.removeAll { it.id == entity.illustID }
            }
            emptyView?.isVisible = items.isEmpty()
        }
    }

    private fun rebuildIllustList() {
        illusts.clear()
        if (historyType != TYPE_ILLUST) return
        items.mapNotNull { Shaft.sGson.fromJson(it.illustJson, IllustsBean::class.java) }
            .forEach { ObjectPool.updateIllust(it); illusts.add(it) }
    }

    private fun appendIllusts(newItems: List<IllustHistoryEntity>) {
        if (historyType != TYPE_ILLUST) return
        newItems.mapNotNull { Shaft.sGson.fromJson(it.illustJson, IllustsBean::class.java) }
            .forEach { ObjectPool.updateIllust(it); illusts.add(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recyclerView = null
        refreshLayout = null
        emptyView = null
    }

    companion object {
        private const val ARG_TYPE = "history_type"
        private const val PAGE_SIZE = 30
        private const val TYPE_ILLUST = 0
        private const val TYPE_NOVEL = 1

        fun newInstance(type: Int): FragmentHistoryList = FragmentHistoryList().apply {
            arguments = Bundle().apply { putInt(ARG_TYPE, type) }
        }
    }
}
