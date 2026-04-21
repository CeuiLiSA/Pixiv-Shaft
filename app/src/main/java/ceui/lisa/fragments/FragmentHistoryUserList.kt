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
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.UActivity
import ceui.lisa.databinding.FragmentHistoryListBinding
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.loxia.User
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.ui.common.viewBinding
import com.bumptech.glide.Glide
import com.scwang.smart.refresh.footer.ClassicsFooter
import com.scwang.smart.refresh.header.MaterialHeader

/**
 * User visit history tab. Reads from GeneralEntity (recordType=VIEW_USER_HISTORY).
 */
class FragmentHistoryUserList : Fragment(R.layout.fragment_history_list) {

    private val binding by viewBinding(FragmentHistoryListBinding::bind)
    private val viewModel: HistoryUserViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = UserHistoryAdapter(requireContext(), viewModel.items.value?.toMutableList() ?: mutableListOf()) { entity ->
            startActivity(Intent(requireContext(), UActivity::class.java).apply {
                putExtra(Params.USER_ID, entity.id.toInt())
            })
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.refreshLayout.setRefreshHeader(MaterialHeader(requireContext()))
        binding.refreshLayout.setRefreshFooter(ClassicsFooter(requireContext()))
        binding.refreshLayout.setOnRefreshListener {
            viewModel.loadFirst { binding.refreshLayout.finishRefresh() }
        }
        binding.refreshLayout.setOnLoadMoreListener {
            viewModel.loadMore { binding.refreshLayout.finishLoadMore() }
        }

        viewModel.items.observe(viewLifecycleOwner) { data ->
            adapter.updateItems(data)
        }
        viewModel.isEmpty.observe(viewLifecycleOwner) { empty ->
            binding.emptyLayout.isVisible = empty
        }

        if (viewModel.items.value.isNullOrEmpty()) {
            viewModel.loadFirst()
        }
    }

    private class UserHistoryAdapter(
        private val context: android.content.Context,
        private val items: MutableList<GeneralEntity>,
        private val onClick: (GeneralEntity) -> Unit,
    ) : RecyclerView.Adapter<UserHistoryAdapter.VH>() {

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val avatar: ImageView = itemView.findViewById(R.id.user_avatar)
            val name: TextView = itemView.findViewById(R.id.user_name)
        }

        fun updateItems(newItems: List<GeneralEntity>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.cell_history_user, parent, false))

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
}
