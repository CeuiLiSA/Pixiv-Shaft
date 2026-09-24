package ceui.pixiv.utils

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.content.ContextCompat
import ceui.lisa.activities.Shaft

/** 收藏触感反馈的日志 tag（看设备实际走了哪档模拟）。 */
private const val HAPTIC_TAG = "LikeHaptic"

/** 收藏触感参数：按下段强度 / 段落间隙 ms / 弹起段强度（调手感改这里）。 */
private const val LIKE_HAPTIC_PRESS_SCALE = 0.8f
private const val LIKE_HAPTIC_GAP_MS = 200
private const val LIKE_HAPTIC_RELEASE_SCALE = 0.1f

/** 设置尚未加载时沿用原有的振动行为。 */
private fun isLikeHapticEnabled(): Boolean = Shaft.sSettings?.isLikeHapticEnable ?: true

/**
 * 收藏「按下」触感（插画卡 / 小说卡共用）：模拟 iOS 3D Touch 的段落感——先「重而长」
 * （压进去的闷震 THUD），停 100ms，再「轻而短」（弹起的细 tick 收尾）。
 * S(31)+ 用 composition primitives，首选 THUD(1.0)+TICK(0.3)，
 * 马达不支持 THUD 退 CLICK(1.0)+TICK(0.3)；
 * O(26)+ 用带振幅 waveform（30ms@满幅 → 100ms → 8ms@60）兜底，
 * 再往下退化成 KEYBOARD_TAP + 轻 CLOCK_TICK。
 */
fun playLikePressHaptic(view: View) {
    if (!isLikeHapticEnabled()) return
    val vibrator = ContextCompat.getSystemService(view.context, Vibrator::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && vibrator != null &&
        vibrator.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_THUD,
            VibrationEffect.Composition.PRIMITIVE_TICK,
        )
    ) {
        Log.d(
            HAPTIC_TAG,
            "composition primitives: THUD($LIKE_HAPTIC_PRESS_SCALE) + " +
                    "postDelayed(${LIKE_HAPTIC_GAP_MS}ms) + TICK($LIKE_HAPTIC_RELEASE_SCALE)"
        )
        // 段落间隙不能写进 composition 的 pause 参数：MIUI/HyperOS 等 HAL 会
        // 直接吞掉它（dumpsys vibrator_manager 实测请求 300/1000ms 实际总时长
        // 都只有 ~150ms）。拆成两次独立 vibrate，间隙由应用层 postDelayed 控制
        vibrator.vibrate(
            VibrationEffect.startComposition()
                .addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_THUD, LIKE_HAPTIC_PRESS_SCALE
                )
                .compose()
        )
        view.postDelayed({
            if (!isLikeHapticEnabled()) return@postDelayed
            vibrator.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(
                        VibrationEffect.Composition.PRIMITIVE_TICK,
                        LIKE_HAPTIC_RELEASE_SCALE,
                    )
                    .compose()
            )
        }, LIKE_HAPTIC_GAP_MS.toLong())
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && vibrator != null &&
        vibrator.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_TICK,
        )
    ) {
        Log.d(
            HAPTIC_TAG,
            "composition primitives (no THUD): CLICK($LIKE_HAPTIC_PRESS_SCALE) + " +
                    "postDelayed(${LIKE_HAPTIC_GAP_MS}ms) + TICK($LIKE_HAPTIC_RELEASE_SCALE)"
        )
        vibrator.vibrate(
            VibrationEffect.startComposition()
                .addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_CLICK, LIKE_HAPTIC_PRESS_SCALE
                )
                .compose()
        )
        view.postDelayed({
            if (!isLikeHapticEnabled()) return@postDelayed
            vibrator.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(
                        VibrationEffect.Composition.PRIMITIVE_TICK,
                        LIKE_HAPTIC_RELEASE_SCALE,
                    )
                    .compose()
            )
        }, LIKE_HAPTIC_GAP_MS.toLong())
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator?.hasVibrator() == true) {
        val effect = if (vibrator.hasAmplitudeControl()) {
            Log.d(HAPTIC_TAG, "waveform with amplitude: 30ms@255 + 100ms + 8ms@60")
            // [延迟, 重长 30ms@满幅, 段落间隙 100ms, 轻短 8ms@60]
            VibrationEffect.createWaveform(
                longArrayOf(0, 30, 100, 8), intArrayOf(0, 255, 0, 60), -1
            )
        } else {
            Log.d(HAPTIC_TAG, "waveform no-amplitude: 28ms + 100ms + 8ms")
            VibrationEffect.createWaveform(longArrayOf(0, 28, 100, 8), -1)
        }
        vibrator.vibrate(effect)
    } else {
        Log.d(HAPTIC_TAG, "fallback: KEYBOARD_TAP + 100ms + CLOCK_TICK")
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        view.postDelayed(
            {
                if (isLikeHapticEnabled()) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }, 100L
        )
    }
}

/** 取消收藏的单下轻触感，与收藏共用设置开关。 */
fun playUnlikeHaptic(view: View) {
    if (!isLikeHapticEnabled()) return
    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}
