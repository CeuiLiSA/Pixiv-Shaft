package ceui.pixiv.applock

import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction

/**
 * 设置页「开启应用锁」：先用同一套系统验证确认一次，通过才开启，避免开了却解不开。
 *
 * 必须在 Fragment 的 onCreate / onViewCreated 里创建（BiometricPrompt 的官方要求），
 * 转屏重建时库会把进行中的弹窗和结果接回新实例。
 */
public class AppLockEnabler(
    private val fragment: Fragment,
    private val listener: Listener,
) {

    public interface Listener {
        public fun onEnabled()

        /** 无法开启的原因，宿主用自己的 Toast 展示。用户主动取消不回调。 */
        public fun onError(message: CharSequence)
    }

    private val prompt = BiometricPrompt(
        fragment,
        ContextCompat.getMainExecutor(fragment.requireContext()),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                AppLock.enable()
                listener.onEnabled()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED -> Unit
                    else -> listener.onError(errString)
                }
            }
        },
    )

    public fun start() {
        val context = fragment.requireContext()
        when (Authenticators.availability(context)) {
            AppLockAvailability.AVAILABLE -> prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(context.getString(R.string.applock_enable_prompt_title))
                    .setSubtitle(context.getString(R.string.applock_enable_prompt_subtitle))
                    .setAllowedAuthenticators(Authenticators.ALLOWED)
                    .setConfirmationRequired(false)
                    .build(),
            )
            AppLockAvailability.NEEDS_SCREEN_LOCK -> showNeedsScreenLock()
            AppLockAvailability.UNAVAILABLE -> listener.onError(context.getString(R.string.applock_unavailable))
        }
    }

    private fun showNeedsScreenLock() {
        val context = fragment.requireContext()
        WitDialog.MessageDialogBuilder(context)
            .setTitle(R.string.applock_needs_screen_lock_title)
            .setMessage(R.string.applock_needs_screen_lock_message)
            .addAction(R.string.applock_cancel) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.applock_open_settings, WitDialogAction.ACTION_PROP_POSITIVE) { dialog, _ ->
                dialog.dismiss()
                Authenticators.openScreenLockSettings(context)
            }
            .show()
    }
}
