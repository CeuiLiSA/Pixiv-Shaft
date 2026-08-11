package ceui.lisa.fragments

import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.os.Bundle
import android.os.Handler
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.BaseActivity
import ceui.lisa.activities.SearchActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.activities.followUser
import ceui.lisa.activities.unfollowUser
import ceui.lisa.adapters.AbstractIllustAdapter
import ceui.lisa.adapters.IllustAdapter
import ceui.pixiv.actions.PixivActions
import ceui.pixiv.ui.bookmark.SelectTagBottomSheet
import ceui.pixiv.ui.detail.UgoiraPlayerAdapter
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentIllustBinding
import ceui.pixiv.ui.muted.MuteTagSheet
import ceui.lisa.download.IllustDownload
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.ObjectSpec
import ceui.lisa.models.TagsBean
import ceui.lisa.models.UserBean
import ceui.lisa.notification.CallBackReceiver
import ceui.lisa.utils.Common
import ceui.lisa.utils.DensityUtil
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.SearchTypeUtil
import ceui.lisa.utils.ShareIllust
import ceui.loxia.ObjectPool
import ceui.loxia.ProgressTextButton
import ceui.loxia.combineLatest
import ceui.loxia.flag.FlagDescFragment
import ceui.pixiv.ui.share.shareFirstImage
import ceui.pixiv.ui.synonym.SynonymOperate
import ceui.pixiv.ui.upscale.IllustAiHelper
import ceui.pixiv.utils.buildPinnedTagPreviewJson
import ceui.pixiv.utils.setOnClick

import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog.CheckableDialogBuilder
import com.qmuiteam.qmui.widget.dialog.QMUIDialog.MessageDialogBuilder
import com.zhy.view.flowlayout.FlowLayout
import com.zhy.view.flowlayout.TagAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FragmentIllust : BaseLazyFragment<FragmentIllustBinding>() {

    private val safeArgs by lazy { IllustArgs(requireArguments()) }

    private class IllustArgs(b: Bundle) {
        val illustId: Int = b.getInt("illust_id")
    }
    private val vm by viewModels<FragmentIllustViewModel> {
        FragmentIllustViewModel.Factory(safeArgs.illustId.toLong())
    }
    private var mReceiver: CallBackReceiver? = null
    private var recyHeight = 0
    private var aiHelper: IllustAiHelper? = null

    // ObjectPool 的每一次发射都会重跑一遍 updateIllust(收藏回流是最常见的一次),下面这组状态用来
    // 让「重建图片区」「重建标签区」「挂 sheet callback」「发头像 Glide 请求」这几件带视觉副作用的
    // 事只在真需要时做——否则收藏一下整页就闪一次(#962)。跟着 view 走,onDestroyView 里清掉。
    private var renderedImageSignature: String? = null
    private var renderedTagSignature: String? = null
    private var bottomSheetCallbackAttached = false
    private var sheetDeltaY = 0
    private var loadedAvatarUrl: String? = null

    public override fun initLayout() {
        mLayoutID = R.layout.fragment_illust
    }

    override fun initView() {
        val illustLiveData = ObjectPool.get<IllustsBean>(safeArgs.illustId.toLong())
        illustLiveData.observe(viewLifecycleOwner) { illust ->
            updateIllust(illust)
        }
        vm.hasDownload.observe(viewLifecycleOwner) { downloaded ->
            baseBind.download.setText(
                if (downloaded) R.string.string_337 else R.string.string_72
            )
        }
        // 网页 ajax 的每页真实宽高到达 → 喂给当前大图 adapter,预置各页展示 ratio(下载前摆准高度)。
        // adapter 建得比数据晚就由这里补,数据比 adapter 晚就由建处 seed(见 IllustAdapter 建处)。
        vm.pageDimensions.observe(viewLifecycleOwner) { dims ->
            (baseBind.recyclerView.adapter as? IllustAdapter)?.seedPageDimensions(dims)
        }
        val userId = illustLiveData.value?.user?.id ?: return
        val userLiveData = ObjectPool.get<UserBean>(userId.toLong())
        userLiveData.observe(viewLifecycleOwner) { user ->
            updateUser(user)
            Common.showLog("updateUser invoke ${user.isIs_followed}")
        }

        ViewCompat.setOnApplyWindowInsetsListener(baseBind.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            if (insets.bottom > 0) {
                baseBind.bottomPlaceHolder.isVisible = true
                baseBind.bottomPlaceHolder.updateLayoutParams {
                    height = insets.bottom
                }
            } else {
                baseBind.bottomPlaceHolder.isVisible = false
            }
            windowInsets
        }
        val illust = illustLiveData.value ?: return
        baseBind.user = userLiveData
        observeMuteStatus(illust)
    }

    private fun observeMuteStatus(illust: IllustsBean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val dao = AppDatabase.getAppDatabase(requireContext()).searchDao()
            val muteIllust = withContext(Dispatchers.IO) {
                dao.getIllustMuteEntityByID(illust.id)
            }
            val muteUser = withContext(Dispatchers.IO) {
                dao.getUserMuteEntityByIDLiveData((illust.user?.userId ?: 0))
            }
            combineLatest(muteIllust, muteUser).observe(viewLifecycleOwner) {
                val illustEntity = it.first
                val userEntity = it.second
                if (illustEntity == null && userEntity == null) {
                    baseBind.contentFrame.isVisible = true
                    baseBind.abandonedFrame.isVisible = false
                } else {
                    baseBind.contentFrame.isVisible = false
                    baseBind.abandonedFrame.isVisible = true
                    // 整页遮罩不再是一块纯黑：糊掉的作品图 + spoiler 粒子。
                    // bind 幂等(同一封面不重发请求)，可以跟着 observer 每次发射照调。
                    baseBind.abandonedSpoiler.bind(Glide.with(this@FragmentIllust), GlideUtil.getMediumImg(illust))
                    baseBind.cancelMuteIllust.isVisible = illustEntity != null
                    baseBind.cancelMuteUser.isVisible = userEntity != null

                    if (illustEntity != null) {
                        baseBind.cancelMuteIllust.setOnClick {
                            viewLifecycleOwner.lifecycleScope.launch {
                                it.showProgress()
                                delay(600L)
                                dao.deleteMuteEntity(illustEntity)
                                it.hideProgress()
                            }
                        }
                    }
                    if (userEntity != null) {
                        baseBind.cancelMuteUser.setOnClick {
                            viewLifecycleOwner.lifecycleScope.launch {
                                it.showProgress()
                                delay(600L)
                                dao.deleteMuteEntity(userEntity)
                                it.hideProgress()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateUser(user: UserBean) {
        if (user.isIs_followed) {
            baseBind.follow.isVisible = false
            baseBind.unfollow.isVisible = true
            baseBind.unfollow.setOnClick {
                unfollowUser(it, user.id)
            }
        } else {
            baseBind.unfollow.isVisible = false
            baseBind.follow.isVisible = true
            baseBind.follow.setOnClick {
                followUser(it, user.id, PixivActions.defaultFollowRestrict())
            }
            baseBind.follow.setOnLongClickListener {
                followUser((it as ProgressTextButton), user.id, Params.TYPE_PRIVATE)
                true
            }
        }
        baseBind.relaIllustBrief.setOnClick {
            val intent = Intent(mContext, UActivity::class.java)
            intent.putExtra(Params.USER_ID, user.id)
            startActivity(intent)
        }
        baseBind.userName.setOnClick {
            val intent = Intent(mContext, UActivity::class.java)
            intent.putExtra(Params.USER_ID, user.id)
            startActivity(intent)
        }
        baseBind.userName.setOnLongClickListener {
            Common.copy(mContext, user.name)
            true
        }

        baseBind.userName.text = user.name
    }

    private fun updateIllust(illust: IllustsBean) {
        if (illust.id == 0 || !illust.isVisible) {
            Common.showToast(R.string.string_206)
            Handler().postDelayed({ finish() }, 1000)
            return
        }

        baseBind.leave.setOnClick {
            viewLifecycleOwner.lifecycleScope.launch {
                it.showProgress()
                delay(600L)
                requireActivity().finish()
                it.hideProgress()
            }
        }

        setupTitle(illust)
        setupToolbarMenu(illust)
        setupLikeButton(illust)
        setupTags(illust)
        setupInfo(illust)
        setupBottomSheet(illust)
        setupActionButtons(illust)
        setupDescription(illust)
        setupStats(illust)
        setupDownloadButton(illust)
        loadUserAvatar(illust)
    }

    private fun setupTitle(illust: IllustsBean) {
        if (illust.series != null && !TextUtils.isEmpty(illust.series.title)) {
            val clickableSpan: ClickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    val intent = Intent(mContext, TemplateActivity::class.java)
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "漫画系列详情")
                    intent.putExtra(Params.MANGA_SERIES_ID, illust.series.id)
                    startActivity(intent)
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.color = Common.resolveThemeAttribute(
                        mContext,
                        androidx.appcompat.R.attr.colorPrimary
                    )
                }
            }
            val seriesString = getString(R.string.string_229)
            val spannableString = SpannableString(
                String.format("@%s %s", seriesString, illust.title)
            )
            spannableString.setSpan(
                clickableSpan, 0, seriesString.length + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            baseBind.title.movementMethod = LinkMovementMethod.getInstance()
            baseBind.title.text = spannableString
        } else {
            baseBind.title.text = illust.title
        }
        baseBind.title.setOnLongClickListener {
            Common.copy(mContext, illust.title)
            true
        }
    }

    private fun setupToolbarMenu(illust: IllustsBean) {
        baseBind.toolbar.menu?.clear()
        baseBind.toolbar.inflateMenu(R.menu.share)
        // 动图(ugoira)的 original 是 zip,加载原图/画质增强/抠图都没法处理,隐藏这几项(对齐 V3 详情页)。
        if (illust.isGif) {
            baseBind.toolbar.menu?.findItem(R.id.action_ai_upscale)?.isVisible = false
            baseBind.toolbar.menu?.findItem(R.id.action_ai_rembg)?.isVisible = false
            baseBind.toolbar.menu?.findItem(R.id.action_show_original)?.isVisible = false
        }
        baseBind.toolbar.setNavigationOnClickListener { mActivity.finish() }
        baseBind.toolbar.setOnMenuItemClickListener(Toolbar.OnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_share -> {
                    object : ShareIllust(mContext, illust) {
                        override fun onPrepare() {}
                    }.execute()
                    true
                }
                R.id.action_share_image -> {
                    shareFirstImage(illust)
                    false
                }
                R.id.action_dislike -> {
                    MuteTagSheet.show(childFragmentManager, illust.tags)
                    true
                }
                R.id.action_copy_link -> {
                    Common.copy(mContext, ShareIllust.URL_Head + illust.id)
                    true
                }
                R.id.action_show_original -> {
                    val adapter = IllustAdapter(
                        mActivity, this@FragmentIllust, illust, recyHeight, true
                    )
                    baseBind.recyclerView.adapter = adapter
                    vm.pageDimensions.value?.let { adapter.seedPageDimensions(it) }
                    true
                }
                R.id.action_mute_illust -> {
                    PixivOperate.muteIllust(illust)
                    true
                }
                R.id.action_flag_illust -> {
                    val intent = Intent(mContext, TemplateActivity::class.java)
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "举报插画")
                    // illust.id 是 IllustsBean 的 Int 字段；TemplateActivity 读这个 extra 走
                    // getLongExtra（配合 Illust.id: Long 的新版详情页入口），这里必须显式转 Long，
                    // 否则又是一次 Int/Long extra 类型不匹配，读回来静默变 0。
                    intent.putExtra(FlagDescFragment.FlagObjectIdKey, illust.id.toLong())
                    intent.putExtra(FlagDescFragment.FlagObjectTypeKey, ObjectSpec.POST)
                    startActivity(intent)
                    true
                }
                R.id.action_ai_upscale -> {
                    ceui.pixiv.ui.upscale.ModelPickerDialog.pickOrUseDefault(childFragmentManager) { model ->
                        aiHelper?.performUpscale(illust, model)
                    }
                    true
                }
                R.id.action_ai_rembg -> {
                    ceui.pixiv.ui.upscale.RembgModelPickerDialog.pickOrUseDefault(childFragmentManager) { model ->
                        aiHelper?.performRembg(illust, model)
                    }
                    true
                }
                else -> false
            }
        })
    }

    private fun setupLikeButton(illust: IllustsBean) {
        if (illust.isIs_bookmarked) {
            baseBind.postLike.setImageResource(R.drawable.ic_favorite_red_24dp)
        } else {
            baseBind.postLike.setImageResource(R.drawable.ic_favorite_grey_24dp)
        }
        baseBind.postLike.setOnClick {
            val willBookmark = !illust.isIs_bookmarked
            if (illust.isIs_bookmarked) {
                baseBind.postLike.setImageResource(R.drawable.ic_favorite_grey_24dp)
            } else {
                baseBind.postLike.setImageResource(R.drawable.ic_favorite_red_24dp)
            }
            PixivOperate.postLikeDefaultStarType(illust)
            // 收藏后自动下载只在用户主动收藏(非取消)时触发,避免和"下载时自动收藏"循环联动(issue #880)。
            if (willBookmark && Shaft.sSettings.isAutoDownloadAfterStar) {
                IllustDownload.downloadIllustAllPages(illust)
            }
        }
        baseBind.postLike.setOnLongClickListener(object : OnLongClickListener {
            override fun onLongClick(v: View): Boolean {
                SelectTagBottomSheet.show(
                    this@FragmentIllust, illust.id, Params.TYPE_ILLUST, illust.tagNames,
                )
                return true
            }
        })
    }

    private fun setupTags(illust: IllustsBean) {
        // 标签区重建 = 整片 chip 全部拆掉重新 inflate,肉眼就是一次闪烁。池发射(收藏回流等)带不来
        // 新标签,所以只在标签本身真的变了才重建;点击/长按监听照常重挂,始终闭包到最新的 bean(#962)。
        val tagSignature = illust.tags.orEmpty().joinToString("|") {
            "${it.name.orEmpty()}/${it.translated_name.orEmpty()}"
        }
        if (tagSignature != renderedTagSignature) {
            renderedTagSignature = tagSignature
            // 同义词词典「标签匹配关系」框（issue #904）
            baseBind.synonymMatch.setWorkTags(illust.tags)
            baseBind.illustTag.adapter = object : TagAdapter<TagsBean>(illust.tags) {
                override fun getView(parent: FlowLayout, position: Int, s: TagsBean): View {
                    val tv = LayoutInflater.from(mContext).inflate(
                        R.layout.recy_single_line_text_new, parent, false
                    ) as TextView
                    var tag = s.name
                    if (!TextUtils.isEmpty(s.translated_name)) {
                        tag = tag + "/" + s.translated_name
                    }
                    tv.text = tag
                    return tv
                }
            }
        }
        baseBind.illustTag.setOnTagClickListener { view, position, parent ->
            val intent = Intent(mContext, SearchActivity::class.java)
            intent.putExtra(Params.KEY_WORD, illust.tags[position].name)
            intent.putExtra(Params.INDEX, 0)
            startActivity(intent)
            true
        }
        baseBind.illustTag.setOnTagLongClickListener { view, position, parent ->
            val tagBean = illust.tags[position]
            val tagName = tagBean.name
            val searchEntity =
                PixivOperate.getSearchHistory(tagName, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD)
            val isPinned = searchEntity != null && searchEntity.isPinned
            val tagMenuBuilder = MessageDialogBuilder(mContext)
                .setTitle(tagName)
                .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                .addAction(if (isPinned) getString(R.string.string_443) else getString(R.string.string_442)) { dialog, index ->
                    val nextPinned = !isPinned
                    val previewJson =
                        if (nextPinned) buildPinnedTagPreviewJson(tagBean, illust) else null
                    PixivOperate.insertPinnedSearchHistory(
                        tagName, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD, nextPinned, previewJson
                    )
                    Common.showToast(R.string.operate_success)
                    dialog.dismiss()
                }
                .addAction(getString(R.string.string_120)) { dialog, index ->
                    Common.copy(mContext, tagName)
                    dialog.dismiss()
                }
            // 同义词词典（issue #904）功能总开关：默认关闭，关闭时菜单与本功能存在之前完全一致
            if (Shaft.sSettings.isSynonymDictEnabled) {
                tagMenuBuilder.addAction(getString(R.string.synonym_add_as_synonym)) { dialog, index ->
                    // 长按标签加入词典，备注自动填译文
                    SynonymOperate.showAddAsSynonymDialog(mContext, tagName, tagBean.translated_name)
                    dialog.dismiss()
                }
            }
            tagMenuBuilder.create().show()
            true
        }
    }

    private fun setupInfo(illust: IllustsBean) {
        baseBind.illustSize.text = getString(R.string.string_193, illust.width, illust.height)
        baseBind.illustId.text = getString(R.string.string_194, illust.id)
        baseBind.userId.text = getString(R.string.string_195, illust.user.id)
        baseBind.illustId.setOnClick { Common.copy(mContext, illust.id.toString()) }
        baseBind.userId.setOnClick { Common.copy(mContext, illust.user.id.toString()) }
    }

    /**
     * 图片区（RecyclerView + adapter）真正依赖的数据指纹。指纹没变就不重建 adapter（#962）。
     * 覆盖面对齐 [IllustAdapter] / [UgoiraPlayerAdapter] 实际读的字段：
     * 用哪个 adapter([isGif])、几页([page_count])、pos0 定高([width]/[height])、各页图 url。
     * 精简 bean → detail 全量覆盖（#569：池里 bean 缺分页图/原图）这一步 url 会从无到有，
     * 指纹必变，图片区照样重建；而收藏回流那种「只动 is_bookmarked / total_bookmarks」的
     * 池发射指纹不变，不再触发重建。
     */
    private fun imageAreaSignature(illust: IllustsBean): String {
        val urls = if (illust.page_count <= 1) {
            illust.meta_single_page?.original_image_url.orEmpty()
        } else {
            illust.meta_pages?.joinToString("|") { it.image_urls?.original.orEmpty() }.orEmpty()
        }
        return "${illust.isGif()}|${illust.page_count}|${illust.width}x${illust.height}|$urls"
    }

    private fun setupBottomSheet(illust: IllustsBean) {
        val sheetBehavior: BottomSheetBehavior<*> = BottomSheetBehavior.from(baseBind.coreLinear)
        baseBind.coreLinear.viewTreeObserver.addOnGlobalLayoutListener(object :
            OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                view ?: return
                context ?: return
                val realHeight = baseBind.bottomBar.height +
                        baseBind.viewDivider.height +
                        baseBind.secondLinear.height
                val maxHeight = resources.displayMetrics.heightPixels * 3 / 4
                val params = baseBind.coreLinear.layoutParams
                val slideMaxHeight = Math.min(realHeight, maxHeight)
                params.height = slideMaxHeight
                baseBind.coreLinear.layoutParams = params
                val bottomCardHeight = baseBind.bottomBar.height
                sheetDeltaY = slideMaxHeight - baseBind.bottomBar.height
                sheetBehavior.setPeekHeight(bottomCardHeight, true)

                val headParams = baseBind.helperView.layoutParams
                headParams.height = bottomCardHeight - DensityUtil.dp2px(16.0f)
                baseBind.helperView.layoutParams = headParams
                // 每次发射都重挂一个 callback 会让它无限累积(同一次 onSlide 被回调 N 次),挂一次就够。
                // deltaY 走字段而不是闭包:sheet 会随内容(简介补拉到货)重新量高,回调必须用最新的那份。
                if (!bottomSheetCallbackAttached) {
                    bottomSheetCallbackAttached = true
                    sheetBehavior.addBottomSheetCallback(object : BottomSheetCallback() {
                        override fun onStateChanged(bottomSheet: View, newState: Int) {}
                        override fun onSlide(bottomSheet: View, slideOffset: Float) {
                            baseBind.refreshLayout.translationY = -sheetDeltaY * slideOffset * 0.7f
                        }
                    })
                }
                recyHeight = baseBind.recyclerView.height
                // 上面的高度测算每次都要跑(简介补拉到货后 sheet 要重新长高),但图片区不能跟着重建:
                // 换 layoutManager + new adapter = 所有大图从零重新加载,这就是收藏一下整页闪一次的原因(#962)。
                val signature = imageAreaSignature(illust)
                if (signature != renderedImageSignature) {
                    renderedImageSignature = signature
                    baseBind.recyclerView.layoutManager = LinearLayoutManager(mContext)
                    if (illust.isGif()) {
                        // ugoira 内联播放:以前 VActivity 把动图甩去独立的 FragmentSingleUgora,
                        // 现在留在本页,用解耦的 UgoiraPlayerAdapter 进页即自动加载+播放。
                        val maxHeight = resources.displayMetrics.heightPixels * 3 / 4
                        baseBind.recyclerView.adapter =
                            UgoiraPlayerAdapter(illust, viewLifecycleOwner, maxHeight)
                    } else {
                        val adapter = IllustAdapter(mActivity, this@FragmentIllust, illust, recyHeight, false)
                        baseBind.recyclerView.adapter = adapter
                        vm.pageDimensions.value?.let { adapter.seedPageDimensions(it) }
                    }
                } else {
                    // 不重建,但 bean 实例可能已被池的 merge 换成新的一份(收藏态就在里面)。
                    // 只顶掉引用、不动视图,免得 adapter 的长按下载和跳二级详情读到过期的收藏态。
                    (baseBind.recyclerView.adapter as? AbstractIllustAdapter<*>)?.rebindIllust(illust)
                }
                baseBind.coreLinear.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })
    }

    private fun setupActionButtons(illust: IllustsBean) {
        baseBind.related.setOnClick {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关作品")
            intent.putExtra(Params.ILLUST_ID, illust.id)
            intent.putExtra(Params.ILLUST_TITLE, illust.title)
            startActivity(intent)
        }
        baseBind.comment.setOnClick {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关评论")
            intent.putExtra(Params.ILLUST_ID, illust.id)
            intent.putExtra(Params.ILLUST_TITLE, illust.title)
            startActivity(intent)
        }
        baseBind.illustLike.setOnClick {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(Params.CONTENT, illust)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "喜欢这个作品的用户")
            startActivity(intent)
        }
    }

    private fun setupDescription(illust: IllustsBean) {
        val caption = illust.caption
        if (caption.isNullOrEmpty()) {
            baseBind.description.visibility = View.GONE
            return
        }
        baseBind.description.visibility = View.VISIBLE
        // HtmlTextView.setHtml 在 caption 含 <a> 链接时会直接吐出空串（#552）。
        // 换成 androidx HtmlCompat.fromHtml + LinkMovementMethod，文本和可点链接都能正常渲染。
        baseBind.description.text = androidx.core.text.HtmlCompat.fromHtml(
            caption, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT
        )
        baseBind.description.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun setupStats(illust: IllustsBean) {
        baseBind.postTime.text = String.format(
            "%s投递", Common.getLocalYYYYMMDDHHMMString(illust.create_date)
        )
        baseBind.totalView.text = illust.total_view.toString()
        baseBind.totalLike.text = illust.total_bookmarks.toString()
    }

    private fun setupDownloadButton(illust: IllustsBean) {
        baseBind.download.setChangeAlphaWhenPress(true)
        baseBind.related.setChangeAlphaWhenPress(true)
        baseBind.comment.setChangeAlphaWhenPress(true)
        baseBind.download.setOnClick { v: View? ->
            val resolution = Shaft.sSettings.defaultImageResolution.let {
                if (it.isNullOrEmpty()) Params.IMAGE_RESOLUTION_ORIGINAL else it
            }
            if (illust.page_count == 1) {
                IllustDownload.downloadIllustFirstPageWithResolution(illust, resolution, mContext as BaseActivity<*>)
            } else {
                IllustDownload.downloadIllustAllPagesWithResolution(illust, resolution, mContext as BaseActivity<*>)
            }
            checkDownload()
            if (Shaft.sSettings.isAutoPostLikeWhenDownload && !illust.isIs_bookmarked) {
                PixivOperate.postLikeDefaultStarType(illust)
            }
        }
        baseBind.download.setOnLongClickListener {
            val IMG_RESOLUTION_TITLE = arrayOf(
                getString(R.string.string_280),
                getString(R.string.string_281),
                getString(R.string.string_282),
                getString(R.string.string_283)
            )
            val IMG_RESOLUTION = arrayOf(
                Params.IMAGE_RESOLUTION_ORIGINAL,
                Params.IMAGE_RESOLUTION_LARGE,
                Params.IMAGE_RESOLUTION_MEDIUM,
                Params.IMAGE_RESOLUTION_SQUARE_MEDIUM
            )
            CheckableDialogBuilder(mContext)
                .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                .addItems(IMG_RESOLUTION_TITLE) { dialog, which ->
                    if (illust.page_count == 1) {
                        IllustDownload.downloadIllustFirstPageWithResolution(
                            illust, IMG_RESOLUTION[which], mContext as BaseActivity<*>
                        )
                    } else {
                        IllustDownload.downloadIllustAllPagesWithResolution(
                            illust, IMG_RESOLUTION[which], mContext as BaseActivity<*>
                        )
                    }
                    dialog.dismiss()
                }
                .create()
                .show()
            true
        }
    }

    private fun loadUserAvatar(illust: IllustsBean) {
        val url = illust.user?.profile_image_urls?.medium
        // Glide 的 into() 会先清空 target 再起新请求,即使命中内存缓存也会空一帧。池每发射一次就
        // 重发一次 → 收藏一下头像闪一下(#962)。url 没变、图还在,就什么都不用做。
        if (url == loadedAvatarUrl && baseBind.userHead.drawable != null) return
        loadedAvatarUrl = url
        Glide.with(mContext)
            .load(GlideUtil.getUrl(url))
            .error(R.drawable.no_profile)
            .into(baseBind.userHead)
    }

    override fun onResume() {
        super.onResume()
        checkDownload()
    }

    private fun checkDownload() {
        // SAF existence probe + Room query are heavy on main thread for multi-P
        // works (issue #835 — ANR on Android 16). VM runs both on Dispatchers.IO
        // and posts the result back to hasDownload LiveData.
        vm.refreshDownloadState(mContext)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        aiHelper = IllustAiHelper(this, baseBind.root)
        val intentFilter = IntentFilter()
        val illust = ObjectPool.get<IllustsBean>(safeArgs.illustId.toLong()).value ?: return
        mReceiver = CallBackReceiver { context, intent ->
            val bundle = intent.extras
            if (bundle != null) {
                val id = bundle.getInt(Params.ID)
                if (illust.id == id) {
                    val isLiked = bundle.getBoolean(Params.IS_LIKED)
                    if (isLiked) {
                        illust.isIs_bookmarked = true
                        baseBind.postLike.setImageResource(R.drawable.ic_favorite_red_24dp)
                        val afterStarCount = illust.total_bookmarks + 1
                        illust.total_bookmarks = afterStarCount
                        baseBind.totalLike.text = afterStarCount.toString()
                    } else {
                        illust.isIs_bookmarked = false
                        baseBind.postLike.setImageResource(R.drawable.ic_favorite_grey_24dp)
                        val afterStarCount = illust.total_bookmarks - 1
                        illust.total_bookmarks = afterStarCount
                        baseBind.totalLike.text = afterStarCount.toString()
                    }
                }
            }
        }
        intentFilter.addAction(Params.LIKED_ILLUST)
        mReceiver?.let {
            LocalBroadcastManager.getInstance(mContext).registerReceiver(it, intentFilter)
        }
        aiHelper?.restoreUpscaleIfRunning(safeArgs.illustId)
    }

    override fun onDestroy() {
        mReceiver?.let {
            LocalBroadcastManager.getInstance(mContext).unregisterReceiver(it)
        }
        super.onDestroy()
    }

    override fun onDestroyView() {
        try {
            baseBind.recyclerView.adapter = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        renderedImageSignature = null
        renderedTagSignature = null
        bottomSheetCallbackAttached = false
        sheetDeltaY = 0
        loadedAvatarUrl = null
        aiHelper = null
        super.onDestroyView()
    }

    override fun vertical() {
        baseBind.toolbar.setPadding(0, Shaft.statusHeight, 0, 0)
    }

    companion object {
        @JvmStatic
        fun newInstance(illustId: Int): FragmentIllust {
            return FragmentIllust().apply {
                arguments = Bundle().apply {
                    putInt("illust_id", illustId)
                }
            }
        }
    }
}
