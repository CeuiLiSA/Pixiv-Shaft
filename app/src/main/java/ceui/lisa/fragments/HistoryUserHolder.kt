package ceui.lisa.fragments

import android.content.Intent
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.UActivity
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellHistoryUserBinding
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.loxia.User
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.Locale

class HistoryUserHolder(val entity: GeneralEntity) : ListItemHolder() {
    override fun getItemId(): Long = entity.id

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return other is HistoryUserHolder && other.entity.updatedTime == entity.updatedTime
    }
}

@ItemHolder(HistoryUserHolder::class)
class HistoryUserViewHolder(bd: CellHistoryUserBinding) :
    ListItemViewHolder<CellHistoryUserBinding, HistoryUserHolder>(bd) {

    private val timeFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    override fun onBindViewHolder(holder: HistoryUserHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val user = runCatching { Shaft.sGson.fromJson(holder.entity.json, User::class.java) }.getOrNull()
        binding.userName.text = user?.name ?: "User #${holder.entity.id}"
        binding.visitTime.text = timeFormat.format(holder.entity.updatedTime)
        val avatarUrl = user?.profile_image_urls?.medium
        if (!avatarUrl.isNullOrEmpty()) {
            Glide.with(context).load(GlideUtil.getUrl(avatarUrl)).into(binding.userAvatar)
        }
        binding.root.setOnClickListener {
            context.startActivity(Intent(context, UActivity::class.java).apply {
                putExtra(Params.USER_ID, holder.entity.id.toInt())
            })
        }
    }
}
