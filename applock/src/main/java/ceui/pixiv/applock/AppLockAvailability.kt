package ceui.pixiv.applock

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL

/** 当前设备能不能用应用锁。 */
public enum class AppLockAvailability {
    /** 已有屏幕锁（可能另有指纹 / 面部），可以开启和解锁。 */
    AVAILABLE,

    /** 没有设置屏幕锁。应用锁不单独设密码，先去系统设置里设一个。 */
    NEEDS_SCREEN_LOCK,

    /** 系统暂时无法验证（安全更新待装、状态未知等），这时不开启，也不把用户锁在门外。 */
    UNAVAILABLE,
}

internal object Authenticators {

    /**
     * Class 2 生物识别或屏幕锁凭据。
     *
     * 不用 BIOMETRIC_STRONG：很多机型的面部解锁只到 Class 2，而这里没有 CryptoObject，
     * Class 3 的区别用不上；另外 STRONG | DEVICE_CREDENTIAL 在 Android 9–10 上不受支持，
     * 这个组合在 minSdk 24 起全部可用。
     */
    const val ALLOWED: Int = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun availability(context: Context): AppLockAvailability =
        when (BiometricManager.from(context).canAuthenticate(ALLOWED)) {
            BiometricManager.BIOMETRIC_SUCCESS -> AppLockAvailability.AVAILABLE
            // 允许屏幕锁凭据时，这两个结果都只说明「没设屏幕锁」：设了就能用。
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> AppLockAvailability.NEEDS_SCREEN_LOCK
            else -> AppLockAvailability.UNAVAILABLE
        }

    /** 官方文档的做法：Android 11+ 直达「设置生物识别 / 屏幕锁」，更早的系统进安全设置。 */
    fun openScreenLockSettings(context: Context) {
        val enroll = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, ALLOWED)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        try {
            context.startActivity(enroll)
        } catch (_: ActivityNotFoundException) {
            // 个别 ROM 没有实现 ACTION_BIOMETRIC_ENROLL，退回安全设置首页。
            context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
    }
}
