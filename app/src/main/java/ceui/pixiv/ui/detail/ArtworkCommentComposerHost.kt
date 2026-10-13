package ceui.pixiv.ui.detail

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.FragmentArtworkV3Binding
import ceui.pixiv.panel.PanelState
import ceui.pixiv.ui.comments.CommentComposerController
import ceui.pixiv.ui.comments.CommentComposerPresentation
import ceui.pixiv.ui.comments.CommentComposerView
import ceui.pixiv.ui.comments.CommentsComposerViewModel
import ceui.pixiv.ui.comments.SentComment
import ceui.pixiv.witstudio.theme.V3Palette

/**
 * 详情页底部的内联评论输入栏:按需 inflate、唤起键盘、随面板状态收起,浮着时压住悬浮胶囊。
 *
 * 一个视图生命周期一个实例(随 [ArtworkV3Fragment] 的 onViewCreated 建、onDestroyView 丢)。
 *
 * @param composer 首次唤出时才取:composer VM 与视图树一样,首帧用不上就不建。
 * @param hideFabBar / [showFabBar] 悬浮胶囊归宿主管;宿主放回胶囊前会先看 [active]。
 */
internal class ArtworkCommentComposerHost(
    private val fragment: Fragment,
    private val chrome: FragmentArtworkV3Binding,
    private val listView: RecyclerView,
    private val palette: V3Palette,
    private val composer: () -> CommentsComposerViewModel,
    private val onSent: (SentComment) -> Unit,
    private val hideFabBar: (immediate: Boolean) -> Unit,
    private val showFabBar: () -> Unit,
) {
    private var controller: CommentComposerController? = null

    /** 输入栏正浮着:悬浮胶囊不放回来。 */
    var active = false
        private set

    /** 评论区「留下你的评论吧」入口(由 commentsRenderer 经宿主调)。 */
    fun show() {
        if (controller == null) {
            ensureController()
            // 刚挂上的 BottomPanelCoordinator 在 attach 末尾 requestApplyInsets，那一轮分发要到下一帧
            // 才到。此刻就 showKeyboard 切到 KEYBOARD，那轮分发会看到「IME 尚未可见 + state=KEYBOARD」
            // 判成 NONE → onPanelStateChanged 把空输入栏收成 GONE：键盘照弹，输入栏却没了。
            // 等首轮 insets 落地（同一次 traversal 的 preDraw）再唤起，与 onViewCreated 就挂载时的时序一致。
            chrome.composerRoot.doOnPreDraw { if (isViewAlive()) show() }
            return
        }
        chrome.composerRoot.setBackgroundColor(fragment.requireContext().getColor(R.color.v3_bg))
        if (active) {
            controller?.showKeyboard()
            return
        }
        active = true
        hideFabBar(true)
        controller?.showKeyboard()
    }

    /** 发送成功后收起输入栏。 */
    fun dismiss() {
        controller?.dismiss()
    }

    /**
     * 首次唤出输入栏时才 inflate + 装配。
     *
     * 浮层本体(含一个 ViewPager2)在详情页首帧是 GONE、用户也不会在首帧输入评论,却要为它付一次
     * 完整 inflate —— 详情页首帧还要连带相邻页(offscreenPageLimit=1)各付一份,实测这棵视图树
     * 占掉近百毫秒。推迟到这里,成本落到用户明确等待输入框的那一刻,且不在页面转场关键路径上。
     */
    private fun ensureController() {
        if (controller != null) return
        // ViewStub 只声明了自身 id(未设 inflatedId),所以 inflate 出来的 View 直接取返回值,
        // 不经过 binding 字段。装配后 stub 即从父容器移除,重复调用由上面的守卫挡住。
        val view = chrome.commentComposerStub.inflate() as CommentComposerView
        controller =
            CommentComposerController.attach(
                fragment = fragment,
                view = view,
                panelRoot = chrome.composerRoot,
                panelContentView = listView,
                palette = palette,
                presentation = CommentComposerPresentation.ON_DEMAND_OVERLAY,
                composer = composer(),
                onSent = onSent,
                onPanelStateChanged = ::onPanelStateChanged,
                onPanelDismissStarted = { closingState ->
                    if (closingState == PanelState.KEYBOARD) {
                        chrome.composerRoot.background = null
                    }
                },
                onPanelDismissCancelled = ::onPanelStateChanged,
            )
    }

    /** 本实例绑的那棵视图仍是 Fragment 的当前视图(没被销毁、也没被重建替换)。 */
    private fun isViewAlive(): Boolean = fragment.view === chrome.root

    private fun hideBar() {
        active = false
        controller?.hide()
        chrome.composerRoot.background = null
        showFabBar()
    }

    private fun onPanelStateChanged(state: PanelState) {
        // 面板/IME 回调若在视图销毁后到达,别碰视图(与 renderDownloadFab 对齐)
        if (!isViewAlive()) return
        if (state == PanelState.NONE) {
            val imeUp =
                ViewCompat.getRootWindowInsets(chrome.composerRoot)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (!imeUp && controller?.isEmpty == true) {
                hideBar()
            } else if (!imeUp) {
                chrome.composerRoot.background = null
            }
        } else {
            if (state == PanelState.KEYBOARD) {
                chrome.composerRoot.setBackgroundColor(fragment.requireContext().getColor(R.color.v3_bg))
            } else {
                chrome.composerRoot.background = null
            }
            hideFabBar(false)
        }
    }
}
