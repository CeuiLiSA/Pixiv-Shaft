package ceui.pixiv.ui.settings

import android.net.Uri
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import ceui.lisa.R
import ceui.lisa.activities.BaseActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.ActivityViewerDismissTestBinding
import ceui.lisa.helper.ImageViewerTransition
import ceui.lisa.helper.isAtMinScale
import ceui.lisa.view.DragDismissLayout
import com.github.panpf.sketch.loadImage

/**
 * 「大图拖动退出控制」测试页点图后进入的精简大图页。
 *
 * 只保留「竖向拖拽退出」这条主链路：内容从 ViewPager 换成一整张测试图，没有下载 / 收藏胶囊、
 * 页码与 AI 入口。但**手势与转场完全共用真实二级大图页的那一套**——
 * [DragDismissLayout] 负责跟手位移/缩放与松手判定，[ImageViewerTransition] 负责进场展开、
 * 回弹与收场缩回，边界判定也照抄 `FragmentImageDetail.canSwipeToDismiss`（图片到顶/底才交给
 * 父布局）。所以这里调出来的手感就是线上二级大图页的手感。
 *
 * 三个阈值与真实大图页同源，都从 [Shaft.sSettings] 读：测试页的调节条即时写内存态，
 * 点图进来即可试；只有点「保存」才落盘。
 */
class ViewerDismissTestActivity : BaseActivity<ActivityViewerDismissTestBinding?>() {

    /** 小红书式全屏弹窗转场（进场展开 / 竖向拖拽跟手 / 收场缩回），与真实大图页同一实现。 */
    private var viewerTransition: ImageViewerTransition? = null
    private var restoredFromSavedState = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // 重建恢复不播进场动画，直接铺满黑底（与 ImageDetailActivity 同一处理）。
        restoredFromSavedState = savedInstanceState != null
        super.onCreate(savedInstanceState)
    }

    override fun setTheme(resid: Int) {
        super.setTheme(resid)
        // BaseActivity.updateTheme 会按用户主题色 setTheme(AppTheme_IndexN)，把 manifest 里
        // ImageViewerTheme 的透明 windowBackground 盖回不透明；每次 setTheme 后都叠回窗口透明属性，
        // 保证 PhoneWindow 生成 DecorView 时读到透明背景。与 ImageDetailActivity 完全一致。
        theme.applyStyle(R.style.ImageViewerWindowOverlay, true)
    }

    override fun hideStatusBar(): Boolean = true

    override fun initLayout(): Int = R.layout.activity_viewer_dismiss_test

    override fun initView() {
        val root = baseBind!!.root as DragDismissLayout
        val image = baseBind!!.image

        // 灵敏度与真实二级大图页同源：都读 Settings（测试页调完即时写内存态）。
        val settings = Shaft.sSettings
        root.dismissDistanceFraction = settings.viewerDismissDistance
        root.flingDismissVelocityDp = settings.viewerDismissVelocity
        root.maxDragScaleShrink = settings.viewerDismissScaleShrink

        val transition =
            ImageViewerTransition(
                root,
                image,
                emptyList(),
                intent.getIntArrayExtra(EXTRA_ENTER_BOUNDS),
            )
        viewerTransition = transition
        root.dragTargetView = image
        root.callback =
            object : DragDismissLayout.Callback {
                override fun canStartDismissDrag(direction: DragDismissLayout.Direction): Boolean {
                    // 与 FragmentImageDetail.canSwipeToDismiss 同一判定：图片已到顶（上拉）/
                    // 已到底（下拉）才让父布局接管，否则手势留给图片自己平移。
                    if (Shaft.sSettings.isViewerDismissOnlyAtMinScale && !image.isAtMinScale()) return false
                    val scrollDirection =
                        when (direction) {
                            DragDismissLayout.Direction.UP -> 1
                            DragDismissLayout.Direction.DOWN -> -1
                        }
                    return !image.canScrollVertically(scrollDirection)
                }

                override fun onDismissDragUpdate(fraction: Float) = transition.onDragProgress(fraction)

                override fun onDismissDragRelease(
                    shouldDismiss: Boolean,
                    direction: DragDismissLayout.Direction,
                    velocityY: Float,
                ) {
                    if (shouldDismiss) {
                        transition.playExit(true, direction) { finish() }
                    } else {
                        transition.springBack()
                    }
                }
            }

        // 返回键 / 返回手势与下拉收掉共用同一段收场动画。
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = dismissViewer()
            },
        )

        val assetPath = intent.getStringExtra(EXTRA_ASSET_PATH)
        if (assetPath.isNullOrEmpty()) {
            finish()
            return
        }
        image.loadImage(Uri.parse("file:///android_asset/$assetPath"))

        if (restoredFromSavedState) {
            transition.showImmediately()
        } else {
            transition.playEnter()
        }
    }

    override fun initData() {
    }

    private fun dismissViewer() {
        val transition =
            viewerTransition
                ?: run {
                    finish()
                    return
                }
        transition.playExit(true, DragDismissLayout.Direction.DOWN) { finish() }
    }

    companion object {
        /** 进场缩略图矩形（屏幕坐标 [left, top, right, bottom]），与真实大图页同名同义。 */
        const val EXTRA_ENTER_BOUNDS = "enter_bounds"

        /** assets 下的相对路径，如 `prime_square/xxx.webp`。 */
        const val EXTRA_ASSET_PATH = "asset_path"
    }
}
