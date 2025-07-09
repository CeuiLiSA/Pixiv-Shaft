package ceui.pixiv.ui.background

import android.app.Activity
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import ceui.loxia.launchSuspend
import ceui.pixiv.utils.TokenGenerator
import com.yalantis.ucrop.UCrop
import timber.log.Timber
import java.io.File

class ImageCropper<FragmentT : Fragment>(
    private val fragment: FragmentT,
    private val aspectRatioX: Float = 9f,
    private val aspectRatioY: Float = 16f,
    private val onCropSuccess: FragmentT.(Uri) -> Unit,
) {
    private val cropLauncher =
        fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                UCrop.getOutput(data)?.let {
                    fragment.launchSuspend {
                        fragment.onCropSuccess(it)
                    }
                }
            } else if (result.resultCode == UCrop.RESULT_ERROR && data != null) {
                val error = UCrop.getError(data)
                Timber.e(error, "裁剪图片失败")
            }
        }

    fun startCrop(sourceUri: Uri) {
        val context = fragment.requireContext()
        val token = TokenGenerator.generateToken()
        val destFile =
            File(context.cacheDir, "shaft_background_$token.png").apply {
                createNewFile()
            }
        val destUri = destFile.toUri()
        val intent = UCrop.of(sourceUri, destUri)
            .withAspectRatio(aspectRatioX, aspectRatioY)
            .getIntent(context)
        cropLauncher.launch(intent)
    }
}
