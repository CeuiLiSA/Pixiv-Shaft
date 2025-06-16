package ceui.pixiv.ui.common

import android.animation.Animator
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import androidx.navigation.findNavController
import ceui.lisa.R
import ceui.lisa.databinding.ActivityHomeBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.IllustResponse
import ceui.loxia.ObjectPool
import ceui.loxia.RefreshHint
import ceui.loxia.observeEvent
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.repo.RemoteRepository
import ceui.pixiv.utils.ppppx
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.bumptech.glide.request.RequestOptions.bitmapTransform
import com.google.gson.Gson
import jp.wasabeef.glide.transformations.BlurTransformation
import timber.log.Timber

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    private val bgViewModel by pixivValueViewModel {
        RemoteRepository {
            val rest = if (SessionManager.loggedInUid > 0L) {
                Client.appApi.getUserBookmarkedIllusts(
                    SessionManager.loggedInUid, Params.TYPE_PUBLIC
                )
            } else {
                val jsonString =
                    assets.open("walkthrough.json").bufferedReader().use { it.readText() }
                Gson().fromJson(jsonString, IllustResponse::class.java)
            }

            val list = rest.illusts
            rest.copy(illusts = list.shuffled())
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val graph = navController.navInflater.inflate(R.navigation.mobile_navigation)
        val startDestination = if (SessionManager.isLoggedIn) {
            R.id.navigation_home_viewpager
        } else {
            R.id.navigation_landing
        }
        graph.setStartDestination(startDestination)
        navController.graph = graph

        SessionManager.newTokenEvent.observeEvent(this) {
            triggerOnce()
        }

        SessionManager.loggedInAccount.observe(this) {
            Timber.d("loggedInAccount ${it}")
            bgViewModel.refresh(RefreshHint.PullToRefresh)
        }

        bgViewModel.result.observe(this) { loadResult ->
            val resp = loadResult?.data ?: return@observe
            resp.displayList.getOrNull(0)?.let { illust ->
                ObjectPool.update(illust)
                binding.dimmer.isVisible = true
                Glide.with(this)
                    .load(GlideUrlChild(illust.image_urls?.large))
                    .apply(bitmapTransform(BlurTransformation(15, 3)))
                    .transition(withCrossFade())
                    .into(binding.pageBackground)
            }
        }
    }

    private fun triggerOnce() {
        val lottieView = binding.renewTokenProgress
        lottieView.visibility = View.VISIBLE

        lottieView.progress = 0f

        // 添加动画监听器
        lottieView.addAnimatorListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {
                // 动画开始时执行的操作（可选）
            }

            override fun onAnimationEnd(animation: Animator) {
                // 动画结束时隐藏 LottieAnimationView
                lottieView.visibility = View.GONE
            }

            override fun onAnimationCancel(animation: Animator) {
                // 动画取消时执行的操作（可选）
            }

            override fun onAnimationRepeat(animation: Animator) {
                // 动画重复时执行的操作（可选）
            }
        })

        // 播放动画
        lottieView.playAnimation()
    }

    private fun triggerTouchOnce(x1: Int, y1: Int) {
        val lottieView = binding.clickEvent
        Timber.d("TouchEvent 点击位置: x=$x1, y=$y1")
        val halfWidth = 80.ppppx
        lottieView.x = x1.toFloat() - halfWidth
        lottieView.y = y1.toFloat() - halfWidth
        lottieView.visibility = View.VISIBLE

        lottieView.progress = 0f

        // 添加动画监听器
        lottieView.addAnimatorListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {
                // 动画开始时执行的操作（可选）
            }

            override fun onAnimationEnd(animation: Animator) {
                // 动画结束时隐藏 LottieAnimationView
                lottieView.visibility = View.GONE
            }

            override fun onAnimationCancel(animation: Animator) {
                // 动画取消时执行的操作（可选）
            }

            override fun onAnimationRepeat(animation: Animator) {
                // 动画重复时执行的操作（可选）
            }
        })

        // 播放动画
        lottieView.playAnimation()
    }

    private var startX = 0f
    private var startY = 0f
    private val TOUCH_SLOP = 10 // 设置滑动阈值，单位是像素

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 记录按下的位置
                startX = event.x
                startY = event.y
            }

            MotionEvent.ACTION_UP -> {
                val endX = event.x
                val endY = event.y

                // 计算按下和抬起位置的距离
                val distanceX = Math.abs(endX - startX)
                val distanceY = Math.abs(endY - startY)

                // 如果滑动距离小于阈值，判定为点击
                if (distanceX <= TOUCH_SLOP && distanceY <= TOUCH_SLOP) {
                    triggerTouchOnce(endX.toInt(), endY.toInt())
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }
}