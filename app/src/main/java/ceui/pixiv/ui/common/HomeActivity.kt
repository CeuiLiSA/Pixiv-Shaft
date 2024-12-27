package ceui.pixiv.ui.common

import android.animation.Animator
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.LiveData
import ceui.lisa.databinding.ActivityHomeBinding
import ceui.loxia.observeEvent
import ceui.pixiv.session.SessionManager
import ceui.pixiv.utils.INetworkState
import ceui.pixiv.utils.NetworkStateManager

class HomeActivity : AppCompatActivity(), INetworkState {

    private lateinit var binding: ActivityHomeBinding
    private val _networkStateManager by lazy { NetworkStateManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _networkStateManager
        enableEdgeToEdge()

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        SessionManager.newTokenEvent.observeEvent(this) {
            triggerOnce()
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

    override fun onDestroy() {
        super.onDestroy()
        _networkStateManager.unregisterNetworkCallback()
    }

    override val networkState: LiveData<NetworkStateManager.NetworkType> get() {
        return _networkStateManager.networkState
    }
}