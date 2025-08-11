package ceui.pixiv.ui.common

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.navigation.findNavController
import ceui.lisa.R
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.ActivityHomeBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.observeEvent
import ceui.loxia.requireAppBackground
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.background.BackgroundType
import ceui.pixiv.ui.common.repo.RemoteRepository
import ceui.pixiv.ui.web.LinkHandler
import ceui.pixiv.utils.ppppx
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import timber.log.Timber

class HomeActivity : AppCompatActivity(), GrayToggler {

    private lateinit var binding: ActivityHomeBinding

    private val bgViewModel by pixivValueViewModel {
        RemoteRepository {
            val rest = Client.appApi.getUserBookmarkedIllusts(
                SessionManager.loggedInUid, Params.TYPE_PUBLIC
            )

            val list = rest.illusts
            rest.copy(illusts = list.shuffled())
        }
    }
    private val homeViewModel: HomeViewModel by viewModels {
        HomeViewModelFactory(assets)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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

        navController.addOnDestinationChangedListener { controller, destination, arguments ->
            val destId = destination.id
            if (destination.id == R.id.navigation_img_url || destination.id == R.id.navigation_paged_img_urls) {
                binding.pageBackground.isVisible = false
                binding.dimmer.isVisible = false
            } else {
                binding.pageBackground.isVisible = true
                binding.dimmer.isVisible = true
            }

            if (!SessionManager.isLoggedIn) {
                homeViewModel.onDestinationChanged(destId)
            }
        }
        if (!SessionManager.isLoggedIn) {
            homeViewModel.currentScale.observe(this) {
                animateBackground(it)
            }
        }
        SessionManager.newTokenEvent.observeEvent(this) {
            triggerOnce()
        }

        MainScope().launch(Dispatchers.IO) {
            Gson()
            val list = AppDatabase.getAppDatabase(this@HomeActivity).generalDao().getAll()
            list.forEach {
//                Timber.d("dsadasadsw2 ${gson.toJson(it)}")
            }
            Timber.d("dsadasadsw2 count: ${list.size}")
        }

        homeViewModel.grayDisplay.observe(this) { gray -> animateGrayTransition(gray) }

//        lifecycleScope.launch {
//            TaskQueueManager.addTasks(
//                listOf(
//                    "128113366",
//                    "99549359",
//                    "73205835",
//                ).mapNotNull {
//                    it.toLongOrNull()?.let {
//                        LandingPreviewTask(lifecycleScope, it)
//                    }
//                })
//
//            TaskQueueManager.startProcessing()
//        }

        if (SessionManager.loggedInUid > 0L) {
            requireAppBackground().config.observe(this) { config ->
                if (config.type == BackgroundType.RANDOM_FROM_FAVORITES) {
                    bgViewModel.result.observe(this) { loadResult ->
                        loadResult?.data?.displayList?.getOrNull(0)?.let { illust ->
                            onBackgroundIllustPrepared(illust)
                        }
                    }
                } else if (config.type == BackgroundType.SPECIFIC_ILLUST || config.type == BackgroundType.LOCAL_FILE) {
                    binding.dimmer.isVisible = true
                    Glide.with(this)
                        .load(config.localFileUri)
                        .transition(withCrossFade())
                        .into(binding.pageBackground)
                }
            }
        } else {
            homeViewModel.illustResponse.observe(this) { illustResponse ->
                illustResponse.displayList.getOrNull(0)?.let { illust ->
                    onBackgroundIllustPrepared(illust)
                }
            }
        }
    }

    private fun animateBackground(scale: Float) {
        binding.pageBackground.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(1000)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
    }

    private fun onBackgroundIllustPrepared(illust: Illust) {
        ObjectPool.update(illust)
        binding.dimmer.isVisible = true
        Glide.with(this)
            .load(
                GlideUrlChild(
                    illust.meta_single_page?.original_image_url
                        ?: illust.meta_pages?.getOrNull(0)?.image_urls?.original
                )
            )
            .transition(withCrossFade())
            .into(binding.pageBackground)
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

    private fun animateGrayTransition(toGray: Boolean) {
        val start = if (toGray) 1f else 0f
        val end = if (toGray) 0f else 1f

        val animator = ValueAnimator.ofFloat(start, end)
        animator.duration = 400
        animator.addUpdateListener {
            val saturation = it.animatedValue as Float
            val matrix = ColorMatrix().apply { setSaturation(saturation) }
            val paint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            }
            window.decorView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        }
        animator.start()
    }


    override fun toggleGrayMode() {
        homeViewModel.toggleGrayModeImpl()
    }

    private fun handleIntentLink(intent: Intent?) {
        val link = intent?.data?.toString()
        if (link.isNullOrEmpty()) return

        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val linkHandler = LinkHandler(navController)
        linkHandler.processLink(link)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        handleIntentLink(intent)
    }
}