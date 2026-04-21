package ceui.lisa.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.UActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.loxia.User
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.db.RecordType
import com.bumptech.glide.Glide
import com.scwang.smart.refresh.footer.ClassicsFooter
import com.scwang.smart.refresh.header.MaterialHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * User visit history tab. Reads from GeneralEntity (recordType=VIEW_USER_HISTORY).
 */
class FragmentHistoryUserList : Fragment() {

    private val items = mutableListOf<GeneralEntity>()
    private var adapter: UserHistoryAdapter? = null
    private var recyclerView: RecyclerView? = null
    private var refreshLayout: com.scwang.smart.refresh.layout.SmartRefreshLayout? = null
    private var emptyView: View? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_history_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.recycler_view)
        refreshLayout = view.findViewById(R.id.refresh_layout)
        emptyView = view.findViewById(R.id.empty_layout)

        adapter = UserHistoryAdapter(requireContext(), items) { entity ->
            val intent = Intent(requireContext(), UActivity::class.java).apply {
                putExtra(Params.USER_ID, entity.id.toInt())
            }
            startActivity(intent)
        }

        recyclerView?.layoutManager = LinearLayoutManager(requireContext())
        recyclerView?.adapter = adapter

        refreshLayout?.setRefreshHeader(MaterialHeader(requireContext()))
        refreshLayout?.setRefreshFooter(ClassicsFooter(requireContext()))
        refreshLayout?.setOnRefreshListener { loadFirst() }
        refreshLayout?.setOnLoadMoreListener { loadMore() }

        loadFirst()
    }

    private fun loadFirst() {
        viewLifecycleOwner.lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                AppDatabase.getAppDatabase(requireContext()).generalDao()
                    .getByRecordType(RecordType.VIEW_USER_HISTORY, 0, PAGE_SIZE)
            }
            items.clear()
            items.addAll(data)
            adapter?.notifyDataSetChanged()
            refreshLayout?.finishRefresh()
            emptyView?.isVisible = items.isEmpty()
        }
    }

    private fun loadMore() {
        viewLifecycleOwner.lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                AppDatabase.getAppDatabase(requireContext()).generalDao()
                    .getByRecordType(RecordType.VIEW_USER_HISTORY, items.size, PAGE_SIZE)
            }
            if (data.isNotEmpty()) {
                val start = items.size
                items.addAll(data)
                adapter?.notifyItemRangeInserted(start, data.size)
            }
            refreshLayout?.finishLoadMore()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recyclerView = null
        refreshLayout = null
        emptyView = null
        adapter = null
    }

    private class UserHistoryAdapter(
        private val context: android.content.Context,
        private val items: List<GeneralEntity>,
        private val onClick: (GeneralEntity) -> Unit,
    ) : RecyclerView.Adapter<UserHistoryAdapter.VH>() {

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val avatar: ImageView = itemView.findViewById(R.id.user_avatar)
            val name: TextView = itemView.findViewById(R.id.user_name)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.cell_history_user, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entity = items[position]
            val user = runCatching { Shaft.sGson.fromJson(entity.json, User::class.java) }.getOrNull()
            holder.name.text = user?.name ?: "User #${entity.id}"
            val avatarUrl = user?.profile_image_urls?.medium
            if (!avatarUrl.isNullOrEmpty()) {
                Glide.with(context).load(GlideUtil.getUrl(avatarUrl)).circleCrop().into(holder.avatar)
            }
            holder.itemView.setOnClickListener { onClick(entity) }
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        private const val PAGE_SIZE = 30
    }
}
