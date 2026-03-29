package ceui.pixiv.widgets

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.fragment.app.FragmentManager
import ceui.lisa.R
import ceui.lisa.databinding.DialogRateAppBinding
import ceui.lisa.utils.Common
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.setOnClick
import com.google.android.play.core.review.ReviewManagerFactory
import timber.log.Timber

class RateAppDialog : PixivDialog(R.layout.dialog_rate_app) {

    private val binding by viewBinding(DialogRateAppBinding::bind)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("RateAppDialog onCreate")
    }

    override fun onViewFirstCreated(view: View) {
        super.onViewFirstCreated(view)
        Timber.d("RateAppDialog onViewFirstCreated")

        binding.btnRateNow.setOnClick {
            Timber.d("RateAppDialog btnRateNow clicked")
            launchInAppReview()
        }

        binding.btnMaybeLater.setOnClick {
            Timber.d("RateAppDialog btnMaybeLater clicked")
            RateAppManager.onUserChoseLater()
            dismissAllowingStateLoss()
        }

        binding.btnNever.setOnClick {
            Timber.d("RateAppDialog btnNever clicked")
            RateAppManager.onUserChoseNever()
            dismissAllowingStateLoss()
        }

        animateEntrance()
    }

    private fun launchInAppReview() {
        val activity = activity ?: run {
            Timber.w("RateAppDialog activity is null, falling back to Play Store")
            openPlayStoreFallback()
            return
        }
        Timber.d("RateAppDialog launching In-App Review flow")
        val manager = ReviewManagerFactory.create(activity)
        val request = manager.requestReviewFlow()
        request.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Timber.d("RateAppDialog requestReviewFlow succeeded, launching review")
                val flow = manager.launchReviewFlow(activity, task.result)
                flow.addOnCompleteListener {
                    Timber.d("RateAppDialog launchReviewFlow completed")
                    RateAppManager.onUserRated()
                    dismissAllowingStateLoss()
                }
            } else {
                Timber.w(task.exception, "RateAppDialog requestReviewFlow failed, falling back")
                openPlayStoreFallback()
            }
        }
    }

    private fun openPlayStoreFallback() {
        Timber.d("RateAppDialog openPlayStoreFallback")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=ceui.pixiv.pshaft")))
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "RateAppDialog market:// intent failed")
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=ceui.pixiv.pshaft")
                    )
                )
            } catch (e2: Exception) {
                Timber.e(e2, "RateAppDialog Play Store web fallback also failed")
                Common.showToast("Unable to open Play Store")
            }
        }
        RateAppManager.onUserRated()
        dismissAllowingStateLoss()
    }

    private fun animateEntrance() {
        binding.heartIcon.apply {
            scaleX = 0f
            scaleY = 0f
            animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(500)
                .setInterpolator(OvershootInterpolator(2f))
                .setStartDelay(150)
                .start()
        }

        val stars = listOf(
            binding.star1,
            binding.star2,
            binding.star3,
            binding.star4,
            binding.star5
        )
        stars.forEachIndexed { index, star ->
            star.scaleX = 0f
            star.scaleY = 0f
            star.alpha = 0f

            val delay = 400L + (index * 100L)

            val scaleX = ObjectAnimator.ofFloat(star, View.SCALE_X, 0f, 1.3f, 1f)
            val scaleY = ObjectAnimator.ofFloat(star, View.SCALE_Y, 0f, 1.3f, 1f)
            val alpha = ObjectAnimator.ofFloat(star, View.ALPHA, 0f, 1f)

            AnimatorSet().apply {
                playTogether(scaleX, scaleY, alpha)
                duration = 350
                startDelay = delay
                interpolator = OvershootInterpolator(1.5f)
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: android.animation.Animator) {
                        star.setImageResource(R.drawable.ic_rate_star)
                    }
                })
                start()
            }
        }
    }

    companion object {
        private const val TAG = "RateAppDialog"

        fun showIfNeeded(fragmentManager: FragmentManager) {
            if (!RateAppManager.shouldShowRateDialog()) return
            if (fragmentManager.findFragmentByTag(TAG) != null) return
            RateAppDialog().show(fragmentManager, TAG)
        }
    }
}
