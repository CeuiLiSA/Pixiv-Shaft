package ceui.pixiv.ui.detail

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentArtworkV3Binding
import ceui.lisa.utils.GlideUtil
import ceui.pixiv.api.model.Illust
import ceui.pixiv.ui.common.IllustMuteStore
import ceui.pixiv.utils.combineLatest
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widget.SpoilerBlurView
import ceui.pixiv.widgets.ProgressTextButton
import com.bumptech.glide.RequestManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 详情页的整页屏蔽遮罩(对齐经典 [ceui.lisa.fragments.FragmentIllust] 的 observeMuteStatus):作品或画师
 * 命中屏蔽记录时全屏盖住整页,给出取消屏蔽 / 离开入口。V3 原先只有菜单里的写库动作、没有任何消费方,
 * 「屏蔽这个作品」点完页面纹丝不动(#983)。
 *
 * 两路判定都直接来自 Room 的行,没有「本进程内临时可见」这种中间态:瀑布流里点一下打码卡就是取消屏蔽,
 * 那一行当场就删了,再点进详情自然不会被挡。所以这里只观察库,不必再掺 [IllustMuteStore] 的版本号。
 *
 * 一个视图生命周期一个实例(随 [ArtworkV3Fragment] 的 onViewCreated 建、onDestroyView 丢),
 * 「只接一次观察」「遮罩子树建没建过」这两个标志因此天然随视图归零。
 *
 * @param onMutedChanged 每次判定都回调(含「没被屏蔽」),由宿主同步悬浮胶囊;宿主自己负责幂等。
 */
internal class ArtworkMuteMask(
    private val fragment: Fragment,
    private val chrome: FragmentArtworkV3Binding,
    private val glide: RequestManager,
    private val onMutedChanged: (Boolean) -> Unit,
) {
    private var observed = false

    /**
     * 遮罩子树首帧不建:只有命中屏蔽记录的作品才亮,却要每个作品、每一页都跟着建 8 个 View
     * (ViewPager 的 offscreenPageLimit=1 意味着一次点击至少建两页)。推迟到第一次报 muted。
     */
    private var frameReady = false

    private val frameView: View
        get() = ViewCompat.requireViewById(chrome.root, R.id.abandoned_frame)
    private val spoilerView: SpoilerBlurView
        get() = ViewCompat.requireViewById(chrome.root, R.id.abandoned_spoiler)
    private val cancelMuteIllustView: ProgressTextButton
        get() = ViewCompat.requireViewById(chrome.root, R.id.cancel_mute_illust)
    private val cancelMuteUserView: ProgressTextButton
        get() = ViewCompat.requireViewById(chrome.root, R.id.cancel_mute_user)
    private val leaveView: ProgressTextButton
        get() = ViewCompat.requireViewById(chrome.root, R.id.leave)

    fun attach(illust: Illust) {
        if (observed) return
        observed = true
        // 经典同款 userId ?: 0 兜底:user 缺失/解析失败(#592 web 兜底 bean 可能 id=0)时,
        // 作品屏蔽的观察也要照常接线,画师侧退化成恒 null。只接一次——按 userId 重接会留下
        // 两个 mediator 同时观察,二者发射顺序不定,旧 mediator 的过期值可能盖掉新值。
        val userId = illust.user?.id ?: 0
        val owner = fragment.viewLifecycleOwner
        val dao = AppDatabase.getAppDatabase(fragment.requireContext()).searchDao()
        combineLatest(
                dao.getIllustMuteEntityByID(illust.id),
                dao.getUserMuteEntityByIDLiveData(userId),
            )
            .observe(owner) { (illustEntity, userEntity) ->
                val muted = illustEntity != null || userEntity != null
                // 只管胶囊/胶囊同步，不碰遮罩子树，所以可以无条件先做。
                onMutedChanged(muted)
                // 常态是「没被屏蔽」——那时遮罩子树从没被建过，直接返回，连 findViewById 都不必做。
                if (!muted && !frameReady) return@observe
                if (muted) ensureFrame()
                frameView.isVisible = muted
                // 整页遮罩不再是一块纯黑：糊掉的作品图 + spoiler 粒子（与瀑布流「屏蔽此作品」同款）。
                // 只在真要显示遮罩时贴图——本 observer 在**没被屏蔽**时也照常发射（那才是常态），
                // 无脑 bind 等于每开一个作品都白解码 + 白模糊一张图。bind 自身按 cacheKey 幂等，
                // 屏蔽期间的重复发射不会重发请求；粒子由 SpoilerParticleView 按可见性自行起停。
                if (muted) {
                    spoilerView.bind(glide, GlideUtil.getMediumImg(illust))
                }
                cancelMuteIllustView.isVisible = illustEntity != null
                cancelMuteUserView.isVisible = userEntity != null
                if (illustEntity != null) {
                    cancelMuteIllustView.setOnClick {
                        owner.lifecycleScope.launch {
                            it.showProgress()
                            delay(600L)
                            // 删库和内存名单一并交给 store（它无条件删这一行）：瀑布流卡片的遮罩
                            // 判定读的是内存名单，而自己 deleteMuteEntity 还会绕开 store 的单线程
                            // 写队列，和排队中的 insert 抢顺序把这行复活（见 MutedWorkStore 类注释）。
                            // 本页的整页遮罩由上面那条 LiveData 在行真正删掉后自行收起。
                            IllustMuteStore.setMuted(illustEntity.id.toLong(), false)
                            it.hideProgress()
                        }
                    }
                }
                if (userEntity != null) {
                    cancelMuteUserView.setOnClick {
                        owner.lifecycleScope.launch {
                            it.showProgress()
                            delay(600L)
                            dao.deleteMuteEntity(userEntity)
                            it.hideProgress()
                        }
                    }
                }
                leaveView.setOnClick {
                    owner.lifecycleScope.launch {
                        it.showProgress()
                        delay(600L)
                        fragment.requireActivity().finish()
                        it.hideProgress()
                    }
                }
            }
    }

    private fun ensureFrame() {
        if (frameReady) return
        frameReady = true
        chrome.abandonedFrameStub.inflate()
    }
}
