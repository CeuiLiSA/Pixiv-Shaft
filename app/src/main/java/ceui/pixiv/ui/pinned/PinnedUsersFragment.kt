package ceui.pixiv.ui.pinned

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.UActivity
import ceui.lisa.databinding.CellItemPinnedUserBinding
import ceui.lisa.databinding.FragmentPinnedTabListBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.loxia.User
import ceui.pixiv.services.requireEntityWrapper
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.clearGlideOnRecycle
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.cardSurface
import ceui.pixiv.witstudio.theme.ripple
import ceui.pixiv.witstudio.theme.shape
import ceui.pixiv.witstudio.theme.v3Font
import com.bumptech.glide.Glide
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 「我置顶的内容」的「作者」Tab（[PinnedTabsFragment] 第 2 页）。
 *
 * 与宿主共享 [PinnedContentViewModel]，自动承接多语言昵称 / 账号 / UID 过滤与实时计数。
 */
class PinnedUsersFragment : Fragment(R.layout.fragment_pinned_tab_list) {

    private val binding by viewBinding(FragmentPinnedTabListBinding::bind)
    private val viewModel: PinnedContentViewModel by viewModels({ requireParentFragment() })

    private var adapter: PinnedUsersAdapter? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val listAdapter = PinnedUsersAdapter(
            onClick = { user ->
                // 开 UActivity 而不是直接开 V3：新旧作者页的分发在 UActivity 里。
                startActivity(Intent(requireContext(), UActivity::class.java).apply {
                    putExtra(Params.USER_ID, user.id)
                })
            },
            onLongClick = ::showItemMenu,
            onUnpin = ::onClickUnpin,
        ).also { adapter = it }

        PinnedTabListHelper.setup(
            binding = binding,
            adapter = listAdapter,
            onRefresh = { viewModel.refreshAll(showSpinner = true) },
            onClearSearch = { viewModel.clearSearchQuery() },
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.refreshing.collect { binding.refreshLayout.isRefreshing = it }
                }
                launch {
                    combine(
                        viewModel.filteredUsers,
                        viewModel.searchQuery,
                        viewModel.hasLoaded,
                    ) { items, query, loaded -> Triple(items, query, loaded) }
                        .collect { (items, query, loaded) ->
                            listAdapter.submitList(items)
                            PinnedTabListHelper.renderEmptyState(
                                binding = binding,
                                hasLoaded = loaded,
                                isEmpty = items.isEmpty(),
                                query = query,
                                defaultEmptyRes = R.string.pinned_empty,
                            )
                        }
                }
            }
        }
    }

    override fun onDestroyView() {
        binding.recyclerView.adapter = null
        adapter = null
        super.onDestroyView()
    }

    private fun showItemMenu(user: User) {
        val ctx = context ?: return
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        val name = user.name.orEmpty()
        if (name.isNotBlank()) {
            labels += ctx.getString(R.string.pinned_user_copy_name)
            actions += { Common.copy(ctx, name) }
        }
        val account = user.account?.trim()?.removePrefix("@").orEmpty()
        if (account.isNotBlank()) {
            labels += ctx.getString(R.string.pinned_user_copy_account)
            actions += { Common.copy(ctx, account) }
        }
        labels += ctx.getString(R.string.pinned_user_copy_id)
        actions += { Common.copy(ctx, user.id.toString()) }
        labels += ctx.getString(R.string.unpin_user)
        actions += { onClickUnpin(user) }

        WitDialog.MenuDialogBuilder(ctx)
            .setTitle(name.ifBlank { "ID ${user.id}" })
            .addItems(labels.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                actions[which].invoke()
            }
            .show()
    }

    private fun onClickUnpin(user: User) {
        val ctx = context ?: return
        val entityWrapper = requireEntityWrapper()
        val appContext = ctx.applicationContext
        WitDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.string_143)
            .setMessage(getString(R.string.unpin_user_confirm_message, user.name.orEmpty()))
            .addAction(R.string.string_142) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.unpin_user, WitDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                viewModel.deletePinnedUser(entityWrapper, appContext, user.id)
                Common.showToast(R.string.unpin_user_success)
                dialog.dismiss()
            }
            .show()
    }

    fun showClearAllDialog() {
        val ctx = context ?: return
        val entityWrapper = requireEntityWrapper()
        val appContext = ctx.applicationContext
        WitDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.string_143)
            .setMessage(R.string.clear_pinned_users_msg)
            .addAction(R.string.string_142) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.string_141, WitDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                viewModel.clearPinnedUsers(entityWrapper, appContext)
                Common.showToast(R.string.pinned_users_cleared)
                dialog.dismiss()
            }
            .show()
    }

    private class PinnedUsersAdapter(
        private val onClick: (User) -> Unit,
        private val onLongClick: (User) -> Unit,
        private val onUnpin: (User) -> Unit,
    ) : ListAdapter<PinnedUserItemHolder, PinnedUsersAdapter.Holder>(DIFF) {

        class Holder(val binding: CellItemPinnedUserBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = CellItemPinnedUserBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            val ctx = binding.root.context
            val palette = V3Palette.from(ctx)
            binding.root.background = ctx.cardSurface(22)
            binding.userAvatar.strokeColor = ColorStateList.valueOf(palette.textAccent)
            binding.userName.typeface = ctx.v3Font(600)
            binding.userAccount.typeface = ctx.v3Font(500)
            binding.userAccount.fontFeatureSettings = "tnum"
            binding.deletePin.background = ctx.ripple(null, shape(999f, Color.WHITE))

            val holder = Holder(binding)
            binding.root.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(getItem(pos).user)
            }
            binding.root.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onLongClick(getItem(pos).user)
                true
            }
            binding.deletePin.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onUnpin(getItem(pos).user)
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val user = getItem(position).user
            holder.binding.userName.text = user.name
            val account = user.account?.trim()?.removePrefix("@").orEmpty()
            holder.binding.userAccount.text = if (account.isNotEmpty()) {
                "@$account · ID ${user.id}"
            } else {
                "ID ${user.id}"
            }
            Glide.with(holder.binding.root.context)
                .load(GlideUtil.getHead(user))
                .into(holder.binding.userAvatar)
        }

        override fun onViewRecycled(holder: Holder) {
            super.onViewRecycled(holder)
            holder.binding.userAvatar.clearGlideOnRecycle()
        }

        private companion object {
            val DIFF = object : DiffUtil.ItemCallback<PinnedUserItemHolder>() {
                override fun areItemsTheSame(
                    oldItem: PinnedUserItemHolder,
                    newItem: PinnedUserItemHolder,
                ): Boolean = oldItem.user.id == newItem.user.id

                override fun areContentsTheSame(
                    oldItem: PinnedUserItemHolder,
                    newItem: PinnedUserItemHolder,
                ): Boolean = oldItem.user.name == newItem.user.name &&
                    oldItem.user.account == newItem.user.account &&
                    oldItem.user.profile_image_urls == newItem.user.profile_image_urls
            }
        }
    }
}
