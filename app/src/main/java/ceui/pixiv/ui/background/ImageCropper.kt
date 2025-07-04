package ceui.pixiv.ui.background

import android.app.Activity
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import ceui.pixiv.utils.TokenGenerator
import com.yalantis.ucrop.UCrop
import java.io.File

class ImageCropper(
    private val fragment: Fragment,
    private val aspectRatioX: Float = 9f,
    private val aspectRatioY: Float = 16f,
    private val onCropSuccess: (Uri) -> Unit,
    private val onCropError: ((Throwable) -> Unit)? = null
) {
    private val cropLauncher =
        fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                UCrop.getOutput(data)?.let(onCropSuccess)
            } else if (result.resultCode == UCrop.RESULT_ERROR && data != null) {
                val error = UCrop.getError(data)
                onCropError?.invoke(error ?: Exception("Unknown crop error"))
            }
        }

    fun startCrop(sourceUri: Uri) {
        val token = TokenGenerator.generateToken()
        val destFile =
            File(fragment.requireActivity().cacheDir, "shaft_background_$token.png").apply {
                createNewFile()
            }
        val destUri = destFile.toUri()
        val intent = UCrop.of(sourceUri, destUri)
            .withAspectRatio(aspectRatioX, aspectRatioY)
            .getIntent(fragment.requireContext())
        cropLauncher.launch(intent)
    }
}
