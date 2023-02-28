package ceui.lisa.fragments

import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.*
import ceui.lisa.adapters.IllustAdapter
import ceui.lisa.databinding.FragmentIllustBinding
import ceui.lisa.dialogs.MuteDialog
import ceui.lisa.download.IllustDownload
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.ObjectSpec
import ceui.lisa.models.TagsBean
import ceui.lisa.notification.CallBackReceiver
import ceui.lisa.utils.*
import ceui.lisa.viewmodel.AppLevelViewModel.FollowUserStatus
import ceui.loxia.FlagDescFragment
import ceui.loxia.ObjectPool
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog.CheckableDialogBuilder
import com.qmuiteam.qmui.widget.dialog.QMUIDialog.MessageDialogBuilder
import com.scwang.smart.refresh.layout.SmartRefreshLayout
import com.zhy.view.flowlayout.FlowLayout
import com.zhy.view.flowlayout.TagAdapter

class FragmentIllust : SwipeFragment<FragmentIllustBinding?>() {

    private lateinit var illust: IllustsBean

    public override fun initBundle(bundle: Bundle) {
        illust = bundle.getSerializable(Params.CONTENT) as IllustsBean
    }

    public override fun initLayout() {
        mLayoutID = R.layout.fragment_illust
    }

    override fun initView() {
        if (illust.id == 0 || !illust.isVisible) {
            Common.showToast(R.string.string_206)
            Handler().postDelayed({ finish() }, 1000)
            return
        }
        ObjectPool.get<IllustsBean>(illust.id.toLong()).observe(viewLifecycleOwner) { illust ->
            Common.showLog("ObjectPoolTest " + illust.title)
        }
        if (illust.series != null && !TextUtils.isEmpty(illust.series.title)) {
            val clickableSpan: ClickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    val intent = Intent(mContext, TemplateActivity::class.java)
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "漫画系列详情")
                    intent.putExtra(Params.MANGA_SERIES_ID, illust.series.id)
                    startActivity(intent)
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.color = Common.resolveThemeAttribute(mContext, R.attr.colorPrimary)
                }
            }
            val spannableString: SpannableString
            val seriesString = getString(R.string.string_229)
            spannableString = SpannableString(
                String.format(
                    "@%s %s",
                    seriesString, illust.title
                )
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
        baseBind.toolbar.inflateMenu(R.menu.share)
        baseBind.toolbar.setNavigationOnClickListener { v: View? -> mActivity.finish() }
        baseBind.toolbar.setOnMenuItemClickListener(Toolbar.OnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.action_share) {
                object : ShareIllust(mContext, illust) {
                    override fun onPrepare() {}
                }.execute()
                return@OnMenuItemClickListener true
            } else if (menuItem.itemId == R.id.action_share_image) {
                Glide.with(mContext)
                    .asBitmap()
                    .load(
                        GlideUrlChild(
                            IllustDownload.getUrl(
                                illust,
                                0,
                                Params.IMAGE_RESOLUTION_LARGE
                            )
                        )
                    )
                    .listener(object : RequestListener<Bitmap?> {
                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any,
                            target: Target<Bitmap?>,
                            isFirstResource: Boolean
                        ): Boolean {
                            return false
                        }

                        override fun onResourceReady(
                            resource: Bitmap?,
                            model: Any,
                            target: Target<Bitmap?>,
                            dataSource: DataSource,
                            isFirstResource: Boolean
                        ): Boolean {
                            val uri = Common.copyBitmapToImageCacheFolder(
                                resource,
                                illust.id.toString() + ".png"
                            )
                            if (uri != null) {
                                val shareIntent = Intent()
                                shareIntent.action = Intent.ACTION_SEND
                                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                shareIntent.setDataAndType(
                                    uri,
                                    mContext.contentResolver.getType(uri)
                                )
                                shareIntent.putExtra(Intent.EXTRA_STREAM, uri)
                                startActivity(
                                    Intent.createChooser(
                                        shareIntent,
                                        getString(R.string.share)
                                    )
                                )
                            }
                            return true
                        }
                    }).submit()
            } else if (menuItem.itemId == R.id.action_dislike) {
                val muteDialog = MuteDialog.newInstance(illust)
                muteDialog.show(childFragmentManager, "MuteDialog")
                return@OnMenuItemClickListener true
            } else if (menuItem.itemId == R.id.action_copy_link) {
                val url = ShareIllust.URL_Head + illust.id
                Common.copy(mContext, url)
                return@OnMenuItemClickListener true
            } else if (menuItem.itemId == R.id.action_show_original) {
                baseBind.recyclerView.adapter = IllustAdapter(
                    mActivity, this@FragmentIllust, illust,
                    recyHeight, true
                )
                return@OnMenuItemClickListener true
            } else if (menuItem.itemId == R.id.action_mute_illust) {
                PixivOperate.muteIllust(illust)
                return@OnMenuItemClickListener true
            } else if (menuItem.itemId == R.id.action_flag_illust) {
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "举报插画")
                intent.putExtra(FlagDescFragment.FlagObjectIdKey, illust.id)
                intent.putExtra(FlagDescFragment.FlagObjectTypeKey, ObjectSpec.POST)
                startActivity(intent)
                return@OnMenuItemClickListener true
            }
            false
        })
        if (illust.isIs_bookmarked) {
            baseBind.postLike.setImageResource(R.drawable.ic_favorite_red_24dp)
        } else {
            baseBind.postLike.setImageResource(R.drawable.ic_favorite_grey_24dp)
        }
        baseBind.postLike.setOnClickListener {
            if (illust.isIs_bookmarked) {
                baseBind.postLike.setImageResource(R.drawable.ic_favorite_grey_24dp)
            } else {
                baseBind.postLike.setImageResource(R.drawable.ic_favorite_red_24dp)
            }
            PixivOperate.postLikeDefaultStarType(illust)
        }
        baseBind.postLike.setOnLongClickListener(object : OnLongClickListener {
            override fun onLongClick(v: View): Boolean {
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(Params.ILLUST_ID, illust.id)
                intent.putExtra(Params.DATA_TYPE, Params.TYPE_ILLUST)
                intent.putExtra(Params.TAG_NAMES, illust.tagNames)
                intent.putExtra(Params.LAST_CLASS, javaClass.simpleName)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "按标签收藏")
                startActivity(intent)
                return true
            }
        })
        baseBind.illustTag.adapter = object : TagAdapter<TagsBean>(
            illust.tags
        ) {
            override fun getView(parent: FlowLayout, position: Int, s: TagsBean): View {
                val tv = LayoutInflater.from(mContext).inflate(
                    R.layout.recy_single_line_text_new,
                    parent, false
                ) as TextView
                var tag = s.name
                if (!TextUtils.isEmpty(s.translated_name)) {
                    tag = tag + "/" + s.translated_name
                }
                tv.text = tag
                return tv
            }
        }
        baseBind.illustTag.setOnTagClickListener { view, position, parent ->
            val intent = Intent(mContext, SearchActivity::class.java)
            intent.putExtra(Params.KEY_WORD, illust.tags[position].name)
            intent.putExtra(Params.INDEX, 0)
            startActivity(intent)
            true
        }
        baseBind.illustTag.setOnTagLongClickListener { view, position, parent -> // 弹出菜单：固定+复制
            val tagName = illust.tags[position].name
            val searchEntity =
                PixivOperate.getSearchHistory(tagName, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD)
            val isPinned = searchEntity != null && searchEntity.isPinned
            MessageDialogBuilder(mContext)
                .setTitle(tagName)
                .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                .addAction(if (isPinned) getString(R.string.string_443) else getString(R.string.string_442)) { dialog, index ->
                    PixivOperate.insertPinnedSearchHistory(
                        tagName,
                        SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD,
                        !isPinned
                    )
                    Common.showToast(R.string.operate_success)
                    dialog.dismiss()
                }
                .addAction(getString(R.string.string_120)) { dialog, index ->
                    Common.copy(mContext, tagName)
                    dialog.dismiss()
                }
                .create()
                .show()
            true
        }
        baseBind.illustSize.text = getString(R.string.string_193, illust.width, illust.height)
        baseBind.illustId.text = getString(R.string.string_194, illust.id)
        baseBind.userId.text = getString(R.string.string_195, illust.user.id)
        val sheetBehavior: BottomSheetBehavior<*> = BottomSheetBehavior.from(
            baseBind.coreLinear
        )
        baseBind.coreLinear.viewTreeObserver.addOnGlobalLayoutListener(object :
            OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val realHeight = baseBind.bottomBar.height +
                        baseBind.viewDivider.height +
                        baseBind.secondLinear.height
                val maxHeight = resources.displayMetrics.heightPixels * 3 / 4
                val params = baseBind.coreLinear.layoutParams
                val slideMaxHeight = Math.min(realHeight, maxHeight)
                params.height = slideMaxHeight
                baseBind.coreLinear.layoutParams = params
                val bottomCardHeight = baseBind.bottomBar.height
                val deltaY = slideMaxHeight - baseBind.bottomBar.height
                sheetBehavior.setPeekHeight(bottomCardHeight, true)

                //设置占位view大小
                val headParams = baseBind.helperView.layoutParams
                headParams.height = bottomCardHeight - DensityUtil.dp2px(16.0f)
                baseBind.helperView.layoutParams = headParams
                sheetBehavior.addBottomSheetCallback(object : BottomSheetCallback() {
                    override fun onStateChanged(bottomSheet: View, newState: Int) {}
                    override fun onSlide(bottomSheet: View, slideOffset: Float) {
                        baseBind.refreshLayout.translationY = -deltaY * slideOffset * 0.7f
                    }
                })
                baseBind.recyclerView.layoutManager = LinearLayoutManager(mContext)
                recyHeight = baseBind.recyclerView.height
                val adapter =
                    IllustAdapter(mActivity, this@FragmentIllust, illust, recyHeight, false)
                baseBind.recyclerView.adapter = adapter
                baseBind.coreLinear.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })
        baseBind.related.setOnClickListener {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关作品")
            intent.putExtra(Params.ILLUST_ID, illust.id)
            intent.putExtra(Params.ILLUST_TITLE, illust.title)
            startActivity(intent)
        }
        baseBind.comment.setOnClickListener {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关评论")
            intent.putExtra(Params.ILLUST_ID, illust.id)
            intent.putExtra(Params.ILLUST_TITLE, illust.title)
            startActivity(intent)
        }
        baseBind.illustLike.setOnClickListener {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(Params.CONTENT, illust)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "喜欢这个作品的用户")
            startActivity(intent)
        }
        if (!TextUtils.isEmpty(illust.caption)) {
            baseBind.description.visibility = View.VISIBLE
            baseBind.description.setHtml(illust.caption)
        } else {
            baseBind.description.visibility = View.GONE
        }
        val toUserActivityListener = View.OnClickListener {
            val intent = Intent(mContext, UserActivity::class.java)
            intent.putExtra(Params.USER_ID, illust.user.id)
            startActivity(intent)
        }
        baseBind.relaIllustBrief.setOnClickListener(toUserActivityListener)
        baseBind.userName.setOnClickListener(toUserActivityListener)
        baseBind.userName.setOnLongClickListener {
            Common.copy(mContext, illust.user.name)
            true
        }
        baseBind.follow.setOnClickListener {
            val integerValue = Shaft.appViewModel.getFollowUserLiveData(illust.user.id).value ?: FollowUserStatus.UNKNOWN
            if (FollowUserStatus.isFollowed(integerValue)) {
                PixivOperate.postUnFollowUser(illust.user.id)
                illust.user.isIs_followed = false
            } else {
                PixivOperate.postFollowUser(illust.user.id, Params.TYPE_PUBLIC)
                illust.user.isIs_followed = true
            }
        }
        baseBind.follow.setOnLongClickListener { v1: View? ->
            val integerValue = Shaft.appViewModel.getFollowUserLiveData(
                illust.user.id
            ).value ?: FollowUserStatus.UNKNOWN
            if (!FollowUserStatus.isFollowed(integerValue)) {
                illust.user.isIs_followed = true
            }
            PixivOperate.postFollowUser(illust.user.id, Params.TYPE_PRIVATE)
            true
        }
        baseBind.userName.text = illust.user.name
        baseBind.postTime.text = String.format(
            "%s投递", Common.getLocalYYYYMMDDHHMMString(
                illust.create_date
            )
        )
        baseBind.totalView.text = illust.total_view.toString()
        baseBind.totalLike.text = illust.total_bookmarks.toString()
        baseBind.download.setChangeAlphaWhenPress(true)
        baseBind.related.setChangeAlphaWhenPress(true)
        baseBind.comment.setChangeAlphaWhenPress(true)
        baseBind.download.setOnClickListener { v: View? ->
            if (illust.page_count == 1) {
                IllustDownload.downloadIllustFirstPage(illust, mContext as BaseActivity<*>)
            } else {
                IllustDownload.downloadIllustAllPages(illust, mContext as BaseActivity<*>)
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
                            illust,
                            IMG_RESOLUTION[which],
                            mContext as BaseActivity<*>
                        )
                    } else {
                        IllustDownload.downloadIllustAllPagesWithResolution(
                            illust,
                            IMG_RESOLUTION[which],
                            mContext as BaseActivity<*>
                        )
                    }
                    dialog.dismiss()
                }
                .create()
                .show()
            true
        }
        baseBind.illustId.setOnClickListener { Common.copy(mContext, illust.id.toString()) }
        baseBind.userId.setOnClickListener { Common.copy(mContext, illust.user.id.toString()) }
        Glide.with(mContext)
            .load(GlideUtil.getUrl(illust.user.profile_image_urls.medium))
            .error(R.drawable.no_profile)
            .into(baseBind.userHead)
        Shaft.appViewModel.getFollowUserLiveData(illust.user.id)
            .observe(this) { integer -> updateFollowUserUI(integer) }
    }

    private var mReceiver: CallBackReceiver? = null
    override fun onResume() {
        super.onResume()
        checkDownload()
    }

    private var recyHeight = 0
    private fun checkDownload() {
        if (Common.isIllustDownloaded(illust)) {
            baseBind.download.setText(R.string.string_337)
        } else {
            baseBind.download.setText(R.string.string_72)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val intentFilter = IntentFilter()
        mReceiver = CallBackReceiver { context, intent ->
            val bundle = intent.extras
            if (bundle != null) {
                val id = bundle.getInt(Params.ID)
                if (illust.id == id) {
                    val isLiked = bundle.getBoolean(Params.IS_LIKED)
                    if (isLiked) {
                        illust.isIs_bookmarked = true
                        baseBind.postLike.setImageResource(R.drawable.ic_favorite_red_24dp)
                        val beforeStarCount = illust.total_bookmarks
                        val afterStarCount = beforeStarCount + 1
                        illust.total_bookmarks = afterStarCount
                        baseBind.totalLike.text = afterStarCount.toString()
                    } else {
                        illust.isIs_bookmarked = false
                        baseBind.postLike.setImageResource(R.drawable.ic_favorite_grey_24dp)
                        val beforeStarCount = illust.total_bookmarks
                        val afterStarCount = beforeStarCount - 1
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
        super.onDestroyView()
    }

    override fun vertical() {
        //竖屏
        baseBind.toolbar.setPadding(0, Shaft.statusHeight, 0, 0)
    }

    override fun getSmartRefreshLayout(): SmartRefreshLayout {
        return baseBind.refreshLayout
    }

    private fun updateFollowUserUI(status: Int) {
        if (FollowUserStatus.isFollowed(status)) {
            baseBind.follow.setText(R.string.string_177)
        } else {
            baseBind.follow.setText(R.string.string_178)
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(illustsBean: IllustsBean?): FragmentIllust {
            val args = Bundle()
            args.putSerializable(Params.CONTENT, illustsBean)
            val fragment = FragmentIllust()
            fragment.arguments = args
            return fragment
        }
    }
}